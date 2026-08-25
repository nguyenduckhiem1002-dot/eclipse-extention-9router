package com.casla.eclipse.ai.learning;

import java.util.Locale;

import com.casla.eclipse.ai.completion.CodeContext;

public final class CompletionContextClassifier {
    private CompletionContextClassifier() {}

    public static String bucket(CodeContext context) {
        if (context == null) return "unknown";
        String language = context.language() == null ? "" : context.language().toUpperCase(Locale.ROOT);
        if (!"ABAP".equals(language)) return language.isBlank() ? "text" : language.toLowerCase(Locale.ROOT);

        String text = (context.beforeCursor() + "\n" + context.afterCursor()).toUpperCase(Locale.ROOT);
        String structure = context.structureHint() == null ? "" : context.structureHint().toUpperCase(Locale.ROOT);
        if (text.contains("READ ENTITIES") || text.contains("MODIFY ENTITIES") || text.contains("IN LOCAL MODE") || structure.contains("BEHAVIOR")) return "abap-rap";
        if (text.contains("SELECT ") || text.contains("FROM ") || text.contains("WHERE ") || text.contains("INNER JOIN") || text.contains("LEFT OUTER JOIN")) return "abap-sql";
        if (structure.contains("METHOD") || text.contains("METHOD ")) return "abap-method";
        if (structure.contains("DEFINITION")) return "abap-definition";
        return "abap-statement";
    }
}
