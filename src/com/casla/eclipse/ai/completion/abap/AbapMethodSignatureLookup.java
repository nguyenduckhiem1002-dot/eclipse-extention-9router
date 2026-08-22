package com.casla.eclipse.ai.completion.abap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * When the cursor sits inside a METHOD ... ENDMETHOD implementation, the
 * model only sees the body -- not the METHODS/CLASS-METHODS signature from
 * the DEFINITION section that names its IMPORTING/EXPORTING/RETURNING
 * parameters. Without it, completions guess at parameter names instead of
 * using the real ones. This does a plain text search for that declaration
 * elsewhere in the same source (ADT's editor shows DEFINITION and
 * IMPLEMENTATION together for a class-pool source) and returns its raw text.
 */
public final class AbapMethodSignatureLookup {
    private AbapMethodSignatureLookup() {}

    public static String find(String fullDocument, String methodName) {
        if (fullDocument == null || fullDocument.isBlank() || methodName == null || methodName.isBlank()) {
            return "";
        }

        Pattern declaration = Pattern.compile(
            "(?im)^[ \\t]*(METHODS|CLASS-METHODS)\\s+" + Pattern.quote(methodName) + "\\b"
        );
        Matcher matcher = declaration.matcher(fullDocument);
        if (!matcher.find()) return "";

        int statementEnd = findStatementEnd(fullDocument, matcher.end());
        if (statementEnd < 0) return "";
        return fullDocument.substring(matcher.start(), statementEnd + 1).strip();
    }

    /** Scans forward from a declaration's keyword+name for the period that ends the ABAP statement, skipping string/template literals. */
    private static int findStatementEnd(String source, int from) {
        boolean inSingleQuote = false;
        boolean inTemplate = false;
        for (int i = from; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\'' && !inTemplate) {
                if (inSingleQuote && i + 1 < source.length() && source.charAt(i + 1) == '\'') {
                    i++;
                } else {
                    inSingleQuote = !inSingleQuote;
                }
            } else if (c == '|' && !inSingleQuote) {
                inTemplate = !inTemplate;
            } else if (c == '.' && !inSingleQuote && !inTemplate) {
                return i;
            }
        }
        return -1;
    }
}
