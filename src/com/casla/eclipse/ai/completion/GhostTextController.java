package com.casla.eclipse.ai.completion;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
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
import com.casla.eclipse.ai.completion.abap.AbapContextExtractor;
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/**
 * Copilot-style inline ghost text with multi-line support, word/line-by-word accept,
 * caching, and context awareness.
 *
 * Runs debounced completion on a background thread; results are painted in grey at the
 * caret.
 * - Tab: accepts full suggestion
 * - Ctrl+Right / Alt+]: accepts next word
 * - Ctrl+Down / Alt+Enter: accepts next line
 * - Esc: dismisses suggestion
 * - Typing characters matching ghost head consumes them locally without another request.
 *
 * Painting is plain StyledText overlay drawing, not a reflow-aware inline
 * annotation API, so it can only ever draw *on top of* whatever is already at
 * that screen position -- it cannot push real text out of the way. That
 * bounds what is safe to show: the first line only paints when nothing but
 * whitespace follows the cursor on that line (see requestSuggestion), and
 * subsequent lines only paint when the buffer lines they would cover are
 * themselves blank or don't exist yet (see paintableLineCount). When a
 * multi-line suggestion doesn't clear that bar, only the first line is drawn
 * plus a small "+N lines" marker -- Tab still inserts the full text either way.
 */
public final class GhostTextController implements IPartListener2, IDocumentListener {
    private static final GhostTextController INSTANCE = new GhostTextController();

    public record Ghost(int modelOffset, String text) {}

    /** Cancels its request as soon as a newer keystroke/ticket supersedes it. */
    private final class TicketMonitor extends NullProgressMonitor {
        private final long ticketAtStart;

        TicketMonitor(long ticketAtStart) {
            this.ticketAtStart = ticketAtStart;
        }

        @Override
        public boolean isCanceled() {
            return super.isCanceled() || ticket.get() != ticketAtStart;
        }
    }

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
                    scheduleFetch(50); // fast chain next suggestion after full typed accept
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
        scheduleFetch(new AiPreferences().completionSettings().debounceMillis());
    }

    private void scheduleFetch(int delay) {
        AiPreferences preferences = new AiPreferences();
        if (!preferences.completionSettings().automaticSuggestion()) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;

        long requestTicket = ticket.incrementAndGet();
        Display.getDefault().timerExec(delay, () -> {
            if (requestTicket == ticket.get()) requestSuggestion(requestTicket);
        });
    }

    /** Runs on the UI thread; captures the context, then leaves the thread. */
    private void requestSuggestion(long requestTicket) {
        if (widget == null || widget.isDisposed() || document == null || viewer == null) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;

        int caretModel = widgetToModel(widget.getCaretOffset());
        if (caretModel < 0 || !isEligibleCursorPosition(caretModel)) return;

        CompletionSettings settings = new AiPreferences().completionSettings();
        CodeContext context;
        try {
            context = extractContext(document, caretModel, settings);
        } catch (BadLocationException error) {
            return;
        }

        // 1. Check local completion cache for instantaneous hit. Shared with
        // AiAbapProposalsProvider's Ctrl+Space path via the same fingerprint
        // + shape key, so whichever path answers first serves the other too.
        String cacheKey = cacheKey(context, false);
        String cached = CompletionCache.get().get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            showGhost(new Ghost(caretModel, cached));
            return;
        }

        IDocument requestDocument = document;
        executor.execute(() -> {
            try {
                // Multi-line ghost completion. The monitor is ticket-bound, so
                // a superseded request (user kept typing) aborts mid-stream
                // instead of finishing and queuing behind the next one.
                CompletionResponse response =
                    AiRuntime.get().complete(context, new TicketMonitor(requestTicket), false);
                String insertion = new CompletionSanitizer().sanitize(response.content(), context);
                if (insertion.isBlank()) return;

                CompletionCache.get().put(cacheKey, insertion);
                Display.getDefault().asyncExec(() -> tryShowGhost(requestDocument, context, insertion));
            } catch (OperationCanceledException ignored) {
                // Superseded request.
            } catch (ApiException error) {
                logCompletionError("Inline AI completion failed", error);
            } catch (Exception error) {
                AiPlugin.logError("Inline AI completion failed.", error);
            }
        });
    }

    /**
     * Lets another completion source (the ABAP Ctrl+Space popup, when its
     * answer arrives after it already gave up waiting) hand its result over
     * to be shown as ghost text instead of being thrown away. Safe to call
     * from any thread.
     */
    public void offerCompletion(IDocument sourceDocument, CodeContext context, String insertion) {
        if (sourceDocument == null || context == null || insertion == null || insertion.isBlank()) return;
        Display.getDefault().asyncExec(() -> tryShowGhost(sourceDocument, context, insertion));
    }

    /** Must run on the UI thread. Shows the ghost only if nothing has moved on since context was captured. */
    private void tryShowGhost(IDocument requestDocument, CodeContext context, String insertion) {
        if (widget == null || widget.isDisposed() || document != requestDocument) return;
        if (modificationStamp(document) != context.modificationStamp()) return;
        int caretNow = widgetToModel(widget.getCaretOffset());
        if (caretNow != context.cursorOffset()) return;
        showGhost(new Ghost(context.cursorOffset(), insertion));
    }

    /** Key includes the completion shape so a single-line answer never gets served where a block was expected, or vice versa. */
    public static String cacheKey(CodeContext context, boolean singleLine) {
        return context.fingerprint() + (singleLine ? ":line" : ":block");
    }

    /** EMPTY_COMPLETION is a routine "model had nothing to add" outcome, not a fault -- logging it as an error is just noise. */
    private static void logCompletionError(String prefix, ApiException error) {
        if ("EMPTY_COMPLETION".equals(error.errorCode())) return;
        AiPlugin.logError(prefix + ": " + error.getMessage(), error);
    }

    private CodeContext extractContext(IDocument doc, int offset, CompletionSettings settings)
        throws BadLocationException {
        ICompilationUnit compilationUnit = null;
        if (editor != null && editor.getEditorInput() != null) {
            IJavaElement element = editor.getEditorInput().getAdapter(IJavaElement.class);
            if (element instanceof ICompilationUnit unit) {
                compilationUnit = unit;
            }
        }

        String label = editor == null || editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        if (compilationUnit != null || (label != null && label.endsWith(".java"))) {
            return new ContextExtractor().extract(compilationUnit, doc, offset, settings);
        }

        // ABAP via ADT or .abap file
        if (RelatedFileCollector.isAbapEditor(editor, label)) {
            return new AbapContextExtractor().extract(doc, offset, label, settings);
        }

        // Generic Text Fallback
        int beforeLine = doc.getLineOfOffset(Math.max(0, offset - settings.contextBefore()));
        int beforeStart = doc.getLineOffset(beforeLine);
        int afterLine = doc.getLineOfOffset(Math.min(doc.getLength(), offset + settings.contextAfter()));
        int afterEnd = doc.getLineOffset(afterLine) + doc.getLineLength(afterLine);
        CursorContextType contextType = ContextExtractor.detectCursorContext(doc.get(), offset, "Text");

        return new CodeContext(
            "",
            label,
            "Text",
            "",
            "",
            "",
            doc.get(beforeStart, offset - beforeStart),
            doc.get(offset, afterEnd - offset),
            offset,
            modificationStamp(doc),
            CodeContext.fingerprint(doc.get(), offset),
            contextType,
            java.util.List.of()
        );
    }

    // ------------------------------------------------------------------- keys

    private void verifyKey(VerifyEvent event) {
        Ghost current = ghost;
        if (current == null) return;

        // Tab: accept full suggestion
        if (event.character == SWT.TAB && event.stateMask == 0) {
            event.doit = false;
            accept(current);
            return;
        }

        // Esc: dismiss suggestion
        if (event.character == SWT.ESC) {
            event.doit = false;
            ticket.incrementAndGet();
            clearGhost();
            return;
        }

        // Ctrl+Right Arrow or Alt+]: accept next word
        boolean isCtrlRight = event.keyCode == SWT.ARROW_RIGHT && (event.stateMask & SWT.CTRL) != 0;
        boolean isAltBracket = event.character == ']' && (event.stateMask & SWT.ALT) != 0;
        if (isCtrlRight || isAltBracket) {
            event.doit = false;
            acceptWord(current);
            return;
        }

        // Ctrl+Down Arrow or Alt+Enter: accept next line
        boolean isCtrlDown = event.keyCode == SWT.ARROW_DOWN && (event.stateMask & SWT.CTRL) != 0;
        boolean isAltEnter = (event.character == SWT.CR || event.character == SWT.LF) && (event.stateMask & SWT.ALT) != 0;
        if (isCtrlDown || isAltEnter) {
            event.doit = false;
            acceptLine(current);
            return;
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
        widget.redraw();
        scheduleFetch(50); // fast prefetch next token
    }

    private void acceptWord(Ghost current) {
        String word = extractNextWord(current.text());
        if (!word.isEmpty()) {
            acceptPartial(current, word);
        }
    }

    private void acceptLine(Ghost current) {
        String line = extractNextLine(current.text());
        if (!line.isEmpty()) {
            acceptPartial(current, line);
        }
    }

    private void acceptPartial(Ghost current, String portion) {
        if (document == null || widget == null || widget.isDisposed()) return;
        accepting = true;
        try {
            document.replace(current.modelOffset(), 0, portion);
            int newModelOffset = current.modelOffset() + portion.length();
            int widgetEnd = modelToWidget(newModelOffset);
            if (widgetEnd >= 0) widget.setCaretOffset(widgetEnd);

            String remainder = current.text().substring(portion.length());
            if (remainder.isEmpty()) {
                ghost = null;
                widget.redraw();
                scheduleFetch(50);
            } else {
                moveGhost(new Ghost(newModelOffset, remainder));
            }
        } catch (BadLocationException stale) {
            clearGhost();
        } finally {
            accepting = false;
        }
    }

    public static String extractNextWord(String text) {
        if (text == null || text.isEmpty()) return "";
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        if (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
                i++;
            }
        } else if (i < text.length()) {
            while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && !Character.isJavaIdentifierPart(text.charAt(i))) {
                i++;
            }
        }
        return text.substring(0, Math.max(1, i));
    }

    public static String extractNextLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int idx = text.indexOf('\n');
        if (idx < 0) return text;
        return text.substring(0, idx + 1);
    }

    private void caretMoved(CaretEvent event) {
        Ghost current = ghost;
        if (current == null) return;
        if (widgetToModel(event.caretOffset) != current.modelOffset()) clearGhost();
    }

    // ---------------------------------------------------------------- drawing

    private void showGhost(Ghost next) {
        ghost = next;
        redrawGhostLines(next);
    }

    /** Advances the ghost after typed-prefix consumption. */
    private void moveGhost(Ghost next) {
        ghost = next;
        redrawGhostLines(next);
    }

    private void clearGhost() {
        Ghost previous = ghost;
        ghost = null;
        if (previous != null) redrawGhostLines(previous);
    }

    /**
     * Invalidates the caret line and all subsequent lines occupied by multi-line ghost text.
     */
    private void redrawGhostLines(Ghost target) {
        if (widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(target.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) {
            widget.redraw();
            return;
        }
        Point location = widget.getLocationAtOffset(widgetOffset);
        int lineHeight = widget.getLineHeight(widgetOffset);
        String[] lines = target.text().split("\r?\n", -1);
        int totalHeight = lineHeight * Math.max(1, lines.length);
        widget.redraw(0, location.y, widget.getClientArea().width, totalHeight, false);
    }

    private void paintGhost(PaintEvent event) {
        Ghost current = ghost;
        if (current == null || widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(current.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) return;

        Point location = widget.getLocationAtOffset(widgetOffset);
        event.gc.setForeground(widget.getDisplay().getSystemColor(SWT.COLOR_GRAY));
        event.gc.setFont(widget.getFont());

        String[] lines = current.text().split("\r?\n", -1);
        int lineHeight = widget.getLineHeight(widgetOffset);
        int paintable = paintableLineCount(current, lines);

        event.gc.drawString(lines[0], location.x, location.y, true);
        int leftMargin = widget.getLeftMargin();
        int lastLineY = location.y;
        for (int i = 1; i < paintable; i++) {
            lastLineY = location.y + lineHeight * i;
            event.gc.drawString(lines[i], leftMargin, lastLineY, true);
        }

        if (paintable < lines.length) {
            int remaining = lines.length - paintable;
            String marker = "  +" + remaining + (remaining == 1 ? " line (Tab)" : " lines (Tab)");
            boolean onFirstLine = paintable <= 1;
            int markerX = (onFirstLine ? location.x : leftMargin)
                + event.gc.textExtent(lines[paintable - 1]).x;
            event.gc.drawString(marker, markerX, lastLineY, true);
        }
    }

    /**
     * How many leading lines of a multi-line ghost are safe to paint over:
     * a plain overlay draw cannot push real text out of the way, so a line
     * only qualifies while the buffer line underneath it is blank or doesn't
     * exist yet. The remaining lines still insert in full on Tab -- only the
     * preview is truncated, with a "+N lines" marker standing in for them.
     */
    private int paintableLineCount(Ghost target, String[] lines) {
        if (lines.length <= 1 || document == null) return lines.length;
        try {
            int startLine = document.getLineOfOffset(target.modelOffset());
            int count = 1;
            for (int i = 1; i < lines.length; i++) {
                int lineIndex = startLine + i;
                if (lineIndex < document.getNumberOfLines() && !isBlankLine(lineIndex)) break;
                count++;
            }
            return count;
        } catch (BadLocationException error) {
            return 1;
        }
    }

    private boolean isBlankLine(int lineIndex) throws BadLocationException {
        IRegion info = document.getLineInformation(lineIndex);
        return document.get(info.getOffset(), info.getLength()).isBlank();
    }

    // ---------------------------------------------------------------- offsets

    /**
     * Automatic ghost text only fires when the rest of the current line is
     * blank. This is a hard requirement, not a heuristic: painting is a plain
     * overlay draw with no reflow, so anything already on the line past the
     * cursor would sit under the same pixels the ghost draws over -- garbled
     * text, not a missing feature. Ctrl+Space (AiAbapProposalsProvider /
     * AiCompletionProposalComputer) has no such restriction since it renders
     * through the platform's own completion popup instead of raw painting.
     */
    private boolean isEligibleCursorPosition(int modelOffset) {
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

    private static long modificationStamp(IDocument doc) {
        return doc instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }
}
