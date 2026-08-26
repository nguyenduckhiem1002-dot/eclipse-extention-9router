package com.casla.eclipse.ai.codeaction;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.learning.AdaptiveLearningStore;
import com.casla.eclipse.ai.learning.EditHistoryTracker;

/** Applies the current same-file repeated-edit suggestion after confirmation. */
public final class ApplyNextEditHandler extends AbstractHandler {
    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        IEditorPart active = HandlerUtil.getActiveEditor(event);
        ITextEditor editor = active == null ? null : active.getAdapter(ITextEditor.class);
        if (editor == null || editor.getDocumentProvider() == null) return null;
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (document == null) return null;
        if (!AdaptiveLearningStore.get().shouldTrackNextEdits()) {
            MessageDialog.openInformation(editor.getSite().getShell(), "AI Next Edit", "AI Next Edit is disabled or adaptive learning is paused. Enable it in AI Code Assistant → Adaptive Learning.");
            return null;
        }

        EditHistoryTracker tracker = EditHistoryTracker.get();
        EditHistoryTracker.NextEditSuggestion suggestion = tracker.suggestion();
        if (suggestion == null) {
            MessageDialog.openInformation(editor.getSite().getShell(), "AI Next Edit", "No repeated edit is currently confident enough to suggest.");
            return null;
        }
        try {
            if (suggestion.offset() < 0 || suggestion.offset() + suggestion.length() > document.getLength()) {
                tracker.clearSuggestion();
                return null;
            }
            String actual = document.get(suggestion.offset(), suggestion.length());
            if (!actual.equals(suggestion.before())) {
                tracker.clearSuggestion();
                MessageDialog.openInformation(editor.getSite().getShell(), "AI Next Edit", "The source changed and the pending suggestion is no longer valid.");
                return null;
            }
            String message = "Repeat the detected edit?\n\n" + suggestion.before() + "\n→\n" + suggestion.replacement();
            if (!MessageDialog.openQuestion(editor.getSite().getShell(), "AI Next Edit", message)) return null;
            tracker.suppressNextChange();
            document.replace(suggestion.offset(), suggestion.length(), suggestion.replacement());
            editor.selectAndReveal(suggestion.offset(), suggestion.replacement().length());
            tracker.clearSuggestion();
            editor.getEditorSite().getActionBars().getStatusLineManager().setMessage("AI next edit applied.");
        } catch (BadLocationException error) {
            AiPlugin.logError("Could not apply next edit suggestion.", error);
            tracker.clearSuggestion();
        }
        return null;
    }
}
