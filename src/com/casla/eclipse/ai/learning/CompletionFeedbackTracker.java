package com.casla.eclipse.ai.learning;

import org.eclipse.jface.text.DocumentEvent;

import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.completion.CodeContext;

/**
 * Keeps only short-lived completion text in memory so document edits can be
 * classified. Persisted state contains counters only via AdaptiveLearningStore.
 */
public final class CompletionFeedbackTracker {
    private static final CompletionFeedbackTracker INSTANCE = new CompletionFeedbackTracker();
    private static final long PENDING_TTL_MS = 15_000L;
    private static final long EDIT_WINDOW_MS = 10_000L;

    private record Pending(
        String objectKey,
        String model,
        int offset,
        String text,
        int matchedChars,
        long createdAt
    ) {}

    private record AcceptedRegion(
        String objectKey,
        String model,
        int offset,
        int length,
        long expiresAt,
        boolean alreadyEdited
    ) {}

    private Pending pending;
    private AcceptedRegion acceptedRegion;

    private CompletionFeedbackTracker() {}

    public static CompletionFeedbackTracker get() {
        return INSTANCE;
    }

    public synchronized void generated(CodeContext context, CompletionResponse response) {
        if (context == null || response == null || !"ABAP".equalsIgnoreCase(context.language())) return;
        String text = response.content();
        if (text == null || text.isBlank()) return;

        expire();
        if (pending != null) {
            AdaptiveLearningStore.get().recordFeedback(pending.model(), FeedbackEvent.SUPERSEDED);
        }
        pending = new Pending(
            clean(context.filePath()),
            clean(response.responseModel()),
            context.cursorOffset(),
            text,
            0,
            System.currentTimeMillis()
        );
        AdaptiveLearningStore.get().recordFeedback(clean(response.responseModel()), FeedbackEvent.GENERATED);
    }

    public synchronized void documentChanged(String objectKey, String language, DocumentEvent event) {
        if (!"ABAP".equalsIgnoreCase(language) || event == null) return;
        expire();
        String key = clean(objectKey);

        detectEditAfterAccept(key, event);

        Pending current = pending;
        if (current == null || !sameObject(current.objectKey(), key)) return;

        String inserted = event.getText() == null ? "" : event.getText();
        int expectedOffset = current.offset() + current.matchedChars();
        if (event.getLength() != 0 || event.getOffset() != expectedOffset || inserted.isEmpty()) {
            AdaptiveLearningStore.get().recordFeedback(current.model(), FeedbackEvent.DISMISSED);
            pending = null;
            return;
        }

        String remaining = current.text().substring(current.matchedChars());
        if (!remaining.startsWith(inserted)) {
            AdaptiveLearningStore.get().recordFeedback(current.model(), FeedbackEvent.DISMISSED);
            pending = null;
            return;
        }

        FeedbackEvent eventType = classifyInsertion(remaining, inserted, current.matchedChars());
        int newMatched = current.matchedChars() + inserted.length();
        if (newMatched >= current.text().length()) {
            AdaptiveLearningStore.get().recordFeedback(current.model(), eventType);
            if (eventType != FeedbackEvent.TYPED_MATCH) {
                acceptedRegion = new AcceptedRegion(
                    key, current.model(), current.offset(), current.text().length(),
                    System.currentTimeMillis() + EDIT_WINDOW_MS, false
                );
            }
            pending = null;
            return;
        }

        if (eventType == FeedbackEvent.ACCEPT_WORD || eventType == FeedbackEvent.ACCEPT_LINE) {
            AdaptiveLearningStore.get().recordFeedback(current.model(), eventType);
            acceptedRegion = new AcceptedRegion(
                key, current.model(), current.offset(), newMatched,
                System.currentTimeMillis() + EDIT_WINDOW_MS, false
            );
        }
        pending = new Pending(
            current.objectKey(), current.model(), current.offset(), current.text(), newMatched, current.createdAt()
        );
    }

    public synchronized void dismissPending() {
        expire();
        if (pending == null) return;
        AdaptiveLearningStore.get().recordFeedback(pending.model(), FeedbackEvent.DISMISSED);
        pending = null;
    }

    public synchronized void resetTransient() {
        pending = null;
        acceptedRegion = null;
    }

    private void detectEditAfterAccept(String objectKey, DocumentEvent event) {
        AcceptedRegion region = acceptedRegion;
        if (region == null || region.alreadyEdited() || !sameObject(region.objectKey(), objectKey)) return;
        int editStart = event.getOffset();
        int editEnd = editStart + Math.max(event.getLength(), event.getText() == null ? 0 : event.getText().length());
        int regionEnd = region.offset() + region.length();
        boolean overlaps = editStart < regionEnd && editEnd > region.offset();
        if (!overlaps) return;

        AdaptiveLearningStore.get().recordFeedback(region.model(), FeedbackEvent.EDITED_AFTER_ACCEPT);
        acceptedRegion = new AcceptedRegion(
            region.objectKey(), region.model(), region.offset(), region.length(), region.expiresAt(), true
        );
    }

    private void expire() {
        long now = System.currentTimeMillis();
        if (pending != null && now - pending.createdAt() > PENDING_TTL_MS) {
            AdaptiveLearningStore.get().recordFeedback(pending.model(), FeedbackEvent.DISMISSED);
            pending = null;
        }
        if (acceptedRegion != null && acceptedRegion.expiresAt() < now) acceptedRegion = null;
    }

    static FeedbackEvent classifyInsertion(String remaining, String inserted, int alreadyMatched) {
        if (inserted.equals(remaining)) {
            return alreadyMatched == 0 ? FeedbackEvent.ACCEPT_FULL : FeedbackEvent.TYPED_MATCH;
        }
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
            while (i < text.length() && !Character.isWhitespace(text.charAt(i))
                && !Character.isJavaIdentifierPart(text.charAt(i))) i++;
        }
        return text.substring(0, Math.max(1, i));
    }

    private static String nextLine(String text) {
        int index = text.indexOf('\n');
        return index < 0 ? text : text.substring(0, index + 1);
    }

    private static boolean sameObject(String generatedKey, String editorKey) {
        if (generatedKey.isBlank() || editorKey.isBlank()) return true;
        return generatedKey.equals(editorKey)
            || generatedKey.endsWith("/" + editorKey)
            || generatedKey.endsWith("\\" + editorKey);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public enum FeedbackEvent {
        GENERATED,
        ACCEPT_FULL,
        ACCEPT_WORD,
        ACCEPT_LINE,
        TYPED_MATCH,
        DISMISSED,
        SUPERSEDED,
        EDITED_AFTER_ACCEPT
    }
}
