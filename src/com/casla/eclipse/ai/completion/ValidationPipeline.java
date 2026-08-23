package com.casla.eclipse.ai.completion;

import java.util.regex.Pattern;

/**
 * A second, structural line of defense after CompletionSanitizer: the
 * sanitizer asks "is this text usable at all" (not prose, not a stray
 * fence); this asks "is this text safe to insert at this specific
 * position". The prompt already instructs the model not to mix up
 * DEFINITION and IMPLEMENTATION (see CompletionPromptBuilder.ABAP_RULES) --
 * this is what catches it when a model ignores that instruction anyway,
 * since that exact failure (a METHOD/ENDMETHOD body suggested inside a
 * DEFINITION section) is the bug this whole ABAP support effort started
 * from. Deliberately narrow: broad "does this parse as ABAP" validation
 * would need a real parser this codebase doesn't have and isn't trying to
 * build.
 */
public final class ValidationPipeline {
    private static final Pattern ENDMETHOD = Pattern.compile("(?i)\\bENDMETHOD\\b");
    private static final Pattern METHOD_IMPL_HEADER = Pattern.compile("(?im)^\\s*METHOD\\s+\\S+\\s*\\.");
    private static final Pattern METHODS_DECLARATION = Pattern.compile("(?i)\\bMETHODS\\b");

    private ValidationPipeline() {}

    /** True when the completion should be rejected rather than shown. */
    public static boolean isUnsafe(String insertion, String structureHint) {
        return violatesAbapStructure(insertion, structureHint) || hasUnbalancedParentheses(insertion);
    }

    private static boolean violatesAbapStructure(String insertion, String structureHint) {
        if (insertion == null || insertion.isBlank() || structureHint == null || structureHint.isBlank()) {
            return false;
        }
        boolean inDefinition = structureHint.contains("DEFINITION");
        boolean inImplementation = structureHint.contains("IMPLEMENTATION");
        if (inDefinition) {
            return ENDMETHOD.matcher(insertion).find() || METHOD_IMPL_HEADER.matcher(insertion).find();
        }
        if (inImplementation) {
            return METHODS_DECLARATION.matcher(insertion).find();
        }
        return false;
    }

    /**
     * Counts parens outside single-quote and template-string literals; a
     * mismatch is a strong signal of a truncated or malformed expression
     * (constructor expressions like REDUCE/COND nest parens deeply, so an
     * imbalance there is exactly the case worth catching).
     */
    private static boolean hasUnbalancedParentheses(String insertion) {
        if (insertion == null || insertion.isBlank()) return false;
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inTemplate = false;
        for (int i = 0; i < insertion.length(); i++) {
            char c = insertion.charAt(i);
            if (c == '\'' && !inTemplate) {
                inSingleQuote = !inSingleQuote;
            } else if (c == '|' && !inSingleQuote) {
                inTemplate = !inTemplate;
            } else if (inSingleQuote || inTemplate) {
                continue;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth < 0) return true;
            }
        }
        return depth != 0;
    }
}
