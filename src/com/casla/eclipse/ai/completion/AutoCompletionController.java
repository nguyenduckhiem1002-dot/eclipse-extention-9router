package com.casla.eclipse.ai.completion;

import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/**
 * Optional, conservative auto-activation. It opens the standard Eclipse
 * content-assist popup after selected trigger characters; it does not use JDT
 * internal APIs or draw unstable ghost-text overlays.
 */
public final class AutoCompletionController implements IPartListener2, IDocumentListener {
    private static final AutoCompletionController INSTANCE = new AutoCompletionController();

    private final AtomicLong sequence = new AtomicLong();
    private IWorkbenchWindow window;
    private ITextEditor editor;
    private IDocument document;

    private AutoCompletionController() {}

    public static AutoCompletionController get() {
        return INSTANCE;
    }

    public void start() {
        if (!PlatformUI.isWorkbenchRunning()) return;
        window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        if (window == null) return;
        window.getPartService().addPartListener(this);
        IEditorPart active = window.getActivePage() == null ? null : window.getActivePage().getActiveEditor();
        attach(active);
    }

    public void stop() {
        sequence.incrementAndGet();
        detach();
        if (window != null) window.getPartService().removePartListener(this);
        window = null;
    }

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        // No pre-change work.
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        AiPreferences preferences = new AiPreferences();
        if (!preferences.completionSettings().automaticSuggestion()) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;
        if (!isTrigger(event.getText())) return;

        long ticket = sequence.incrementAndGet();
        int delay = preferences.completionSettings().debounceMillis();
        Display.getDefault().timerExec(delay, () -> {
            if (ticket != sequence.get() || editor == null || document == null) return;
            if (!AiRuntime.get().snapshot().canComplete()) return;
            ITextOperationTarget target = editor.getAdapter(ITextOperationTarget.class);
            if (target != null && target.canDoOperation(ISourceViewer.CONTENTASSIST_PROPOSALS)) {
                target.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
            }
        });
    }

    @Override
    public void partActivated(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) instanceof IEditorPart activeEditor) attach(activeEditor);
    }

    @Override
    public void partClosed(IWorkbenchPartReference partRef) {
        if (partRef.getPart(false) == editor) detach();
    }

    @Override public void partBroughtToTop(IWorkbenchPartReference partRef) {}
    @Override public void partDeactivated(IWorkbenchPartReference partRef) {}
    @Override public void partOpened(IWorkbenchPartReference partRef) {}
    @Override public void partHidden(IWorkbenchPartReference partRef) {}
    @Override public void partVisible(IWorkbenchPartReference partRef) {}
    @Override public void partInputChanged(IWorkbenchPartReference partRef) {}

    private void attach(IEditorPart candidate) {
        ITextEditor textEditor = candidate == null ? null : candidate.getAdapter(ITextEditor.class);
        if (textEditor == editor) return;
        detach();
        if (textEditor == null) return;
        IDocument nextDocument = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (nextDocument == null) return;
        editor = textEditor;
        document = nextDocument;
        document.addDocumentListener(this);
    }

    private void detach() {
        sequence.incrementAndGet();
        if (document != null) document.removeDocumentListener(this);
        document = null;
        editor = null;
    }

    private static boolean isTrigger(String insertedText) {
        if (insertedText == null || insertedText.isEmpty() || insertedText.length() > 8) return false;
        char last = insertedText.charAt(insertedText.length() - 1);
        // Debounce ordinary typing too: waiting for only punctuation made
        // automatic mode appear inactive during normal identifier entry.
        // Whitespace-only edits are ignored to avoid noisy requests while
        // the user is formatting or navigating.
        return !Character.isWhitespace(last);
    }
}
