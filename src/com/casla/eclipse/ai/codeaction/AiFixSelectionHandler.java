package com.casla.eclipse.ai.codeaction;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.client.ApiException;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.abap.AbapContextExtractor;
import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/** Explicit, review-before-apply ABAP repair action. */
public final class AiFixSelectionHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart active = HandlerUtil.getActiveEditor(event);
        ITextEditor editor = active == null ? null : active.getAdapter(ITextEditor.class);
        if (editor == null || editor.getDocumentProvider() == null) return null;
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (document == null) return null;
        if (!AiRuntime.get().snapshot().canComplete()) {
            MessageDialog.openInformation(editor.getSite().getShell(), "Fix with AI", "AI connection/model is not ready. Open AI Code Assistant preferences and test the connection first.");
            return null;
        }

        Target target;
        try {
            target = target(document, editor.getSite().getSelectionProvider().getSelection());
        } catch (BadLocationException error) {
            return null;
        }
        if (target.text().isBlank()) return null;

        CodeContext context;
        try {
            context = new AbapContextExtractor().extract(document, target.offset(), new AiPreferences().completionSettings());
        } catch (Exception error) {
            AiPlugin.logError("Could not prepare ABAP context for AI fix.", error);
            return null;
        }

        long stamp = modificationStamp(document);
        AiCodeActionPromptBuilder.Prompt prompt = new AiCodeActionPromptBuilder().fix(context, target.text(), "");
        Job job = new Job("Fix ABAP selection with AI") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                try {
                    String replacement = AiRuntime.get().completeCodeAction(context, prompt.system(), prompt.user(), monitor).content();
                    if (replacement == null || replacement.isBlank() || replacement.equals(target.text())) return Status.OK_STATUS;
                    Display.getDefault().asyncExec(() -> previewAndApply(editor, document, target, replacement, stamp));
                } catch (ApiException error) {
                    AiPlugin.logError("AI fix failed: " + error.getMessage(), error);
                } catch (Exception error) {
                    AiPlugin.logError("AI fix failed.", error);
                }
                return Status.OK_STATUS;
            }
        };
        job.setUser(true);
        job.schedule();
        return null;
    }

    private static void previewAndApply(ITextEditor editor, IDocument document, Target target, String replacement, long stamp) {
        if (editor == null || document == null) return;
        if (stamp != IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP && modificationStamp(document) != stamp) {
            MessageDialog.openInformation(editor.getSite().getShell(), "AI Fix", "The document changed while the fix was being generated, so the result was not applied.");
            return;
        }
        try {
            if (!document.get(target.offset(), target.length()).equals(target.text())) return;
        } catch (BadLocationException stale) {
            return;
        }

        String preview = "Current:\n" + clip(target.text()) + "\n\nAI replacement:\n" + clip(replacement)
            + "\n\nApply this replacement?";
        if (!MessageDialog.openQuestion(editor.getSite().getShell(), "AI Fix Preview", preview)) return;
        try {
            document.replace(target.offset(), target.length(), replacement);
            editor.selectAndReveal(target.offset(), replacement.length());
        } catch (BadLocationException error) {
            AiPlugin.logError("Could not apply AI fix.", error);
        }
    }

    private static Target target(IDocument document, ISelection selection) throws BadLocationException {
        if (selection instanceof ITextSelection text && text.getLength() > 0) {
            return new Target(text.getOffset(), text.getLength(), document.get(text.getOffset(), text.getLength()));
        }
        int offset = selection instanceof ITextSelection text ? text.getOffset() : 0;
        IRegion line = document.getLineInformationOfOffset(Math.max(0, Math.min(offset, document.getLength())));
        return new Target(line.getOffset(), line.getLength(), document.get(line.getOffset(), line.getLength()));
    }

    private static long modificationStamp(IDocument document) {
        return document instanceof IDocumentExtension4 extension ? extension.getModificationStamp() : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }

    private static String clip(String value) {
        if (value == null) return "";
        int max = 3500;
        return value.length() <= max ? value : value.substring(0, max) + "\n…";
    }

    private record Target(int offset, int length, String text) {}
}
