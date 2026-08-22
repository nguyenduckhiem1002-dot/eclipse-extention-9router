package com.casla.eclipse.ai.completion;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class CompletionSanitizer {
    private static final int MAX_INSERTION_CHARACTERS = 32_000;

    /**
     * A fast/low-effort model asked for "code only, no explanations" doesn't
     * always comply -- it sometimes writes prose like "for `get_min`, it
     * starts by checking..." instead of code, which then gets inserted
     * straight into the source file. This is the short list of common
     * English connective/filler words a run of which marks text as prose
     * rather than ABAP or Java; deliberately excludes words that double as
     * real keywords in either language (e.g. "if", "is", "in", "do", "at").
     */
    private static final Set<String> PROSE_WORDS = Set.of(
        "it", "this", "that", "these", "those", "starts", "begins", "here",
        "note", "we", "you", "your", "would", "should", "could", "will",
        "and", "but", "so", "means", "simply", "basically", "essentially"
    );
    private static final int PROSE_RUN_THRESHOLD = 2;
    private static final Pattern WORD = Pattern.compile("[a-zA-Z']+");

    /** "`identifier`, " -- a code token mentioned inline inside a sentence -- is a distinctive prose tell no real ABAP/Java statement produces. */
    private static final Pattern BACKTICK_MENTION = Pattern.compile("`[\\w]+`\\s*,");

    public String sanitize(String raw, CodeContext context) {
        String value = raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        value = stripFence(value);
        value = value.strip();
        value = removeRepeatedPrefix(value, context.beforeCursor());
        value = removeRepeatedSuffix(value, context.afterCursor());
        if (value.length() > MAX_INSERTION_CHARACTERS) {
            value = value.substring(0, MAX_INSERTION_CHARACTERS);
        }
        return looksLikeProse(value) ? "" : value;
    }

    /** Strips a ```-fenced block wherever it appears, not just when the response starts with one. */
    private static String stripFence(String value) {
        int openFence = value.indexOf("```");
        if (openFence < 0) return value;
        int firstNewLine = value.indexOf('\n', openFence);
        int contentStart = firstNewLine >= 0 ? firstNewLine + 1 : value.length();
        int closingFence = value.indexOf("```", contentStart);
        return closingFence >= 0 ? value.substring(contentStart, closingFence) : value.substring(contentStart);
    }

    /**
     * A cheap, deliberately conservative "is this actually a sentence"
     * check: three or more of the connective words above in a row is enough
     * English prose that real code -- ABAP or Java identifiers are
     * underscore/camelCase, not space-separated filler words -- essentially
     * never produces it by coincidence.
     */
    private static boolean looksLikeProse(String value) {
        if (value.isBlank()) return false;
        if (BACKTICK_MENTION.matcher(value).find()) return true;

        var matcher = WORD.matcher(value);
        int run = 0;
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT);
            if (PROSE_WORDS.contains(word)) {
                run++;
                if (run >= PROSE_RUN_THRESHOLD) return true;
            } else {
                run = 0;
            }
        }
        return false;
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
