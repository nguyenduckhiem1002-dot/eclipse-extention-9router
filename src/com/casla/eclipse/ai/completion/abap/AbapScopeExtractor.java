package com.casla.eclipse.ai.completion.abap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A plain-text symbol table for the current ABAP source: identifiers that
 * appear in a "NAME TYPE sometype" or "VALUE(name) TYPE sometype" position
 * anywhere in the file, plus the names of declared methods. Not a real ABAP
 * parser -- no AST, no statement-boundary tracking, no scope nesting (a
 * DEFINITION-section attribute and a local variable inside one METHOD both
 * just become "a known symbol") -- but enough to give the model a concrete
 * "these identifiers exist, with these types" signal instead of a bare text
 * window, which is the difference between completing with a real field name
 * and inventing a plausible-looking one that doesn't compile.
 *
 * ADT has no public API for a real semantic model (the extension point this
 * plugin already uses for ABAP completions is itself internal/optional), so
 * this is deliberately the cheap, good-enough alternative rather than a
 * dependency on something SAP could change or restrict.
 */
public final class AbapScopeExtractor {
    private static final int MAX_SYMBOLS = 30;
    private static final int MAX_METHOD_NAMES = 30;

    private static final Pattern VALUE_PARAM = Pattern.compile(
        "(?i)VALUE\\((\\w+)\\)\\s+TYPE\\s+(?:REF\\s+TO\\s+)?([\\w/]+)"
    );
    private static final Pattern PLAIN_DECLARATION = Pattern.compile(
        "(?i)\\b(\\w+)\\s+TYPE\\s+(?:REF\\s+TO\\s+)?([\\w/]+)"
    );
    private static final Pattern METHOD_NAME = Pattern.compile("(?im)^[ \\t]*(?:CLASS-)?METHODS\\s+(\\S+)");

    private AbapScopeExtractor() {}

    /**
     * @param fullDocument the whole editor buffer
     * @return a prompt-ready block, or "" when nothing useful was found
     */
    public static String describe(String fullDocument) {
        if (fullDocument == null || fullDocument.isBlank()) return "";

        Map<String, String> symbols = new LinkedHashMap<>();
        collect(VALUE_PARAM, fullDocument, symbols);
        collect(PLAIN_DECLARATION, fullDocument, symbols);
        List<String> methodNames = collectMethodNames(fullDocument);

        if (symbols.isEmpty() && methodNames.isEmpty()) return "";

        StringBuilder out = new StringBuilder();
        if (!symbols.isEmpty()) {
            out.append("Known variables/parameters (name: type):\n");
            int count = 0;
            for (Map.Entry<String, String> entry : symbols.entrySet()) {
                if (count++ >= MAX_SYMBOLS) break;
                out.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
            }
        }
        if (!methodNames.isEmpty()) {
            out.append("Known methods: ").append(String.join(", ", methodNames)).append('\n');
        }
        return out.toString().stripTrailing();
    }

    private static void collect(Pattern pattern, String source, Map<String, String> symbols) {
        Matcher matcher = pattern.matcher(source);
        while (symbols.size() < MAX_SYMBOLS && matcher.find()) {
            symbols.putIfAbsent(matcher.group(1), matcher.group(2));
        }
    }

    private static List<String> collectMethodNames(String source) {
        List<String> names = new ArrayList<>();
        Matcher matcher = METHOD_NAME.matcher(source);
        while (names.size() < MAX_METHOD_NAMES && matcher.find()) {
            String name = matcher.group(1);
            if (!names.contains(name)) names.add(name);
        }
        return names;
    }
}
