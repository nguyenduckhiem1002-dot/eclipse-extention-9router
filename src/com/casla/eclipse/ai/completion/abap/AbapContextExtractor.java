package com.casla.eclipse.ai.completion.abap;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.PlatformUI;

import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.completion.CodeContext;

/**
 * ADT hands the content-assist provider only an ITextViewer and an offset --
 * none of JDT's compilation-unit structure -- so this extracts a plain
 * character window around the cursor. Two things keep that window useful:
 * snapping it to line boundaries (a window that starts or ends mid-token
 * confuses the model with broken syntax) and labelling it with the active
 * editor's input name, the closest thing to a project/file identity ADT
 * exposes here (no package/import equivalent is attempted).
 */
final class AbapContextExtractor {
    CodeContext extract(IDocument document, int offset, CompletionSettings settings) throws BadLocationException {
        int safeOffset = Math.max(0, Math.min(offset, document.getLength()));
        String fullDocument = document.get();

        int beforeStart = startOfLine(document, Math.max(0, safeOffset - settings.contextBefore()));
        int afterEnd = endOfLine(document, Math.min(document.getLength(), safeOffset + settings.contextAfter()));

        return new CodeContext(
            "",
            activeEditorLabel(),
            "ABAP",
            "",
            "",
            AbapStructureHint.scan(document, safeOffset),
            document.get(beforeStart, safeOffset - beforeStart),
            document.get(safeOffset, afterEnd - safeOffset),
            safeOffset,
            modificationStamp(document),
            CodeContext.fingerprint(fullDocument, safeOffset)
        );
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
