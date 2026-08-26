package com.casla.eclipse.ai.completion.abap;

import com.casla.eclipse.ai.completion.CodeContext;

/**
 * Conservative ABAP post-processing applied after model sanitization and before
 * structural validation. This deliberately fixes only lexical whitespace that
 * is unambiguous in ABAP; it is not a general-purpose formatter.
 */
public final class AbapCompletionQualityGate {
    private AbapCompletionQualityGate() {}

    public static String refine(String insertion, CodeContext context) {
        if (insertion == null || insertion.isBlank()) return insertion == null ? "" : insertion;
        if (context == null || !"ABAP".equalsIgnoreCase(context.language())) return insertion;

        String[] lines = insertion.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder(insertion.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) out.append('\n');
            out.append(refineLine(lines[i]));
        }
        return out.toString().stripTrailing();
    }

    static String refineLine(String line) {
        if (line == null || line.isEmpty()) return line == null ? "" : line;
        StringBuilder out = new StringBuilder(line.length() + 8);
        StringBuilder code = new StringBuilder();
        boolean singleQuote = false;
        boolean template = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (!singleQuote && !template && c == '"') {
                out.append(refineCodeSegment(code.toString()));
                code.setLength(0);
                out.append(line.substring(i));
                return out.toString().stripTrailing();
            }
            if (c == '\'' && !template) {
                if (!singleQuote) {
                    out.append(refineCodeSegment(code.toString()));
                    code.setLength(0);
                }
                singleQuote = !singleQuote;
                out.append(c);
                continue;
            }
            if (c == '|' && !singleQuote) {
                if (!template) {
                    out.append(refineCodeSegment(code.toString()));
                    code.setLength(0);
                }
                template = !template;
                out.append(c);
                continue;
            }
            if (singleQuote || template) out.append(c);
            else code.append(c);
        }
        out.append(refineCodeSegment(code.toString()));
        return out.toString().stripTrailing();
    }

    private static String refineCodeSegment(String code) {
        if (code.isEmpty()) return code;
        String value = code
            // Whole relational operators first.
            .replaceAll("\\s*(<=|>=|<>)\\s*", " $1 ")
            // Compound assignment must be handled before standalone '=' so
            // `x+=1` never becomes the invalid `x + = 1`.
            .replaceAll("\\s*([+\\-*/])=\\s*", " $1= ")
            // Standalone assignment/comparison '=' only; explicitly exclude
            // class selector => and the left halves of compound operators.
            .replaceAll("(?<![<>=+\\-*/:])\\s*=\\s*(?![=>])", " = ");

        int originalIndent = leadingWhitespace(code);
        int newIndent = leadingWhitespace(value);
        if (newIndent != originalIndent && originalIndent > 0) {
            value = code.substring(0, originalIndent) + value.stripLeading();
        }
        return value;
    }

    private static int leadingWhitespace(String value) {
        int i = 0;
        while (i < value.length() && Character.isWhitespace(value.charAt(i))) i++;
        return i;
    }
}
