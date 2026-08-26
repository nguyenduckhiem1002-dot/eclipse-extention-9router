package com.casla.eclipse.ai.learning;

import org.eclipse.jface.text.DocumentEvent;

import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.completion.CodeContext;

/** Keeps only short-lived completion text in memory so document edits can be classified. */
public final class CompletionFeedbackTracker {
    private static final CompletionFeedbackTracker INSTANCE = new CompletionFeedbackTracker();
    private static final long PENDING_TTL_MS = 15_000L;
    private static final long EDIT_WINDOW_MS = 10_000L;

    private record Pending(
        String objectKey,
        String structureHint,
        String contextBucket,
        String contextShape,
        String model,
        int offset,
        String text,
        int matchedChars,
        boolean hadExplicitAccept,
        long createdAt
    ) {}
    private record AcceptedRegion(String objectKey, String model, String contextBucket, int offset, int length, long expiresAt, boolean alreadyEdited) {}

    private Pending pending;
    private AcceptedRegion acceptedRegion;

    private CompletionFeedbackTracker() {}
    public static CompletionFeedbackTracker get() { return INSTANCE; }

    public synchronized void generated(CodeContext context, CompletionResponse response) {
        if (context == null || response == null || !"ABAP".equalsIgnoreCase(context.language())) return;
        String text = response.content();
        if (text == null || text.isBlank()) return;
        expire();
        if (pending != null) record(pending.model(), pending.contextBucket(), FeedbackEvent.SUPERSEDED);
        String model = clean(response.responseModel());
        String bucket = CompletionContextClassifier.bucket(context);
        String shape = AbapStructuralSignature.of(context.beforeCursor());
        pending = new Pending(
            clean(context.filePath()), clean(context.structureHint()), bucket, shape, model,
            context.cursorOffset(), text, 0, false, System.currentTimeMillis()
        );
        record(model, bucket, FeedbackEvent.GENERATED);
    }

    /**
     * @return true only when this document event consumed part of the active AI
     * completion. Callers may use this to keep AI-generated edits out of a
     * manual edit-history learner.
     */
    public synchronized boolean documentChanged(String objectKey, String language, DocumentEvent event) {
        if (!"ABAP".equalsIgnoreCase(language) || event == null) return false;
        expire();
        String key = clean(objectKey);
        detectEditAfterAccept(key, event);
        Pending current = pending;
        if (current == null || !sameObject(current.objectKey(), key)) return false;

        String inserted = event.getText() == null ? "" : event.getText();
        int expectedOffset = current.offset() + current.matchedChars();
        if (event.getLength() != 0 || event.getOffset() != expectedOffset || inserted.isEmpty()) {
            record(current.model(), current.contextBucket(), FeedbackEvent.DISMISSED);
            pending = null;
            return false;
        }
        String remaining = current.text().substring(current.matchedChars());
        if (!remaining.startsWith(inserted)) {
            record(current.model(), current.contextBucket(), FeedbackEvent.DISMISSED);
            pending = null;
            return false;
        }

        FeedbackEvent eventType = classifyInsertion(remaining, inserted, current.matchedChars());
        boolean explicitThisEvent = eventType == FeedbackEvent.ACCEPT_FULL
            || eventType == FeedbackEvent.ACCEPT_WORD
            || eventType == FeedbackEvent.ACCEPT_LINE;
        boolean hadExplicitAccept = current.hadExplicitAccept() || explicitThisEvent;
        int newMatched = current.matchedChars() + inserted.length();
        if (newMatched >= current.text().length()) {
            record(current.model(), current.contextBucket(), eventType);
            if (eventType != FeedbackEvent.TYPED_MATCH || hadExplicitAccept) {
                acceptedRegion = new AcceptedRegion(
                    key, current.model(), current.contextBucket(), current.offset(), current.text().length(),
                    System.currentTimeMillis() + EDIT_WINDOW_MS, false
                );
            }
            // Do not bias memory toward Tab-only users: a suggestion that was
            // partially accepted with word/line commands and then fully
            // consumed is still a successful accepted pattern.
            if (hadExplicitAccept) {
                AdaptiveLearningStore.get().rememberAcceptedExample(
                    key,
                    current.structureHint(),
                    current.contextBucket(),
                    current.contextShape(),
                    current.text(),
                    current.model()
                );
            }
            pending = null;
            return true;
        }

        if (explicitThisEvent) {
            record(current.model(), current.contextBucket(), eventType);
            acceptedRegion = new AcceptedRegion(
                key, current.model(), current.contextBucket(), current.offset(), newMatched,
                System.currentTimeMillis() + EDIT_WINDOW_MS, false
            );
        }
        pending = new Pending(
            current.objectKey(), current.structureHint(), current.contextBucket(), current.contextShape(), current.model(),
            current.offset(), current.text(), newMatched, hadExplicitAccept, current.createdAt()
        );
        return true;
    }

    public synchronized void dismissPending() {
        expire();
        if (pending == null) return;
        record(pending.model(), pending.contextBucket(), FeedbackEvent.DISMISSED);
        pending = null;
    }
    public synchronized void resetTransient() { pending = null; acceptedRegion = null; }

    private void detectEditAfterAccept(String objectKey, DocumentEvent event) {
        AcceptedRegion region = acceptedRegion;
        if (region == null || region.alreadyEdited() || !sameObject(region.objectKey(), objectKey)) return;
        int editStart = event.getOffset();
        int editEnd = editStart + Math.max(event.getLength(), event.getText() == null ? 0 : event.getText().length());
        int regionEnd = region.offset() + region.length();
        if (!(editStart < regionEnd && editEnd > region.offset())) return;
        record(region.model(), region.contextBucket(), FeedbackEvent.EDITED_AFTER_ACCEPT);
        acceptedRegion = new AcceptedRegion(
            region.objectKey(), region.model(), region.contextBucket(), region.offset(), region.length(), region.expiresAt(), true
        );
    }

    private void expire() {
        long now = System.currentTimeMillis();
        if (pending != null && now - pending.createdAt() > PENDING_TTL_MS) {
            record(pending.model(), pending.contextBucket(), FeedbackEvent.DISMISSED);
            pending = null;
        }
        if (acceptedRegion != null && acceptedRegion.expiresAt() < now) acceptedRegion = null;
    }

    private static void record(String model, String bucket, FeedbackEvent event) {
        AdaptiveLearningStore.get().recordFeedback(model, bucket, event);
    }

    static FeedbackEvent classifyInsertion(String remaining, String inserted, int alreadyMatched) {
        if (inserted.equals(remaining)) return alreadyMatched == 0 ? FeedbackEvent.ACCEPT_FULL : FeedbackEvent.TYPED_MATCH;
        String line = nextLine(remaining);
        if (inserted.length() > 1 && inserted.equals(line)) return FeedbackEvent.ACCEPT_LINE;
        String word = nextWord(remaining);
        if (inserted.length() > 1 && inserted.equals(word)) return FeedbackEvent.ACCEPT_WORD;
        return FeedbackEvent.TYPED_MATCH;
    }

    private static String nextWord(String text) {
        int i = 0;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
        if (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) {
            while (i < text.length() && Character.isJavaIdentifierPart(text.charAt(i))) i++;
        } else if (i < text.length()) {
            while (i < text.length() && !Character.isWhitespace(text.charAt(i)) && !Character.isJavaIdentifierPart(text.charAt(i))) i++;
        }
        return text.substring(0, Math.max(1, i));
    }
    private static String nextLine(String text) { int index = text.indexOf('\n'); return index < 0 ? text : text.substring(0, index + 1); }
    private static boolean sameObject(String a, String b) { if (a.isBlank() || b.isBlank()) return true; return a.equals(b) || a.endsWith("/" + b) || a.endsWith("\\" + b); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }

    public enum FeedbackEvent { GENERATED, ACCEPT_FULL, ACCEPT_WORD, ACCEPT_LINE, TYPED_MATCH, DISMISSED, SUPERSEDED, EDITED_AFTER_ACCEPT }
}
