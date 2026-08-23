package com.casla.eclipse.ai.completion;

import java.util.Locale;
import java.util.Set;

import org.eclipse.jface.text.DocumentEvent;

/**
 * Pure decision logic for whether/how fast an automatic ghost-text request
 * should fire after a document edit. Deliberately has no SWT/JFace-viewer
 * dependency beyond DocumentEvent, so it can be unit tested headlessly;
 * the one check that genuinely needs live viewer state (whether ADT's own
 * Content Assist popup is open) lives in GhostTextController instead, since
 * tracking it requires registering a session listener on the viewer, not a
 * one-shot query.
 */
public final class TriggerEngine {
    private static final int FAST_DEBOUNCE_MILLIS = 150;

    /**
     * Single-word ABAP keywords that typically open a new block or clause.
     * Firing sooner after one of these (plus a trailing space) reads as
     * responsive; the same speed-up for everything else would just make
     * ordinary identifier typing feel twitchy.
     */
    private static final Set<String> FAST_TRIGGER_KEYWORDS = Set.of(
        "data", "types", "constants", "field-symbols",
        "if", "loop", "select", "method", "try", "case", "return",
        "endif", "endloop", "endmethod", "endtry", "endcase", "endselect"
    );

    private TriggerEngine() {}

    /** A pure deletion (backspace/delete/cut) with nothing inserted -- don't chase a target that's shrinking. */
    public static boolean isDeletion(DocumentEvent event) {
        String inserted = event.getText();
        return event.getLength() > 0 && (inserted == null || inserted.isEmpty());
    }

    /**
     * Ordinary line/block comments are just human notes -- interrupting them
     * with a suggestion is more often noise than help. Javadoc and string
     * literals are deliberately NOT blocked here: CompletionPromptBuilder
     * already has dedicated roles for completing doc text and string
     * content, and blocking them would make that support unreachable.
     */
    public static boolean blocksAutoTrigger(CursorContextType contextType) {
        return contextType == CursorContextType.LINE_COMMENT || contextType == CursorContextType.BLOCK_COMMENT;
    }

    /**
     * Fast debounce right after a newline, or right after one of the
     * trigger keywords followed by a space (the user just committed to a
     * new statement/block) -- otherwise the caller's configured debounce.
     */
    public static int debounceMillis(DocumentEvent event, String textUpToCursor, int configuredMillis) {
        String inserted = event.getText();
        if (inserted != null && inserted.endsWith("\n")) return FAST_DEBOUNCE_MILLIS;
        return endsWithTriggerKeyword(textUpToCursor) ? FAST_DEBOUNCE_MILLIS : configuredMillis;
    }

    private static boolean endsWithTriggerKeyword(String textUpToCursor) {
        if (textUpToCursor == null || textUpToCursor.isEmpty()) return false;
        boolean trailingSpace = Character.isWhitespace(textUpToCursor.charAt(textUpToCursor.length() - 1));
        if (!trailingSpace) return false;

        String trimmed = textUpToCursor.stripTrailing();
        int start = trimmed.length();
        while (start > 0 && (Character.isLetter(trimmed.charAt(start - 1)) || trimmed.charAt(start - 1) == '-')) {
            start--;
        }
        if (start == trimmed.length()) return false;
        return FAST_TRIGGER_KEYWORDS.contains(trimmed.substring(start).toLowerCase(Locale.ROOT));
    }
}
