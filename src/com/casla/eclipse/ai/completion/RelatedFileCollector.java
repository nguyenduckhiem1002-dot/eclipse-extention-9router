package com.casla.eclipse.ai.completion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IType;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.AiPlugin;

public final class RelatedFileCollector {
    public record RelatedFile(String path, String summary) {}

    private static final int MAX_RELATED_FILES = 4;
    private static final int MAX_CHARS_PER_FILE = 1200;

    private record CachedSkeleton(long modificationStamp, String skeleton) {}

    /** Keyed by editor path/name; re-extracted only when the document's modification stamp moves. */
    private static final ConcurrentHashMap<String, CachedSkeleton> SKELETON_CACHE = new ConcurrentHashMap<>();

    /**
     * Collects summaries of related source files in the project or neighboring open tabs.
     */
    public List<RelatedFile> collect(ICompilationUnit compilationUnit, String currentPath) {
        List<RelatedFile> results = new ArrayList<>();
        Set<String> visitedPaths = new HashSet<>();
        if (currentPath != null && !currentPath.isBlank()) {
            visitedPaths.add(currentPath);
        }

        // 1. Try collecting from open workbench editors (highest relevancy)
        collectFromOpenEditors(results, visitedPaths);

        // 2. If JDT compilation unit is available and we need more context, collect from same package / imports
        if (compilationUnit != null && results.size() < MAX_RELATED_FILES) {
            collectFromPackageAndImports(compilationUnit, results, visitedPaths);
        }

        return results;
    }

    private void collectFromOpenEditors(List<RelatedFile> results, Set<String> visitedPaths) {
        if (!PlatformUI.isWorkbenchRunning()) return;
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null) return;
            IWorkbenchPage page = window.getActivePage();
            if (page == null) return;

            for (IEditorReference ref : page.getEditorReferences()) {
                if (results.size() >= MAX_RELATED_FILES) break;
                IEditorPart editor = ref.getEditor(false);
                if (editor instanceof ITextEditor textEditor) {
                    IEditorInput input = textEditor.getEditorInput();
                    if (input == null) continue;
                    String name = input.getName();
                    if (name == null) continue;
                    boolean isJava = name.endsWith(".java");
                    boolean isAbap = name.endsWith(".abap") || isAbapEditor(textEditor, name);
                    if (!isJava && !isAbap) continue;
                    String path = input.getToolTipText();
                    if (path == null || path.isBlank()) path = name;
                    if (visitedPaths.contains(path) || visitedPaths.contains(name)) continue;

                    var docProvider = textEditor.getDocumentProvider();
                    if (docProvider != null) {
                        var doc = docProvider.getDocument(input);
                        if (doc != null) {
                            String skeleton = cachedSkeleton(path, doc, isAbap);
                            if (!skeleton.isBlank()) {
                                visitedPaths.add(path);
                                visitedPaths.add(name);
                                results.add(new RelatedFile(name, skeleton));
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // Non-critical background feature
        }
    }

    /** Re-extracting a skeleton on every keystroke of every open editor is wasted work when nothing there changed. */
    private static String cachedSkeleton(String cacheKey, IDocument doc, boolean isAbap) {
        long modificationStamp = doc instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
        CachedSkeleton cached = SKELETON_CACHE.get(cacheKey);
        if (cached != null
            && modificationStamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP
            && cached.modificationStamp() == modificationStamp) {
            return cached.skeleton();
        }
        String skeleton = isAbap ? extractAbapSkeleton(doc.get()) : extractSkeleton(doc.get());
        SKELETON_CACHE.put(cacheKey, new CachedSkeleton(modificationStamp, skeleton));
        return skeleton;
    }

    public static boolean isAbapEditor(ITextEditor editor, String label) {
        if (label != null && label.endsWith(".abap")) return true;
        if (editor != null) {
            try {
                if (editor.getSite() != null) {
                    String siteId = editor.getSite().getId();
                    if (siteId != null) {
                        String lower = siteId.toLowerCase();
                        if (lower.contains("abap") || lower.contains("adt")) return true;
                    }
                }
                String className = editor.getClass().getName().toLowerCase();
                if (className.contains("abap") || className.contains("adt")) return true;
            } catch (Exception ignored) {}
        }
        return false;
    }

    private void collectFromPackageAndImports(
        ICompilationUnit cu,
        List<RelatedFile> results,
        Set<String> visitedPaths
    ) {
        try {
            // Sibling files in same package
            if (cu.getParent() instanceof IPackageFragment pkg) {
                for (ICompilationUnit sibling : pkg.getCompilationUnits()) {
                    if (results.size() >= MAX_RELATED_FILES) break;
                    String siblingName = sibling.getElementName();
                    String path = sibling.getPath() != null ? sibling.getPath().toString() : siblingName;
                    if (visitedPaths.contains(path) || visitedPaths.contains(siblingName)) continue;

                    String source = sibling.getSource();
                    if (source != null && !source.isBlank()) {
                        String skeleton = extractSkeleton(source);
                        if (!skeleton.isBlank()) {
                            visitedPaths.add(path);
                            visitedPaths.add(siblingName);
                            results.add(new RelatedFile(siblingName, skeleton));
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // JDT model might be locked or not fully indexed
        }
    }

    /**
     * Extracts a concise skeleton of a Java source file (class/interface declarations,
     * fields, and method signatures) while dropping method bodies.
     */
    public static String extractSkeleton(String source) {
        if (source == null || source.isBlank()) return "";
        StringBuilder skeleton = new StringBuilder();
        String[] lines = source.split("\r?\n");
        int count = 0;
        boolean inBlockComment = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("/*")) {
                inBlockComment = !trimmed.endsWith("*/");
                continue;
            }
            if (inBlockComment) {
                if (trimmed.endsWith("*/")) inBlockComment = false;
                continue;
            }
            if (trimmed.startsWith("//") || trimmed.isEmpty()) continue;

            // Keep package, imports, class/interface/record/enum declarations, annotations, method signatures, field declarations
            if (trimmed.startsWith("package ")
                || trimmed.startsWith("import ")
                || trimmed.startsWith("@")
                || trimmed.contains("class ")
                || trimmed.contains("interface ")
                || trimmed.contains("record ")
                || trimmed.contains("enum ")
                || (trimmed.startsWith("public ") || trimmed.startsWith("protected ") || trimmed.startsWith("private "))
                || trimmed.endsWith(";")
                || trimmed.endsWith("{")
            ) {
                // If it's a method declaration with open brace, strip body and show signature
                String formatted = line;
                if (formatted.length() > 120) {
                    formatted = formatted.substring(0, 117) + "...";
                }
                skeleton.append(formatted).append("\n");
                count += formatted.length();
                if (count >= MAX_CHARS_PER_FILE) {
                    skeleton.append("// ... (truncated)\n");
                    break;
                }
            }
        }
        return skeleton.toString().stripTrailing();
    }

    /**
     * Extracts a concise skeleton of an ABAP source file (class/interface declarations,
     * section headers, method declarations, and data/type structures).
     */
    public static String extractAbapSkeleton(String source) {
        if (source == null || source.isBlank()) return "";
        StringBuilder skeleton = new StringBuilder();
        String[] lines = source.split("\r?\n");
        int count = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("*") || trimmed.startsWith("\"")) {
                continue;
            }
            String upper = trimmed.toUpperCase();

            // Keep structural ABAP definitions
            if (upper.startsWith("CLASS ")
                || upper.startsWith("INTERFACE ")
                || upper.startsWith("ENDCLASS")
                || upper.startsWith("ENDINTERFACE")
                || upper.startsWith("PUBLIC SECTION")
                || upper.startsWith("PROTECTED SECTION")
                || upper.startsWith("PRIVATE SECTION")
                || upper.startsWith("METHODS ")
                || upper.startsWith("CLASS-METHODS ")
                || upper.startsWith("DATA")
                || upper.startsWith("TYPES")
                || upper.startsWith("CONSTANTS")
                || upper.startsWith("INTERFACES ")
                || upper.startsWith("EVENTS ")
                || upper.startsWith("ALIASES ")
            ) {
                String formatted = line;
                if (formatted.length() > 120) {
                    formatted = formatted.substring(0, 117) + "...";
                }
                skeleton.append(formatted).append("\n");
                count += formatted.length();
                if (count >= MAX_CHARS_PER_FILE) {
                    skeleton.append("\" ... (truncated)\n");
                    break;
                }
            }
        }
        return skeleton.toString().stripTrailing();
    }
}
