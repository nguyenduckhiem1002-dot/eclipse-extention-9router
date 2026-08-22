package com.casla.eclipse.ai.completion;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IDocumentExtension4;
import org.eclipse.jface.text.IDocumentListener;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CaretEvent;
import org.eclipse.swt.custom.CaretListener;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.custom.VerifyKeyListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.VerifyEvent;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.texteditor.ITextEditor;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.client.ApiException;
import com.casla.eclipse.ai.completion.abap.AbapStructureHint;
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/**
 * Copilot-style inline ghost text. After the configured debounce a completion
 * is requested on a background thread; the result is painted in grey at the
 * caret. Tab accepts it, Esc dismisses it, and typing characters that match
 * the head of the suggestion consumes them locally without another request.
 *
 * Everything here is plain platform API (StyledText painting, a prepended
 * VerifyKeyListener, document/caret listeners), so it works identically in
 * the ADT ABAP editor and the JDT Java editor, and the editor thread is never
 * blocked on network I/O.
 *
 * The ghost is only shown while the caret sits at the end of its line
 * (trailing whitespace ignored) and is limited to a single line. Both limits
 * exist so painting never has to shift or overdraw existing text: an earlier
 * multi-line version used StyledText.setLineVerticalIndent to open a gap,
 * which broke SWT's damage tracking and left stale glyphs painted over real
 * code. Single-line also cuts latency sharply, because the request can carry
 * a newline stop sequence instead of generating a whole block.
 */
public final class GhostTextController implements IPartListener2, IDocumentListener {
    private static final GhostTextController INSTANCE = new GhostTextController();

    private record Ghost(int modelOffset, String text) {}

    private final AtomicLong ticket = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-ghost-completion");
        thread.setDaemon(true);
        return thread;
    });

    private IWorkbenchWindow window;
    private ITextEditor editor;
    private IDocument document;
    private ITextViewer viewer;
    private StyledText widget;
    private volatile Ghost ghost;
    /** Set while accept() edits the document, so our own insert is ignored. */
    private boolean accepting;

    private final PaintListener painter = this::paintGhost;
    private final CaretListener caretListener = this::caretMoved;
    private final VerifyKeyListener keyListener = this::verifyKey;

    private GhostTextController() {}

    public static GhostTextController get() {
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

    // ------------------------------------------------------------------ parts

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

        IDocument nextDocument = textEditor.getDocumentProvider() == null
            ? null
            : textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
        if (nextDocument == null) return;

        ITextOperationTarget target = textEditor.getAdapter(ITextOperationTarget.class);
        if (!(target instanceof ITextViewer textViewer)) return;
        StyledText text = textViewer.getTextWidget();
        if (text == null || text.isDisposed()) return;

        editor = textEditor;
        document = nextDocument;
        viewer = textViewer;
        widget = text;
        document.addDocumentListener(this);
        widget.addPaintListener(painter);
        widget.addCaretListener(caretListener);
        if (viewer instanceof ITextViewerExtension extension) {
            extension.prependVerifyKeyListener(keyListener);
        } else {
            widget.addVerifyKeyListener(keyListener);
        }
    }

    private void detach() {
        ticket.incrementAndGet();
        clearGhost();
        if (document != null) document.removeDocumentListener(this);
        if (widget != null && !widget.isDisposed()) {
            widget.removePaintListener(painter);
            widget.removeCaretListener(caretListener);
            widget.removeVerifyKeyListener(keyListener);
        }
        if (viewer instanceof ITextViewerExtension extension) {
            extension.removeVerifyKeyListener(keyListener);
        }
        document = null;
        viewer = null;
        widget = null;
        editor = null;
    }

    // -------------------------------------------------------------- documents

    @Override
    public void documentAboutToBeChanged(DocumentEvent event) {
        // No pre-change work.
    }

    @Override
    public void documentChanged(DocumentEvent event) {
        if (accepting) return;
        Ghost current = ghost;
        if (current != null) {
            String inserted = event.getText();
            boolean plainInsertAtGhost = event.getLength() == 0
                && event.getOffset() == current.modelOffset()
                && inserted != null && !inserted.isEmpty();
            if (plainInsertAtGhost && current.text().startsWith(inserted)) {
                String rest = current.text().substring(inserted.length());
                if (rest.isEmpty()) {
                    clearGhost();
                    scheduleFetch(); // chain the next suggestion after a full accept
                } else {
                    moveGhost(new Ghost(current.modelOffset() + inserted.length(), rest));
                }
                return;
            }
            clearGhost();
        }
        scheduleFetch();
    }

    private void scheduleFetch() {
        AiPreferences preferences = new AiPreferences();
        if (!preferences.completionSettings().automaticSuggestion()) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;

        long requestTicket = ticket.incrementAndGet();
        int delay = preferences.completionSettings().debounceMillis();
        Display.getDefault().timerExec(delay, () -> {
            if (requestTicket == ticket.get()) requestSuggestion(requestTicket);
        });
    }

    /** Runs on the UI thread; captures the context, then leaves the thread. */
    private void requestSuggestion(long requestTicket) {
        if (widget == null || widget.isDisposed() || document == null || viewer == null) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;

        int caretModel = widgetToModel(widget.getCaretOffset());
        if (caretModel < 0 || !caretAtEndOfLine(caretModel)) return;

        CompletionSettings settings = new AiPreferences().completionSettings();
        CodeContext context;
        try {
            context = extractContext(document, caretModel, settings);
        } catch (BadLocationException error) {
            return;
        }

        IDocument requestDocument = document;
        executor.execute(() -> {
            try {
                CompletionResponse response =
                    AiRuntime.get().complete(context, new NullProgressMonitor(), true);
                String insertion = firstLine(new CompletionSanitizer().sanitize(response.content(), context));
                if (insertion.isBlank()) return;
                Display.getDefault().asyncExec(() -> {
                    if (requestTicket != ticket.get()) return;
                    if (widget == null || widget.isDisposed() || document != requestDocument) return;
                    if (modificationStamp(document) != context.modificationStamp()) return;
                    int caretNow = widgetToModel(widget.getCaretOffset());
                    if (caretNow != context.cursorOffset()) return;
                    showGhost(new Ghost(context.cursorOffset(), insertion));
                });
            } catch (OperationCanceledException ignored) {
                // Superseded request.
            } catch (ApiException error) {
                AiPlugin.logError("Inline AI completion failed: " + error.getMessage(), error);
            } catch (Exception error) {
                AiPlugin.logError("Inline AI completion failed.", error);
            }
        });
    }

    private CodeContext extractContext(IDocument doc, int offset, CompletionSettings settings)
        throws BadLocationException {
        int beforeLine = doc.getLineOfOffset(Math.max(0, offset - settings.contextBefore()));
        int beforeStart = doc.getLineOffset(beforeLine);
        int afterLine = doc.getLineOfOffset(Math.min(doc.getLength(), offset + settings.contextAfter()));
        int afterEnd = doc.getLineOffset(afterLine) + doc.getLineLength(afterLine);

        String label = editor == null || editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        String language = label.endsWith(".java") ? "Java" : "ABAP";
        String structureHint = language.equals("ABAP") ? AbapStructureHint.scan(doc, offset) : "";
        return new CodeContext(
            "",
            label,
            language,
            "",
            "",
            structureHint,
            doc.get(beforeStart, offset - beforeStart),
            doc.get(offset, afterEnd - offset),
            offset,
            modificationStamp(doc),
            ""
        );
    }

    // ------------------------------------------------------------------- keys

    private void verifyKey(VerifyEvent event) {
        Ghost current = ghost;
        if (current == null) return;
        if (event.character == SWT.TAB && event.stateMask == 0) {
            event.doit = false;
            accept(current);
        } else if (event.character == SWT.ESC) {
            event.doit = false;
            ticket.incrementAndGet();
            clearGhost();
        }
    }

    private void accept(Ghost current) {
        if (document == null || widget == null || widget.isDisposed()) return;
        accepting = true;
        try {
            document.replace(current.modelOffset(), 0, current.text());
            int widgetEnd = modelToWidget(current.modelOffset() + current.text().length());
            if (widgetEnd >= 0) widget.setCaretOffset(widgetEnd);
        } catch (BadLocationException stale) {
            // Document moved on; drop the suggestion silently.
        } finally {
            ghost = null;
            accepting = false;
        }
        // One redraw for the whole accept, then queue the follow-up so Tab can
        // be pressed repeatedly.
        widget.redraw();
        scheduleFetch();
    }

    private void caretMoved(CaretEvent event) {
        Ghost current = ghost;
        if (current == null) return;
        if (widgetToModel(event.caretOffset) != current.modelOffset()) clearGhost();
    }

    // ---------------------------------------------------------------- drawing

    private void showGhost(Ghost next) {
        ghost = next;
        redrawGhostLine(next);
    }

    /** Advances the ghost after typed-prefix consumption. */
    private void moveGhost(Ghost next) {
        ghost = next;
        redrawGhostLine(next);
    }

    private void clearGhost() {
        Ghost previous = ghost;
        ghost = null;
        if (previous != null) redrawGhostLine(previous);
    }

    /**
     * Invalidates the caret line out to the right edge. Ghost glyphs are drawn
     * transparently on top of the editor's own presentation, so the line has to
     * be explicitly repainted or they stay on screen after the ghost is gone.
     */
    private void redrawGhostLine(Ghost target) {
        if (widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(target.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) {
            widget.redraw();
            return;
        }
        Point location = widget.getLocationAtOffset(widgetOffset);
        int lineHeight = widget.getLineHeight(widgetOffset);
        widget.redraw(0, location.y, widget.getClientArea().width, lineHeight, false);
    }

    private void paintGhost(PaintEvent event) {
        Ghost current = ghost;
        if (current == null || widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(current.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) return;

        Point location = widget.getLocationAtOffset(widgetOffset);
        event.gc.setForeground(widget.getDisplay().getSystemColor(SWT.COLOR_GRAY));
        event.gc.setFont(widget.getFont());
        event.gc.drawString(current.text(), location.x, location.y, true);
    }

    // ---------------------------------------------------------------- offsets

    private boolean caretAtEndOfLine(int modelOffset) {
        try {
            int line = document.getLineOfOffset(modelOffset);
            IRegion info = document.getLineInformation(line);
            String tail = document.get(modelOffset, info.getOffset() + info.getLength() - modelOffset);
            return tail.isBlank();
        } catch (BadLocationException error) {
            return false;
        }
    }

    private int modelToWidget(int modelOffset) {
        if (viewer instanceof ITextViewerExtension5 extension) {
            return extension.modelOffset2WidgetOffset(modelOffset);
        }
        IRegion visible = viewer.getVisibleRegion();
        int widgetOffset = modelOffset - visible.getOffset();
        return widgetOffset < 0 || widgetOffset > widget.getCharCount() ? -1 : widgetOffset;
    }

    private int widgetToModel(int widgetOffset) {
        if (viewer instanceof ITextViewerExtension5 extension) {
            return extension.widgetOffset2ModelOffset(widgetOffset);
        }
        return widgetOffset + viewer.getVisibleRegion().getOffset();
    }

    private static String firstLine(String text) {
        int newLine = text.indexOf('\n');
        return newLine < 0 ? text : text.substring(0, newLine);
    }

    private static long modificationStamp(IDocument doc) {
        return doc instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }
}
