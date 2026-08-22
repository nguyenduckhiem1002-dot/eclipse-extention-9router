package com.casla.eclipse.ai.completion;

public final class CompletionSanitizer {
    private static final int MAX_INSERTION_CHARACTERS = 32_000;

    public String sanitize(String raw, CodeContext context) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (value.startsWith("```")) {
            int firstNewLine = value.indexOf('\n');
            if (firstNewLine >= 0) value = value.substring(firstNewLine + 1);
            int closingFence = value.lastIndexOf("```");
            if (closingFence >= 0) value = value.substring(0, closingFence);
        }

        value = value.strip();
        value = removeRepeatedPrefix(value, context.beforeCursor());
        value = removeRepeatedSuffix(value, context.afterCursor());
        if (value.length() > MAX_INSERTION_CHARACTERS) {
            value = value.substring(0, MAX_INSERTION_CHARACTERS);
        }
        return value;
    }

    /**
     * A short match (under 8 chars) is only trusted as an echoed prefix -- not
     * a coincidence -- when it is pure identifier/number characters (so it can
     * never eat leading indentation, which is whitespace) and sits at a word
     * boundary in `before` (so "...i" followed by a completion starting "if"
     * doesn't get chopped to "f ..."). Long matches (>=8 chars) are unlikely
     * to be a coincidence either way and keep the original unconditional cut.
     */
    private static String removeRepeatedPrefix(String completion, String before) {
        int max = Math.min(Math.min(completion.length(), before.length()), 500);
        for (int length = max; length >= 1; length--) {
            int suffixStart = before.length() - length;
            String suffix = before.substring(suffixStart);
            if (!completion.startsWith(suffix)) continue;
            if (length >= 8) return completion.substring(length);
            boolean isIdentifierRun = !suffix.isBlank()
                && suffix.chars().allMatch(Character::isJavaIdentifierPart);
            boolean atWordBoundary = suffixStart == 0
                || !Character.isJavaIdentifierPart(before.charAt(suffixStart - 1));
            if (isIdentifierRun && atWordBoundary) return completion.substring(length);
        }
        return completion;
    }

    private static String removeRepeatedSuffix(String completion, String after) {
        int max = Math.min(Math.min(completion.length(), after.length()), 500);
        for (int length = max; length >= 2; length--) {
            String prefix = after.substring(0, length);
            if (completion.endsWith(prefix)) {
                return completion.substring(0, completion.length() - length);
            }
        }
        return completion;
    }
}
