package com.casla.eclipse.ai.learning;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight structural normalization for local retrieval; not an ABAP parser. */
public final class AbapStructuralSignature {
    private static final int MAX_INPUT = 900;
    private static final Pattern TOKEN = Pattern.compile(
        "[A-Za-z_][A-Za-z0-9_]*|\\d+(?:\\.\\d+)?|->|=>|<=|>=|<>|[()\\[\\],.:=+*/~\\-]"
    );
    private static final Set<String> KEYWORDS = Set.copyOf(java.util.List.of(
        "DATA", "FINAL", "FIELD-SYMBOLS", "CONSTANTS", "TYPES", "TYPE", "LIKE",
        "IF", "ELSEIF", "ELSE", "ENDIF", "CASE", "WHEN", "ENDCASE", "CHECK", "RETURN",
        "LOOP", "AT", "INTO", "ASSIGNING", "REFERENCE", "ENDLOOP", "READ", "TABLE", "WITH", "KEY",
        "SELECT", "SINGLE", "FROM", "FIELDS", "WHERE", "INTO", "TABLE", "APPENDING", "UP", "TO", "ROWS",
        "METHOD", "ENDMETHOD", "METHODS", "CLASS", "ENDCLASS", "DEFINITION", "IMPLEMENTATION",
        "EXPORTING", "IMPORTING", "CHANGING", "RECEIVING", "RETURNING", "RAISING",
        "VALUE", "CORRESPONDING", "COND", "SWITCH", "REDUCE", "NEW", "REF", "CONV", "CAST",
        "TRY", "CATCH", "CLEANUP", "ENDTRY", "RAISE", "EXCEPTION", "MESSAGE",
        "MODIFY", "ENTITIES", "ENTITY", "READ", "IN", "LOCAL", "MODE", "FAILED", "REPORTED", "MAPPED",
        "AND", "OR", "NOT", "IS", "INITIAL", "BOUND", "INSTANCE", "OF", "ABAP_TRUE", "ABAP_FALSE"
    ));

    private AbapStructuralSignature() {}

    public static String of(String source) {
        if (source == null || source.isBlank()) return "";
        String text = source.length() > MAX_INPUT ? source.substring(source.length() - MAX_INPUT) : source;
        text = text.replaceAll("'[^'\\n]*'", " <LITERAL> ")
            .replaceAll("`[^`\\n]*`", " <LITERAL> ");

        Map<String, String> identifiers = new HashMap<>();
        int next = 1;
        StringBuilder out = new StringBuilder();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            String upper = token.toUpperCase(Locale.ROOT);
            String normalized;
            if (KEYWORDS.contains(upper)) {
                normalized = upper;
            } else if (Character.isDigit(token.charAt(0))) {
                normalized = "<NUMBER>";
            } else if (Character.isJavaIdentifierStart(token.charAt(0))) {
                normalized = identifiers.get(token.toLowerCase(Locale.ROOT));
                if (normalized == null) {
                    normalized = "<I" + next++ + ">";
                    identifiers.put(token.toLowerCase(Locale.ROOT), normalized);
                }
            } else {
                normalized = token;
            }
            if (out.length() > 0) out.append(' ');
            out.append(normalized);
        }
        return out.toString();
    }
}
