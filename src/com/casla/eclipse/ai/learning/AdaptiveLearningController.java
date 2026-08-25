package com.casla.eclipse.ai.learning;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.completion.RelatedFileCollector;

/**
 * Watches the active editor and periodically learns from the stable document
 * state after typing settles. The heavy-ish regex analysis runs off the UI
 * thread and only aggregate metrics are persisted.
 */
public final class AdaptiveLearningController implements IPartListener2, IDocumentListener {
    private static final AdaptiveLearningController INSTANCE = new AdaptiveLearningController();
    private static final int LEARN_DEBOUNCE_MS = 900;

    private final AtomicLong ticket = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-adaptive-learning");
        thread.setDaemon(true);
        return thread;
    });

    private IWorkbenchWindow window;
    private ITextEditor editor;
    private IDocument document;
    private String objectKey = "";
    private String language = "Text";

    private AdaptiveLearningController() {}

    public static AdaptiveLearningController get() {
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
        ticket.incrementAndGet();
        detach();
        if (window != null) window.getPartService().removePartListener(this);
        window = null;
        executor.shutdownNow();
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

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {}

    @Override
    public void documentChanged(DocumentEvent event) {
        scheduleLearning();
    }

    private void attach(IEditorPart candidate) {
        ITextEditor textEditor = candidate == null ? null : candidate.getAdapter(ITextEditor.class);
        if (textEditor == editor) return;
        detach();
        if (textEditor == null || textEditor.getDocumentProvider() == null) return;

        IDocument nextDocument = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (nextDocument == null) return;

        editor = textEditor;
        document = nextDocument;
        objectKey = textEditor.getEditorInput() == null ? "" : textEditor.getEditorInput().getName();
        language = RelatedFileCollector.isAbapEditor(textEditor, objectKey) ? "ABAP" : "Text";
        document.addDocumentListener(this);

        if ("ABAP".equals(language)) scheduleLearning();
    }

    private void detach() {
        ticket.incrementAndGet();
        if (document != null) document.removeDocumentListener(this);
        editor = null;
        document = null;
        objectKey = "";
        language = "Text";
    }

    private void scheduleLearning() {
        if (!"ABAP".equals(language) || document == null) return;
        long requestTicket = ticket.incrementAndGet();
        Display.getDefault().timerExec(LEARN_DEBOUNCE_MS, () -> captureStableDocument(requestTicket));
    }

    private void captureStableDocument(long requestTicket) {
        if (requestTicket != ticket.get() || document == null || !"ABAP".equals(language)) return;
        String source = document.get();
        String key = objectKey;
        String currentLanguage = language;
        executor.execute(() -> AdaptiveLearningStore.get().observeDocument(key, currentLanguage, source));
    }
}
