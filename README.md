# Eclipse Extension 9Router — Casla AI Code Assistant

AI-assisted code completion for Eclipse with first-class SAP ABAP/ADT support and an OpenAI-compatible backend. The extension combines inline ghost text, local adaptive learning, model routing, validation, and explicit AI code actions while keeping learned workspace memory local to Eclipse.

## Highlights in v0.4.0

- ABAP inline ghost completion with full floating preview for multi-line suggestions.
- Conservative mid-line ABAP completion: suggestions before existing source use floating preview and only replace a same-line token when the overlap is exact and safe.
- `Tab` accepts the full ghost, `Ctrl+Right` accepts the next word, `Ctrl+Down` accepts the next line, and `Esc` dismisses it.
- ABAP quality gate normalizes safe operator spacing and rejects structurally unsafe completions.
- Workspace style learning for inline declarations, table access style, keyword case, naming prefixes, modern ABAP syntax, and multi-line method-call/parameter-section layout.
- Contextual accepted-example memory, ABAP object skeleton memory, adaptive model routing, and local feedback statistics.
- **Fix Selection with AI** (`Ctrl+Alt+F`): fixes selected ABAP or the current line, includes nearby Eclipse/ADT diagnostics when available, validates the replacement, and asks before applying it.
- **AI Next Edit** (`Ctrl+Alt+N`): after repeated similar manual edits in the same file, offers a conservative next occurrence and asks before applying it. It can be disabled separately in Adaptive Learning preferences.
- Java Content Assist support remains available through `Ctrl+Space`.

## Requirements

- Eclipse IDE 2026-06 / Platform 4.40 or newer.
- Java 21 runtime.
- Eclipse JDT UI.
- SAP ABAP Development Tools for native ABAP `Ctrl+Space` proposal integration. ABAP ghost text itself does not require ADT-specific classes at build time.
- An OpenAI-compatible endpoint.

Default endpoint:

```text
http://localhost:20128/v1
```

## Install using the p2 ZIP

1. Extract `casla-eclipse-ai-assistant-0.4.0-p2.zip` to a folder.
2. In Eclipse, open **Help → Install New Software**.
3. Click **Add → Local** and select the extracted folder.
4. Select **Casla AI Code Assistant** and complete installation.
5. Restart Eclipse.

## Dropins fallback

Extract `casla-eclipse-ai-assistant-0.4.0-dropins.zip` directly into the Eclipse installation directory, preserving the `dropins/casla-ai/plugins/...` layout, then restart Eclipse with `-clean` once.

## Configure

Open:

```text
Window → Preferences → AI Code Assistant
```

Enter Base URL and API key, then press **Test connection**. In Auto mode the extension scores usable models and adapts routing using local acceptance feedback. In Manual mode the model field remains editable, including when `/v1/models` is unavailable.

Adaptive controls are under:

```text
Window → Preferences → AI Code Assistant → Adaptive Learning
```

There you can pause local adaptive learning, bound local memory, reset learning layers, and enable/disable AI Next Edit.

The API key is encrypted through Eclipse Secure Storage. It is never written to normal Eclipse preferences or logs.

## ABAP usage

Automatic completion is rendered as ghost text when enabled. Single-line insertions are shown inline when safe; multi-line or mid-line suggestions use a floating preview so existing ADT source is never painted over or reflowed.

Keyboard actions while a ghost is visible:

```text
Tab          Accept full suggestion
Ctrl+Right   Accept next word
Ctrl+Down    Accept next line
Esc          Dismiss suggestion
```

Explicit code actions:

```text
Ctrl+Alt+F   Fix selected ABAP/current line with AI
Ctrl+Alt+N   Apply the current AI Next Edit suggestion
```

Native ADT `Ctrl+Space` also receives AI proposals when the ADT client proposal provider is available.

## Build

From PowerShell:

```powershell
.\build.ps1
```

Override local tool locations if necessary:

```powershell
.\build.ps1 -EclipseRoot C:\path\to\eclipse -JdkRoot C:\path\to\jdk-21
```

The build compiles the plugin and tests, runs Core, Adaptive Learning, and mid-line edit-planner regression suites, publishes a p2 repository, and packages release artifacts. An optional live integration test runs when `AI_CODE_ASSISTANT_API_KEY` is present.

Artifacts are written to `dist/`:

```text
casla-eclipse-ai-assistant-0.4.0-p2.zip
casla-eclipse-ai-assistant-0.4.0-dropins.zip
casla-eclipse-ai-assistant-0.4.0-source.zip
SHA256SUMS.txt
```

## Security and privacy

- Plain HTTP is accepted only for localhost/loopback endpoints; remote endpoints must use HTTPS.
- Authorization headers, API keys, prompts, and source code are not logged.
- Completion context is bounded rather than uploading the entire repository.
- Adaptive memory is local to Eclipse plugin state. It stores aggregate style/feedback, normalized bounded accepted snippets, and bounded object skeletons rather than raw workspace telemetry.
- AI Fix and AI Next Edit require review/confirmation before changing source.
- A localhost URL may still proxy requests to a remote provider; verify the gateway's data handling before using proprietary source code.

## Architecture

```text
ABAP / Java Editor
        ↓
Context extraction + local adaptive memory
        ↓
Adaptive model routing
        ↓
OpenAI-compatible client
        ↓
Sanitizer + ABAP quality/structural validation
        ↓
Ghost / Content Assist / explicit AI code action
        ↓
Local feedback + style/example/object learning
```

## License

MIT
