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

    private static String removeRepeatedPrefix(String completion, String before) {
        int max = Math.min(Math.min(completion.length(), before.length()), 500);
        for (int length = max; length >= 8; length--) {
            String suffix = before.substring(before.length() - length);
            if (completion.startsWith(suffix)) return completion.substring(length);
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
