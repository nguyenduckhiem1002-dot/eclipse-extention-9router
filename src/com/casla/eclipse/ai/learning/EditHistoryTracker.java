package com.casla.eclipse.ai.learning;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.Consumer;

import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.DocumentEvent;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.swt.widgets.Display;

/**
 * Local-only, same-file next-edit detector. It learns only manual line edits,
 * coalesces rapid keystrokes, and requires two recent transactions with the
 * same textual replacement before proposing a third occurrence.
 */
public final class EditHistoryTracker {
    public record NextEditSuggestion(String objectKey, int offset, int length, String before, String replacement) {}

    private static final EditHistoryTracker INSTANCE = new EditHistoryTracker();
    private static final int COALESCE_MS = 700;
    private static final long HISTORY_TTL_MS = 5 * 60_000L;
    private static final int MAX_HISTORY = 20;

    private record BeforeChange(String objectKey, int lineOffset, String lineText) {}
    private record Transaction(String objectKey, int lineOffset, String before, String after, long timestamp) {}
    private record Delta(String removed, String inserted) {}

    private final Deque<Transaction> history = new ArrayDeque<>();
    private BeforeChange beforeChange;
    private Transaction current;
    private long ticket;
    private NextEditSuggestion suggestion;
    private Consumer<NextEditSuggestion> listener;
    private boolean suppressNextChange;

    private EditHistoryTracker() {}
    public static EditHistoryTracker get() { return INSTANCE; }

    public synchronized void setSuggestionListener(Consumer<NextEditSuggestion> value) { listener = value; }
    public synchronized NextEditSuggestion suggestion() { return suggestion; }
    public synchronized void clearSuggestion() { suggestion = null; }
    public synchronized void suppressNextChange() { suppressNextChange = true; }

    public synchronized void documentAboutToBeChanged(String objectKey, String language, IDocument document, DocumentEvent event) {
        if (!"ABAP".equalsIgnoreCase(language) || document == null || event == null) {
            beforeChange = null;
            return;
        }
        try {
            int safe = Math.max(0, Math.min(event.getOffset(), document.getLength()));
            IRegion line = document.getLineInformationOfOffset(safe);
            beforeChange = new BeforeChange(clean(objectKey), line.getOffset(), document.get(line.getOffset(), line.getLength()));
        } catch (BadLocationException error) {
            beforeChange = null;
        }
    }

    public synchronized void documentChanged(
        String objectKey,
        String language,
        IDocument document,
        DocumentEvent event,
        boolean aiCompletionMutation
    ) {
        if (suppressNextChange) {
            suppressNextChange = false;
            beforeChange = null;
            return;
        }
        if (aiCompletionMutation || !"ABAP".equalsIgnoreCase(language) || document == null || event == null) {
            beforeChange = null;
            return;
        }
        BeforeChange before = beforeChange;
        beforeChange = null;
        if (before == null || !sameObject(before.objectKey(), clean(objectKey))) return;
        try {
            int safe = Math.max(0, Math.min(event.getOffset(), document.getLength()));
            IRegion line = document.getLineInformationOfOffset(safe);
            String afterLine = document.get(line.getOffset(), line.getLength());
            if (Objects.equals(before.lineText(), afterLine)) return;

            long now = System.currentTimeMillis();
            if (current != null
                && sameObject(current.objectKey(), before.objectKey())
                && current.lineOffset() == before.lineOffset()
                && now - current.timestamp() <= COALESCE_MS) {
                current = new Transaction(current.objectKey(), current.lineOffset(), current.before(), afterLine, now);
            } else {
                finalizeCurrent(document);
                current = new Transaction(before.objectKey(), before.lineOffset(), before.lineText(), afterLine, now);
            }
            long requestTicket = ++ticket;
            Display.getDefault().timerExec(COALESCE_MS, () -> flushIfCurrent(requestTicket, document));
        } catch (BadLocationException ignored) {
        }
    }

    public synchronized void resetTransient() {
        ticket++;
        beforeChange = null;
        current = null;
        suggestion = null;
    }

    private void flushIfCurrent(long requestTicket, IDocument document) {
        synchronized (this) {
            if (requestTicket != ticket) return;
            finalizeCurrent(document);
            current = null;
        }
    }

    private void finalizeCurrent(IDocument document) {
        Transaction transaction = current;
        if (transaction == null || transaction.before().equals(transaction.after())) return;
        Delta delta = delta(transaction.before(), transaction.after());
        if (delta == null || delta.removed().isBlank() || delta.inserted().equals(delta.removed())) return;

        long now = System.currentTimeMillis();
        while (!history.isEmpty() && now - history.peekFirst().timestamp() > HISTORY_TTL_MS) history.removeFirst();
        history.addLast(transaction);
        while (history.size() > MAX_HISTORY) history.removeFirst();
        detectRepeatedEdit(document, transaction, delta);
    }

    private void detectRepeatedEdit(IDocument document, Transaction latest, Delta latestDelta) {
        Transaction previousMatch = null;
        var iterator = history.descendingIterator();
        if (iterator.hasNext()) iterator.next(); // latest itself
        while (iterator.hasNext()) {
            Transaction candidate = iterator.next();
            if (!sameObject(candidate.objectKey(), latest.objectKey())) continue;
            Delta candidateDelta = delta(candidate.before(), candidate.after());
            if (candidateDelta != null
                && candidateDelta.removed().equals(latestDelta.removed())
                && candidateDelta.inserted().equals(latestDelta.inserted())) {
                previousMatch = candidate;
                break;
            }
        }
        if (previousMatch == null) return;

        String source = document.get();
        int from = 0;
        while (from < source.length()) {
            int found = source.indexOf(latestDelta.removed(), from);
            if (found < 0) break;
            from = found + Math.max(1, latestDelta.removed().length());
            if (!identifierBoundary(source, found, latestDelta.removed().length())) continue;
            if (sameLine(document, found, latest.lineOffset()) || sameLine(document, found, previousMatch.lineOffset())) continue;
            NextEditSuggestion next = new NextEditSuggestion(
                latest.objectKey(), found, latestDelta.removed().length(), latestDelta.removed(), latestDelta.inserted()
            );
            suggestion = next;
            Consumer<NextEditSuggestion> callback = listener;
            if (callback != null) callback.accept(next);
            return;
        }
    }

    static Delta delta(String before, String after) {
        if (before == null || after == null || before.equals(after)) return null;
        int prefix = 0;
        int maxPrefix = Math.min(before.length(), after.length());
        while (prefix < maxPrefix && before.charAt(prefix) == after.charAt(prefix)) prefix++;
        int suffix = 0;
        int beforeRemain = before.length() - prefix;
        int afterRemain = after.length() - prefix;
        while (suffix < beforeRemain && suffix < afterRemain
            && before.charAt(before.length() - 1 - suffix) == after.charAt(after.length() - 1 - suffix)) suffix++;
        String removed = before.substring(prefix, before.length() - suffix);
        String inserted = after.substring(prefix, after.length() - suffix);
        if (removed.length() > 160 || inserted.length() > 160) return null;
        return new Delta(removed, inserted);
    }

    private static boolean sameLine(IDocument document, int offset, int lineOffset) {
        try { return document.getLineInformationOfOffset(offset).getOffset() == lineOffset; }
        catch (BadLocationException error) { return false; }
    }

    private static boolean identifierBoundary(String source, int offset, int length) {
        if (length == 0) return false;
        boolean identifier = Character.isJavaIdentifierPart(source.charAt(offset));
        if (!identifier) return true;
        boolean left = offset > 0 && Character.isJavaIdentifierPart(source.charAt(offset - 1));
        int end = offset + length;
        boolean right = end < source.length() && Character.isJavaIdentifierPart(source.charAt(end));
        return !left && !right;
    }

    private static boolean sameObject(String a, String b) {
        if (a.isBlank() || b.isBlank()) return true;
        return a.equals(b) || a.endsWith("/" + b) || a.endsWith("\\" + b) || b.endsWith("/" + a) || b.endsWith("\\" + a);
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
