package com.casla.eclipse.ai.completion.abap;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.ContextExtractor;
import com.casla.eclipse.ai.completion.CursorContextType;
import com.casla.eclipse.ai.completion.RelatedFileCollector;

/**
 * ADT hands the content-assist provider only an ITextViewer and an offset --
 * none of JDT's compilation-unit structure -- so this extracts a plain
 * character window around the cursor. Two things keep that window useful:
 * snapping it to line boundaries (a window that starts or ends mid-token
 * confuses the model with broken syntax) and labelling it with the active
 * editor's input name, the closest thing to a project/file identity ADT
 * exposes here (no package/import equivalent is attempted).
 */
public final class AbapContextExtractor {
    public CodeContext extract(IDocument document, int offset, CompletionSettings settings) throws BadLocationException {
        return extract(document, offset, activeEditorLabel(), settings);
    }

    public CodeContext extract(IDocument document, int offset, String label, CompletionSettings settings) throws BadLocationException {
        int safeOffset = Math.max(0, Math.min(offset, document.getLength()));
        String fullDocument = document.get();

        int beforeStart = startOfLine(document, Math.max(0, safeOffset - settings.contextBefore()));
        int afterEnd = endOfLine(document, Math.min(document.getLength(), safeOffset + settings.contextAfter()));

        String actualLabel = label != null && !label.isBlank() ? label : activeEditorLabel();
        CursorContextType contextType = ContextExtractor.detectCursorContext(fullDocument, safeOffset, "ABAP");
        String structureHint = AbapStructureHint.scan(document, safeOffset);
        List<RelatedFileCollector.RelatedFile> relatedFiles = withScope(
            withMethodSignature(new RelatedFileCollector().collect(null, actualLabel), fullDocument, document, safeOffset),
            fullDocument
        );

        return new CodeContext(
            "",
            actualLabel,
            "ABAP",
            "",
            "",
            structureHint,
            document.get(beforeStart, safeOffset - beforeStart),
            document.get(safeOffset, afterEnd - safeOffset),
            safeOffset,
            modificationStamp(document),
            CodeContext.fingerprint(fullDocument, safeOffset),
            contextType,
            relatedFiles
        );
    }

    /**
     * Inside a METHOD body, the prompt otherwise never sees the
     * IMPORTING/EXPORTING/RETURNING parameter names declared on the matching
     * METHODS line in DEFINITION -- so completions guess at them instead of
     * using the real ones. When found, put it first: it's the single most
     * relevant piece of context for the exact position being completed.
     */
    private static List<RelatedFileCollector.RelatedFile> withMethodSignature(
        List<RelatedFileCollector.RelatedFile> collected, String fullDocument, IDocument document, int offset
    ) {
        String methodName = AbapStructureHint.enclosingMethodName(document, offset);
        if (methodName.isBlank()) return collected;
        String signature = AbapMethodSignatureLookup.find(fullDocument, methodName);
        if (signature.isBlank()) return collected;

        List<RelatedFileCollector.RelatedFile> withSignature = new ArrayList<>(collected.size() + 1);
        withSignature.add(new RelatedFileCollector.RelatedFile("(DEFINITION) " + methodName + " signature", signature));
        withSignature.addAll(collected);
        return withSignature;
    }

    /**
     * Placed right after the method signature (if any) and ahead of related
     * open-editor skeletons: a real identifier/type table is a more direct
     * defense against invented variable names than a preview of another file.
     */
    private static List<RelatedFileCollector.RelatedFile> withScope(
        List<RelatedFileCollector.RelatedFile> collected, String fullDocument
    ) {
        String scope = AbapScopeExtractor.describe(fullDocument);
        if (scope.isBlank()) return collected;

        List<RelatedFileCollector.RelatedFile> withScope = new ArrayList<>(collected.size() + 1);
        int insertAt = collected.isEmpty() || !collected.get(0).path().startsWith("(DEFINITION)") ? 0 : 1;
        withScope.addAll(collected.subList(0, insertAt));
        withScope.add(new RelatedFileCollector.RelatedFile("Scope", scope));
        withScope.addAll(collected.subList(insertAt, collected.size()));
        return withScope;
    }

    private static int startOfLine(IDocument document, int offset) throws BadLocationException {
        int line = document.getLineOfOffset(offset);
        return document.getLineOffset(line);
    }

    private static int endOfLine(IDocument document, int offset) throws BadLocationException {
        int line = document.getLineOfOffset(offset);
        return document.getLineOffset(line) + document.getLineLength(line);
    }

    private static long modificationStamp(IDocument document) {
        return document instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }

    /**
     * Best-effort label for the object being edited. ADT's editor input for a
     * remote ABAP object is not backed by a local IFile, so there is no
     * reliable way to recover a project/package here; the editor's display
     * name (typically the object name) is the one identity signal reachable
     * without depending on ADT-internal types beyond the extension point
     * itself. Safe to call from the UI thread only -- see the caller.
     */
    private static String activeEditorLabel() {
        try {
            var window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) return "";
            IEditorPart editor = window.getActivePage().getActiveEditor();
            return editor == null || editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        } catch (RuntimeException notOnUiThreadOrUnavailable) {
            return "";
        }
    }
}
