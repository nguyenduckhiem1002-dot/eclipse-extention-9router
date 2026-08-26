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
import org.eclipse.jface.text.IViewportListener;
import org.eclipse.jface.text.contentassist.ContentAssistEvent;
import org.eclipse.jface.text.contentassist.ICompletionListener;
import org.eclipse.jface.text.contentassist.ICompletionProposal;
import org.eclipse.jface.text.source.ContentAssistantFacade;
import org.eclipse.jface.text.source.ISourceViewerExtension4;
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
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.completion.abap.AbapContextExtractor;
import com.casla.eclipse.ai.completion.abap.AbapLocalCompleter;
import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/**
 * Copilot-style inline ghost text with full multi-line preview, word/line
 * acceptance, caching, and context awareness.
 *
 * Single-line suggestions are painted inline. Multi-line suggestions keep the
 * first line inline and render the complete block in GhostPreviewControl,
 * which floats over the editor without changing StyledText layout.
 */
public final class GhostTextController implements IPartListener2, IDocumentListener {
    private static final GhostTextController INSTANCE = new GhostTextController();

    public record Ghost(int modelOffset, String text) {}

    private final class TicketMonitor extends NullProgressMonitor {
        private final long ticketAtStart;
        TicketMonitor(long ticketAtStart) { this.ticketAtStart = ticketAtStart; }
        @Override public boolean isCanceled() { return super.isCanceled() || ticket.get() != ticketAtStart; }
    }

    private final AtomicLong ticket = new AtomicLong();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-ghost-completion");
        thread.setDaemon(true);
        return thread;
    });
    private final GhostPreviewControl preview = new GhostPreviewControl();

    private IWorkbenchWindow window;
    private ITextEditor editor;
    private IDocument document;
    private ITextViewer viewer;
    private StyledText widget;
    private volatile Ghost ghost;
    private boolean accepting;
    private volatile boolean popupActive;
    private ContentAssistantFacade assistFacade;

    private final PaintListener painter = this::paintGhost;
    private final CaretListener caretListener = this::caretMoved;
    private final VerifyKeyListener keyListener = this::verifyKey;
    private final IViewportListener viewportListener = verticalPosition -> refreshPreview();
    private final ICompletionListener completionListener = new ICompletionListener() {
        @Override
        public void assistSessionStarted(ContentAssistEvent event) {
            popupActive = true;
            ticket.incrementAndGet();
            clearGhost();
        }
        @Override public void assistSessionEnded(ContentAssistEvent event) { popupActive = false; }
        @Override public void selectionChanged(ICompletionProposal proposal, boolean smart) {}
    };

    private GhostTextController() {}
    public static GhostTextController get() { return INSTANCE; }

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
    @Override public void partClosed(IWorkbenchPartReference partRef) { if (partRef.getPart(false) == editor) detach(); }
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
        viewer.addViewportListener(viewportListener);
        if (viewer instanceof ITextViewerExtension extension) extension.prependVerifyKeyListener(keyListener);
        else widget.addVerifyKeyListener(keyListener);
        if (viewer instanceof ISourceViewerExtension4 sourceViewerExtension) {
            assistFacade = sourceViewerExtension.getContentAssistantFacade();
            if (assistFacade != null) assistFacade.addCompletionListener(completionListener);
        }
    }

    private void detach() {
        ticket.incrementAndGet();
        clearGhost();
        if (document != null) document.removeDocumentListener(this);
        if (viewer != null) viewer.removeViewportListener(viewportListener);
        if (widget != null && !widget.isDisposed()) {
            widget.removePaintListener(painter);
            widget.removeCaretListener(caretListener);
            widget.removeVerifyKeyListener(keyListener);
        }
        if (viewer instanceof ITextViewerExtension extension) extension.removeVerifyKeyListener(keyListener);
        if (assistFacade != null) {
            assistFacade.removeCompletionListener(completionListener);
            assistFacade = null;
        }
        preview.dispose();
        popupActive = false;
        document = null;
        viewer = null;
        widget = null;
        editor = null;
    }

    @Override public void documentAboutToBeChanged(DocumentEvent event) {}

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
                    scheduleFetch(50);
                } else {
                    moveGhost(new Ghost(current.modelOffset() + inserted.length(), rest));
                }
                return;
            }
            clearGhost();
        }

        if (TriggerEngine.isDeletion(event) || popupActive) return;
        int editEnd = event.getOffset() + (event.getText() == null ? 0 : event.getText().length());
        String fullText = document.get();
        CursorContextType contextType = ContextExtractor.detectCursorContext(fullText, editEnd, currentLanguage());
        if (TriggerEngine.blocksAutoTrigger(contextType)) return;
        int configured = new AiPreferences().completionSettings().debounceMillis();
        int delay = TriggerEngine.debounceMillis(event, lineTextUpTo(fullText, editEnd), configured);
        scheduleFetch(delay);
    }

    private void scheduleFetch() { scheduleFetch(new AiPreferences().completionSettings().debounceMillis()); }

    private void scheduleFetch(int delay) {
        AiPreferences preferences = new AiPreferences();
        if (!preferences.completionSettings().automaticSuggestion()) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;
        long requestTicket = ticket.incrementAndGet();
        Display.getDefault().timerExec(delay, () -> { if (requestTicket == ticket.get()) requestSuggestion(requestTicket); });
    }

    private String currentLanguage() {
        String label = editor == null || editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        if (label != null && label.endsWith(".java")) return "Java";
        if (editor != null && editor.getEditorInput() != null
            && editor.getEditorInput().getAdapter(ICompilationUnit.class) != null) return "Java";
        return RelatedFileCollector.isAbapEditor(editor, label) ? "ABAP" : "Text";
    }

    private static String lineTextUpTo(String fullText, int offset) {
        int safeOffset = Math.max(0, Math.min(offset, fullText.length()));
        int start = fullText.lastIndexOf('\n', Math.max(0, safeOffset - 1)) + 1;
        return fullText.substring(start, safeOffset);
    }

    private void requestSuggestion(long requestTicket) {
        if (widget == null || widget.isDisposed() || document == null || viewer == null) return;
        if (!AiRuntime.get().snapshot().canComplete()) return;

        int caretModel = widgetToModel(widget.getCaretOffset());
        if (caretModel < 0 || !isEligibleCursorPosition(caretModel)) return;

        if ("ABAP".equals(currentLanguage())) {
            String local = AbapLocalCompleter.suggest(document, caretModel);
            if (!local.isBlank()) {
                showGhost(new Ghost(caretModel, local));
                return;
            }
        }

        CompletionSettings settings = new AiPreferences().completionSettings();
        CodeContext context;
        try { context = extractContext(document, caretModel, settings); }
        catch (BadLocationException error) { return; }

        String cacheKey = cacheKey(context, false);
        String cached = CompletionCache.get().get(cacheKey);
        if (cached != null && !cached.isBlank()) {
            showGhost(new Ghost(caretModel, cached));
            return;
        }

        IDocument requestDocument = document;
        executor.execute(() -> {
            try {
                CompletionResponse response = AiRuntime.get().complete(context, new TicketMonitor(requestTicket), false);
                String insertion = response.content();
                if (insertion.isBlank()) return;
                CompletionCache.get().put(cacheKey, insertion);
                Display.getDefault().asyncExec(() -> tryShowGhost(requestDocument, context, insertion));
            } catch (OperationCanceledException ignored) {
            } catch (ApiException error) {
                logCompletionError("Inline AI completion failed", error);
            } catch (Exception error) {
                AiPlugin.logError("Inline AI completion failed.", error);
            }
        });
    }

    public void offerCompletion(IDocument sourceDocument, CodeContext context, String insertion) {
        if (sourceDocument == null || context == null || insertion == null || insertion.isBlank()) return;
        Display.getDefault().asyncExec(() -> tryShowGhost(sourceDocument, context, insertion));
    }

    private void tryShowGhost(IDocument requestDocument, CodeContext context, String insertion) {
        if (widget == null || widget.isDisposed() || document != requestDocument) return;
        if (modificationStamp(document) != context.modificationStamp()) return;
        int caretNow = widgetToModel(widget.getCaretOffset());
        if (caretNow != context.cursorOffset()) return;
        showGhost(new Ghost(context.cursorOffset(), insertion));
    }

    public static String cacheKey(CodeContext context, boolean singleLine) {
        return context.fingerprint() + (singleLine ? ":line" : ":block");
    }

    private static void logCompletionError(String prefix, ApiException error) {
        if ("EMPTY_COMPLETION".equals(error.errorCode())) return;
        AiPlugin.logError(prefix + ": " + error.getMessage(), error);
    }

    private CodeContext extractContext(IDocument doc, int offset, CompletionSettings settings) throws BadLocationException {
        ICompilationUnit compilationUnit = null;
        if (editor != null && editor.getEditorInput() != null) {
            IJavaElement element = editor.getEditorInput().getAdapter(IJavaElement.class);
            if (element instanceof ICompilationUnit unit) compilationUnit = unit;
        }

        String label = editor == null || editor.getEditorInput() == null ? "" : editor.getEditorInput().getName();
        if (compilationUnit != null || (label != null && label.endsWith(".java"))) {
            return new ContextExtractor().extract(compilationUnit, doc, offset, settings);
        }
        if (RelatedFileCollector.isAbapEditor(editor, label)) {
            return new AbapContextExtractor().extract(doc, offset, label, settings);
        }

        int beforeLine = doc.getLineOfOffset(Math.max(0, offset - settings.contextBefore()));
        int beforeStart = doc.getLineOffset(beforeLine);
        int afterLine = doc.getLineOfOffset(Math.min(doc.getLength(), offset + settings.contextAfter()));
        int afterEnd = doc.getLineOffset(afterLine) + doc.getLineLength(afterLine);
        CursorContextType contextType = ContextExtractor.detectCursorContext(doc.get(), offset, "Text");
        return new CodeContext(
            "", label, "Text", "", "", "",
            doc.get(beforeStart, offset - beforeStart),
            doc.get(offset, afterEnd - offset),
            offset,
            modificationStamp(doc),
            CodeContext.fingerprint(doc.get(), offset),
            contextType,
            java.util.List.of()
        );
    }

    private void verifyKey(VerifyEvent event) {
        if (popupActive) return;
        Ghost current = ghost;
        if (current == null) return;

        if (event.character == SWT.TAB && event.stateMask == 0) {
            event.doit = false;
            accept(current);
            return;
        }
        if (event.character == SWT.ESC) {
            event.doit = false;
            ticket.incrementAndGet();
            clearGhost();
            return;
        }
        boolean isCtrlRight = event.keyCode == SWT.ARROW_RIGHT && (event.stateMask & SWT.CTRL) != 0;
        boolean isAltBracket = event.character == ']' && (event.stateMask & SWT.ALT) != 0;
        if (isCtrlRight || isAltBracket) {
            event.doit = false;
            acceptWord(current);
            return;
        }
        boolean isCtrlDown = event.keyCode == SWT.ARROW_DOWN && (event.stateMask & SWT.CTRL) != 0;
        boolean isAltEnter = (event.character == SWT.CR || event.character == SWT.LF) && (event.stateMask & SWT.ALT) != 0;
        if (isCtrlDown || isAltEnter) {
            event.doit = false;
            acceptLine(current);
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
        } finally {
            ghost = null;
            preview.hide();
            accepting = false;
        }
        widget.redraw();
        scheduleFetch(50);
    }

    private void acceptWord(Ghost current) {
        String word = extractNextWord(current.text());
        if (!word.isEmpty()) acceptPartial(current, word);
    }

    private void acceptLine(Ghost current) {
        String line = extractNextLine(current.text());
        if (!line.isEmpty()) acceptPartial(current, line);
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
                preview.hide();
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
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
        } else if (i < text.length()) {
            while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && !Character.isJavaIdentifierPart(text.charAt(i))) i++;
        }
        return text.substring(0, Math.max(1, i));
    }

    public static String extractNextLine(String text) {
        if (text == null || text.isEmpty()) return "";
        int idx = text.indexOf('\n');
        return idx < 0 ? text : text.substring(0, idx + 1);
    }

    private void caretMoved(CaretEvent event) {
        Ghost current = ghost;
        if (current == null) return;
        if (widgetToModel(event.caretOffset) != current.modelOffset()) clearGhost();
        else refreshPreview();
    }

    private void showGhost(Ghost next) {
        ghost = next;
        redrawGhostLines(next);
        refreshPreview();
    }

    private void moveGhost(Ghost next) {
        ghost = next;
        redrawGhostLines(next);
        refreshPreview();
    }

    private void clearGhost() {
        Ghost previous = ghost;
        ghost = null;
        preview.hide();
        if (previous != null) redrawGhostLines(previous);
    }

    private void refreshPreview() {
        Ghost current = ghost;
        if (current == null || widget == null || widget.isDisposed() || !current.text().contains("\n")) {
            preview.hide();
            return;
        }
        int widgetOffset = modelToWidget(current.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) {
            preview.hide();
            return;
        }
        preview.show(widget, widgetOffset, current.text());
    }

    private void redrawGhostLines(Ghost target) {
        if (widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(target.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) {
            widget.redraw();
            return;
        }
        Point location = widget.getLocationAtOffset(widgetOffset);
        int lineHeight = widget.getLineHeight(widgetOffset);
        int lineCount = Math.max(1, target.text().split("\r?\n", -1).length);
        widget.redraw(0, location.y, widget.getClientArea().width, lineHeight * lineCount, false);
    }

    private void paintGhost(PaintEvent event) {
        Ghost current = ghost;
        if (current == null || widget == null || widget.isDisposed()) return;
        int widgetOffset = modelToWidget(current.modelOffset());
        if (widgetOffset < 0 || widgetOffset > widget.getCharCount()) return;
        Point location = widget.getLocationAtOffset(widgetOffset);
        event.gc.setForeground(widget.getDisplay().getSystemColor(SWT.COLOR_GRAY));
        event.gc.setFont(widget.getFont());
        String firstLine = current.text().split("\r?\n", -1)[0];
        event.gc.drawString(firstLine, location.x, location.y, true);
    }

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
        if (viewer instanceof ITextViewerExtension5 extension) return extension.modelOffset2WidgetOffset(modelOffset);
        IRegion visible = viewer.getVisibleRegion();
        int widgetOffset = modelOffset - visible.getOffset();
        return widgetOffset < 0 || widgetOffset > widget.getCharCount() ? -1 : widgetOffset;
    }

    private int widgetToModel(int widgetOffset) {
        if (viewer instanceof ITextViewerExtension5 extension) return extension.widgetOffset2ModelOffset(widgetOffset);
        return widgetOffset + viewer.getVisibleRegion().getOffset();
    }

    private static long modificationStamp(IDocument doc) {
        return doc instanceof IDocumentExtension4 extension
            ? extension.getModificationStamp()
            : IDocumentExtension4.UNKNOWN_MODIFICATION_STAMP;
    }
}
