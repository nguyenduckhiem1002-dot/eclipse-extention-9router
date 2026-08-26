package com.casla.eclipse.ai.learning;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, model-independent profile of coding conventions observed in real ABAP
 * source. Metrics are exponential moving averages, so the profile adapts over
 * time without storing source code or growing an unbounded history.
 */
public final class ProjectStyleProfile {
    private static final double ALPHA = 0.18d;
    private static final Pattern INLINE_DATA = Pattern.compile("(?i)\\bDATA\\s*\\(");
    private static final Pattern CLASSIC_DATA = Pattern.compile("(?i)\\bDATA\\s+[a-z0-9_]+\\s+TYPE\\b");
    private static final Pattern READ_TABLE = Pattern.compile("(?i)\\bREAD\\s+TABLE\\b");
    private static final Pattern TABLE_EXPRESSION = Pattern.compile("(?i)\\b[a-z][a-z0-9_]*\\s*\\[[^\\]\\n]+\\]");
    private static final Pattern MODERN_EXPRESSION = Pattern.compile(
        "(?i)\\b(?:VALUE|CORRESPONDING|COND|REDUCE|NEW)\\s*#?\\s*\\(|\\bline_(?:exists|index)\\s*\\("
    );
    private static final Pattern PREFIXED_LOCAL = Pattern.compile("(?i)\\b(?:lv|lt|ls|lo|lr|lf|iv|it|is|ev|et|es)_[a-z0-9_]+\\b");
    private static final Pattern KEYWORD = Pattern.compile(
        "(?i)\\b(?:DATA|IF|ELSEIF|ELSE|ENDIF|LOOP|ENDLOOP|SELECT|ENDSELECT|METHOD|ENDMETHOD|READ|TABLE|VALUE|RETURNING|IMPORTING|EXPORTING|CHANGING|RECEIVING)\\b"
    );
    private static final Pattern METHOD_CALL = Pattern.compile("(?i)(?:->|=>)\\s*[a-z0-9_]+\\s*\\(");
    private static final Pattern MULTILINE_METHOD_CALL = Pattern.compile("(?im)(?:->|=>)\\s*[a-z0-9_]+\\s*\\(\\s*$");
    private static final Pattern PARAMETER_SECTION_LINE = Pattern.compile("(?im)^\\s*(?:EXPORTING|IMPORTING|CHANGING|RECEIVING)\\s*$");
    private static final Pattern PARAMETER_SECTION_INLINE = Pattern.compile("(?i)\\b(?:EXPORTING|IMPORTING|CHANGING|RECEIVING)\\s+[a-z0-9_]+\\s*=");
    private static final Pattern CLOSING_PAREN_OWN_LINE = Pattern.compile("(?m)^\\s*\\)\\.\\s*$");

    private int observations;
    private double inlineDeclaration;
    private double tableExpression;
    private double modernSyntax;
    private double uppercaseKeywords;
    private double prefixedNaming;
    private double multilineMethodCalls;
    private double parameterSectionsOnOwnLine;
    private double closingParenOwnLine;

    public synchronized void observeAbap(String source) {
        if (source == null || source.isBlank()) return;

        boolean hadSignal = false;

        int inline = count(INLINE_DATA, source);
        int classic = count(CLASSIC_DATA, source);
        if (inline + classic > 0) {
            inlineDeclaration = ema(inlineDeclaration, ratio(inline, inline + classic), observations > 0);
            hadSignal = true;
        }

        int tableExpressions = count(TABLE_EXPRESSION, source);
        int readTable = count(READ_TABLE, source);
        if (tableExpressions + readTable > 0) {
            tableExpression = ema(tableExpression, ratio(tableExpressions, tableExpressions + readTable), observations > 0);
            hadSignal = true;
        }

        int modern = count(MODERN_EXPRESSION, source);
        if (modern > 0 || inline + classic > 0 || tableExpressions + readTable > 0) {
            double sample = Math.min(1.0d, modern / 3.0d);
            modernSyntax = ema(modernSyntax, sample, observations > 0);
            hadSignal = true;
        }

        Matcher keywordMatcher = KEYWORD.matcher(source);
        int keywordCount = 0;
        int uppercaseCount = 0;
        while (keywordMatcher.find()) {
            keywordCount++;
            String token = keywordMatcher.group();
            if (token.equals(token.toUpperCase(Locale.ROOT))) uppercaseCount++;
        }
        if (keywordCount > 0) {
            uppercaseKeywords = ema(uppercaseKeywords, ratio(uppercaseCount, keywordCount), observations > 0);
            hadSignal = true;
        }

        int prefixed = count(PREFIXED_LOCAL, source);
        if (prefixed > 0 || inline + classic > 0) {
            prefixedNaming = ema(prefixedNaming, Math.min(1.0d, prefixed / 6.0d), observations > 0);
            hadSignal = true;
        }

        int methodCalls = count(METHOD_CALL, source);
        if (methodCalls > 0) {
            int multiline = count(MULTILINE_METHOD_CALL, source);
            multilineMethodCalls = ema(multilineMethodCalls, ratio(multiline, methodCalls), observations > 0);

            int standaloneSections = count(PARAMETER_SECTION_LINE, source);
            int inlineSections = count(PARAMETER_SECTION_INLINE, source);
            if (standaloneSections + inlineSections > 0) {
                parameterSectionsOnOwnLine = ema(
                    parameterSectionsOnOwnLine,
                    ratio(standaloneSections, standaloneSections + inlineSections),
                    observations > 0
                );
            }

            int closingOwnLine = count(CLOSING_PAREN_OWN_LINE, source);
            closingParenOwnLine = ema(
                closingParenOwnLine,
                Math.min(1.0d, ratio(closingOwnLine, methodCalls)),
                observations > 0
            );
            hadSignal = true;
        }

        if (hadSignal) observations++;
    }

    public synchronized String promptHints() {
        if (observations < 3) return "";

        List<String> hints = new ArrayList<>();
        if (inlineDeclaration >= 0.68d) {
            hints.add("Prefer inline DATA(...) declarations when the type is obvious.");
        } else if (inlineDeclaration <= 0.32d) {
            hints.add("Prefer explicit DATA name TYPE ... declarations over inline declarations.");
        }

        if (tableExpression >= 0.68d) {
            hints.add("Prefer table expressions over READ TABLE when semantics remain equivalent.");
        } else if (tableExpression <= 0.32d) {
            hints.add("Prefer READ TABLE style when reading internal tables.");
        }

        if (modernSyntax >= 0.64d) {
            hints.add("Prefer modern ABAP expressions such as VALUE, CORRESPONDING, COND, REDUCE and line_exists when appropriate.");
        }

        if (uppercaseKeywords >= 0.78d) {
            hints.add("Keep ABAP keywords uppercase.");
        } else if (uppercaseKeywords <= 0.22d) {
            hints.add("Keep ABAP keywords lowercase.");
        }

        if (prefixedNaming >= 0.55d) {
            hints.add("Preserve the observed lv_/lt_/ls_/lo_/iv_/it_ style prefixes for identifiers.");
        }

        if (multilineMethodCalls >= 0.58d) {
            hints.add("Prefer multi-line method calls when a call has multiple named parameters or parameter sections.");
        } else if (multilineMethodCalls <= 0.20d) {
            hints.add("Keep short method calls compact when they fit naturally on one line.");
        }
        if (parameterSectionsOnOwnLine >= 0.60d) {
            hints.add("Put EXPORTING, IMPORTING, CHANGING and RECEIVING on their own lines, with named parameters indented below the section.");
        }
        if (closingParenOwnLine >= 0.58d) {
            hints.add("For multi-line calls, keep the closing ). on its own line.");
        }

        return String.join("\n", hints);
    }

    public synchronized int observations() { return observations; }

    public synchronized void reset() {
        observations = 0;
        inlineDeclaration = 0;
        tableExpression = 0;
        modernSyntax = 0;
        uppercaseKeywords = 0;
        prefixedNaming = 0;
        multilineMethodCalls = 0;
        parameterSectionsOnOwnLine = 0;
        closingParenOwnLine = 0;
    }

    public synchronized void load(Properties properties) {
        observations = integer(properties, "observations", 0);
        inlineDeclaration = decimal(properties, "inlineDeclaration", 0);
        tableExpression = decimal(properties, "tableExpression", 0);
        modernSyntax = decimal(properties, "modernSyntax", 0);
        uppercaseKeywords = decimal(properties, "uppercaseKeywords", 0);
        prefixedNaming = decimal(properties, "prefixedNaming", 0);
        multilineMethodCalls = decimal(properties, "multilineMethodCalls", 0);
        parameterSectionsOnOwnLine = decimal(properties, "parameterSectionsOnOwnLine", 0);
        closingParenOwnLine = decimal(properties, "closingParenOwnLine", 0);
    }

    public synchronized void store(Properties properties) {
        properties.setProperty("observations", Integer.toString(observations));
        properties.setProperty("inlineDeclaration", Double.toString(inlineDeclaration));
        properties.setProperty("tableExpression", Double.toString(tableExpression));
        properties.setProperty("modernSyntax", Double.toString(modernSyntax));
        properties.setProperty("uppercaseKeywords", Double.toString(uppercaseKeywords));
        properties.setProperty("prefixedNaming", Double.toString(prefixedNaming));
        properties.setProperty("multilineMethodCalls", Double.toString(multilineMethodCalls));
        properties.setProperty("parameterSectionsOnOwnLine", Double.toString(parameterSectionsOnOwnLine));
        properties.setProperty("closingParenOwnLine", Double.toString(closingParenOwnLine));
    }

    private static int count(Pattern pattern, String source) {
        int result = 0;
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) result++;
        return result;
    }

    private static double ratio(int numerator, int denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static double ema(double current, double sample, boolean initialized) {
        return initialized ? current * (1.0d - ALPHA) + sample * ALPHA : sample;
    }

    private static int integer(Properties properties, String key, int fallback) {
        try { return Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    private static double decimal(Properties properties, String key, double fallback) {
        try {
            double value = Double.parseDouble(properties.getProperty(key, Double.toString(fallback)));
            return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
