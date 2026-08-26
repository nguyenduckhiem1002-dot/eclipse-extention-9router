package com.casla.eclipse.ai.completion;

/**
 * Converts a model insertion into a conservative editor edit plan.
 *
 * Most completions remain pure insertions. When the caret sits before an
 * existing identifier and the model starts with that exact same whole token,
 * the duplicated source token is replaced instead of inserted twice. This is
 * deliberately narrower than a generic diff algorithm: a false negative only
 * leaves an ordinary insertion, while a false positive could delete source.
 */
public final class CompletionEditPlanner {
    public record Plan(String text, int replaceLength, boolean suppressInline) {}

    private CompletionEditPlanner() {}

    public static Plan plan(String insertion, String afterCursor) {
        String text = insertion == null ? "" : insertion;
        String after = afterCursor == null ? "" : afterCursor;
        String tail = firstLine(after);
        if (tail.isBlank()) {
            return new Plan(text, 0, false);
        }

        // Any non-blank same-line suffix makes raw StyledText overlay unsafe.
        // The suggestion is shown in the floating preview even when it remains
        // a pure insertion, e.g. method( <CURSOR> ).
        boolean suppressInline = true;

        int tailWhitespace = leadingWhitespace(tail);
        int insertionWhitespace = leadingWhitespace(text);
        String tailCore = tail.substring(tailWhitespace);
        String insertionCore = text.substring(Math.min(insertionWhitespace, text.length()));

        int tailTokenLength = identifierTokenLength(tailCore);
        int insertionTokenLength = identifierTokenLength(insertionCore);
        if (tailTokenLength <= 0 || insertionTokenLength <= 0) {
            return new Plan(text, 0, suppressInline);
        }

        String tailToken = tailCore.substring(0, tailTokenLength);
        String insertionToken = insertionCore.substring(0, insertionTokenLength);
        if (!tailToken.equals(insertionToken)) {
            return new Plan(text, 0, suppressInline);
        }

        // Preserve the exact whitespace that already exists after the caret.
        // This guarantees the replacement prefix in the ghost matches the
        // source prefix byte-for-byte, which also makes partial acceptance safe.
        String sourcePrefix = tail.substring(0, tailWhitespace + tailTokenLength);
        String adjusted = sourcePrefix + insertionCore.substring(insertionTokenLength);
        return new Plan(adjusted, sourcePrefix.length(), suppressInline);
    }

    private static String firstLine(String value) {
        int lf = value.indexOf('\n');
        int cr = value.indexOf('\r');
        int end;
        if (lf < 0) end = cr;
        else if (cr < 0) end = lf;
        else end = Math.min(lf, cr);
        return end < 0 ? value : value.substring(0, end);
    }

    private static int leadingWhitespace(String value) {
        int i = 0;
        while (i < value.length() && (value.charAt(i) == ' ' || value.charAt(i) == '\t')) i++;
        return i;
    }

    private static int identifierTokenLength(String value) {
        if (value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) return 0;
        int i = 1;
        while (i < value.length() && Character.isJavaIdentifierPart(value.charAt(i))) i++;
        return i;
    }
}
