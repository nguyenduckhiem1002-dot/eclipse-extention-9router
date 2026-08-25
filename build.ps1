param(
    [string]$EclipseRoot = "",
    [string]$JdkRoot = "",
    [string]$Version = "0.3.0"
)

$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path

# Auto-detect Eclipse root if not provided or path does not exist
$EclipseCandidates = @(
    $EclipseRoot,
    "C:\Users\admin\Downloads\eclipse-java-2026-06-R-win32-x86_64\eclipse",
    "C:\Users\CNTT-KHIEM\Downloads\eclipse-java-2026-06-R-win32-x86_64\eclipse",
    "C:\Program Files\Eclipse"
)
foreach ($candidate in $EclipseCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path (Join-Path $candidate "plugins"))) {
        $EclipseRoot = $candidate
        break
    }
}

# Auto-detect JDK 21 root if not provided or path does not exist
$JustjJre = $null
if (Test-Path $EclipseRoot) {
    $JustjCandidate = Get-ChildItem (Join-Path $EclipseRoot "plugins") -Filter "org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_*" -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($JustjCandidate) {
        $JustjJre = Join-Path $JustjCandidate "jre"
    }
}

$JdkCandidates = @(
    $JdkRoot,
    $JustjJre,
    "C:\Program Files\Android\Android Studio\jbr",
    "C:\Program Files\Eclipse Adoptium\jdk-21*",
    "C:\Program Files\Java\jdk-21*"
)
foreach ($candidate in $JdkCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate)) {
        $resolved = (Resolve-Path $candidate -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty Path)
        if ($resolved -and (Test-Path (Join-Path $resolved "bin\javac.exe"))) {
            $JdkRoot = $resolved
            break
        }
    }
}

$BuildRoot = Join-Path $ProjectRoot "build"
$Classes = Join-Path $BuildRoot "classes"
$TestClasses = Join-Path $BuildRoot "test-classes"
$PublisherSource = Join-Path $BuildRoot "publisher-source"
$P2Repository = Join-Path $BuildRoot "p2-repository"
$JavaTemp = Join-Path $BuildRoot "java-temp"
$Dist = Join-Path $ProjectRoot "dist"
$PluginId = "com.casla.eclipse.ai"
$FeatureId = "com.casla.eclipse.ai.feature"
$Javac = Join-Path $JdkRoot "bin\javac.exe"
$Java = Join-Path $JdkRoot "bin\java.exe"
$Jar = Join-Path $JdkRoot "bin\jar.exe"
$EquinoxLauncher = Get-ChildItem (Join-Path $EclipseRoot "plugins") -Filter "org.eclipse.equinox.launcher_*.jar" |
    Select-Object -First 1 -ExpandProperty FullName

foreach ($required in @($Javac, $Java, $Jar, $EquinoxLauncher)) {
    if (-not (Test-Path $required)) { throw "Required build tool not found: $required (EclipseRoot: $EclipseRoot, JdkRoot: $JdkRoot)" }
}

# SAP ABAP Development Tools is not part of the public Eclipse download (it
# requires a separate install from SAP's own update site), so a vanilla
# EclipseRoot -- like the one CI downloads fresh every run -- won't have it.
# MANIFEST.MF already marks the ADT bundles resolution:=optional for exactly
# this reason; AiAbapProposalsProvider is the one class that imports an ADT
# type directly, so without ADT present it's excluded from this build. Ghost
# text's ABAP support (GhostTextController, AbapContextExtractor, and the
# rest of the abap package) has no ADT import and is unaffected either way.
$AdtAvailable = $null -ne (Get-ChildItem (Join-Path $EclipseRoot "plugins") -Filter "com.sap.adt.util.ui_*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1)
if (-not $AdtAvailable) {
    Write-Host "SAP ADT not found under $EclipseRoot\plugins -- building without the ABAP Ctrl+Space popup provider (ghost text is unaffected)."
}

if (Test-Path $BuildRoot) { Remove-Item -LiteralPath $BuildRoot -Recurse -Force }
if (Test-Path $Dist) { Remove-Item -LiteralPath $Dist -Recurse -Force }
New-Item -ItemType Directory -Force $Classes, $TestClasses, $PublisherSource, $P2Repository, $JavaTemp, $Dist | Out-Null

$AdtOnlySource = Join-Path $ProjectRoot "src\com\casla\eclipse\ai\completion\abap\AiAbapProposalsProvider.java"
$SourceFiles = Get-ChildItem (Join-Path $ProjectRoot "src") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
if (-not $AdtAvailable) {
    $SourceFiles = $SourceFiles | Where-Object { $_ -ne $AdtOnlySource }
}
& $Javac --release 21 -encoding UTF-8 -cp "$EclipseRoot\plugins\*" -d $Classes $SourceFiles
if ($LASTEXITCODE -ne 0) { throw "Plugin compilation failed." }

$TestFiles = Get-ChildItem (Join-Path $ProjectRoot "tests") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
& $Javac --release 21 -encoding UTF-8 -cp "$Classes;$EclipseRoot\plugins\*" -d $TestClasses $TestFiles
if ($LASTEXITCODE -ne 0) { throw "Test compilation failed." }
& $Java -ea -cp "$TestClasses;$Classes;$EclipseRoot\plugins\*" com.casla.eclipse.ai.tests.CoreTests
if ($LASTEXITCODE -ne 0) { throw "Core tests failed." }
& $Java -ea -cp "$TestClasses;$Classes;$EclipseRoot\plugins\*" com.casla.eclipse.ai.tests.AdaptiveLearningTests
if ($LASTEXITCODE -ne 0) { throw "Adaptive learning tests failed." }
if (-not [string]::IsNullOrWhiteSpace($env:AI_CODE_ASSISTANT_API_KEY)) {
    & $Java -ea -cp "$TestClasses;$Classes;$EclipseRoot\plugins\*" com.casla.eclipse.ai.tests.LiveEndpointTest
    if ($LASTEXITCODE -ne 0) { throw "Live endpoint test failed." }
}

$PluginStage = Join-Path $BuildRoot "plugin-stage"
New-Item -ItemType Directory -Force $PluginStage | Out-Null
Copy-Item -Path (Join-Path $Classes "*") -Destination $PluginStage -Recurse
Copy-Item -Path (Join-Path $ProjectRoot "icons") -Destination $PluginStage -Recurse

$PluginXml = Get-Content (Join-Path $ProjectRoot "plugin.xml") -Raw
if (-not $AdtAvailable) {
    # Without AiAbapProposalsProvider in the jar, declaring this extension
    # would point at a class that doesn't exist in this particular build.
    $PluginXml = [regex]::Replace(
        $PluginXml,
        '(?s)\r?\n\s*<extension\s+point="com\.sap\.adt\.tools\.abapsource\.ui\.clientProposalProvider">.*?</extension>\r?\n',
        "`n"
    )
}
Set-Content -Path (Join-Path $PluginStage "plugin.xml") -Value $PluginXml -Encoding UTF8 -NoNewline

Copy-Item -Path (Join-Path $ProjectRoot "README.md") -Destination $PluginStage
Copy-Item -Path (Join-Path $ProjectRoot "LICENSE") -Destination $PluginStage

$PluginDirectory = Join-Path $PublisherSource "plugins"
$FeatureDirectory = Join-Path $PublisherSource "features\${FeatureId}_${Version}"
New-Item -ItemType Directory -Force $PluginDirectory, $FeatureDirectory | Out-Null
$PluginJar = Join-Path $PluginDirectory "${PluginId}_${Version}.jar"
& $Jar --create --file $PluginJar --manifest (Join-Path $ProjectRoot "META-INF\MANIFEST.MF") -C $PluginStage .
if ($LASTEXITCODE -ne 0) { throw "Plugin JAR creation failed." }
Copy-Item -Path (Join-Path $ProjectRoot "feature\feature.xml") -Destination $FeatureDirectory
Copy-Item -Path (Join-Path $ProjectRoot "LICENSE") -Destination $FeatureDirectory

$RepoUri = ([Uri]$P2Repository).AbsoluteUri
& $Java "-Djava.io.tmpdir=$JavaTemp" -jar $EquinoxLauncher -nosplash -consoleLog -clean `
    -configuration (Join-Path $BuildRoot "publisher-config") `
    -data (Join-Path $BuildRoot "publisher-workspace") `
    -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher `
    -metadataRepository $RepoUri -artifactRepository $RepoUri `
    -source $PublisherSource -compress -publishArtifacts
if ($LASTEXITCODE -ne 0) { throw "p2 repository publishing failed." }
if (-not (Test-Path (Join-Path $P2Repository "content.jar")) -or
    -not (Test-Path (Join-Path $P2Repository "artifacts.jar")) -or
    -not (Test-Path (Join-Path $P2Repository "plugins\${PluginId}_${Version}.jar"))) {
    throw "p2 publisher returned without producing repository metadata."
}

$CategoryFile = ([Uri](Join-Path $ProjectRoot "category.xml")).AbsoluteUri
& $Java "-Djava.io.tmpdir=$JavaTemp" -jar $EquinoxLauncher -nosplash -consoleLog -clean `
    -configuration (Join-Path $BuildRoot "category-config") `
    -data (Join-Path $BuildRoot "category-workspace") `
    -application org.eclipse.equinox.p2.publisher.CategoryPublisher `
    -metadataRepository $RepoUri -categoryDefinition $CategoryFile -compress
if ($LASTEXITCODE -ne 0) { throw "p2 category publishing failed." }

$P2Zip = Join-Path $Dist "casla-eclipse-ai-assistant-${Version}-p2.zip"
Compress-Archive -Path (Join-Path $P2Repository "*") -DestinationPath $P2Zip -CompressionLevel Optimal

$DropinsRoot = Join-Path $BuildRoot "dropins\casla-ai\plugins"
New-Item -ItemType Directory -Force $DropinsRoot | Out-Null
Copy-Item $PluginJar $DropinsRoot
$DropinsZip = Join-Path $Dist "casla-eclipse-ai-assistant-${Version}-dropins.zip"
Compress-Archive -Path (Join-Path $BuildRoot "dropins\*") -DestinationPath $DropinsZip -CompressionLevel Optimal

$SourceZip = Join-Path $Dist "casla-eclipse-ai-assistant-${Version}-source.zip"
$SourceItems = @(
    ".classpath", ".project", ".settings", ".github", "META-INF", "feature", "icons", "src", "tests",
    ".gitignore", "build.properties", "build.ps1", "category.xml", "LICENSE", "plugin.xml", "README.md"
) | ForEach-Object { Join-Path $ProjectRoot $_ } | Where-Object { Test-Path $_ }
Compress-Archive -Path $SourceItems -DestinationPath $SourceZip -CompressionLevel Optimal

Get-FileHash -Path @($P2Zip, $DropinsZip, $SourceZip) -Algorithm SHA256 |
    ForEach-Object { "$($_.Hash)  $(Split-Path $_.Path -Leaf)" } |
    Set-Content -Encoding UTF8 (Join-Path $Dist "SHA256SUMS.txt")

Write-Host "Build complete:"
Get-ChildItem $Dist | Select-Object Name, Length
