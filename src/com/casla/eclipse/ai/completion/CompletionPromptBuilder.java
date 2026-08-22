package com.casla.eclipse.ai.completion;

public final class CompletionPromptBuilder {
    private static final String ABAP_RULES = """

        ABAP-specific rules:
        - METHODS (plural) declares a method signature inside a CLASS ... DEFINITION section; it is never followed by a body or ENDMETHOD.
        - METHOD (singular) ... ENDMETHOD implements a method inside a CLASS ... IMPLEMENTATION section; it is never used inside a DEFINITION section.
        - Never insert a METHOD/ENDMETHOD implementation while completing inside a DEFINITION section, and never insert a METHODS declaration while completing inside an IMPLEMENTATION section.
        - Every ABAP statement ends with a period; chained statements share one keyword, a colon, and comma-separated clauses.
        - Complete only the current statement or declaration unless the surrounding code clearly continues into more lines.""";

    public record Prompt(String system, String user) {}

    public Prompt build(CodeContext context) {
        return new Prompt(buildSystem(context), buildUser(context));
    }

    private String buildSystem(CodeContext context) {
        String base = """
            You are an inline %s code completion engine.
            Return only the code that should be inserted at <CURSOR>.
            Do not return markdown fences or explanations.
            Do not repeat code before or after the cursor.
            Preserve indentation, naming style, nullability, and error-handling conventions.
            Prefer the smallest useful completion.""".formatted(context.language());
        String rules = "ABAP".equals(context.language()) ? ABAP_RULES : "";
        return (base + rules).strip();
    }

    /**
     * Omits empty fields instead of always rendering "Project:\nPackage:\n..."
     * -- ADT does not expose project/package/imports the way JDT does, and a
     * block of empty labels is pure noise the model has to read past.
     */
    private String buildUser(CodeContext context) {
        StringBuilder user = new StringBuilder();
        user.append("Language: ").append(context.language()).append('\n');
        appendIfPresent(user, "Project", context.projectName());
        appendIfPresent(user, "File", context.filePath());
        appendIfPresent(user, "Package", context.packageName());
        appendIfPresent(user, "Structure", context.structureHint());
        if (!context.imports().isBlank()) {
            user.append("\nImports:\n").append(context.imports()).append('\n');
        }
        user.append("\nCode before cursor:\n").append(context.beforeCursor());
        user.append("\n\n<CURSOR>\n\n");
        user.append("Code after cursor:\n").append(context.afterCursor());
        return user.toString();
    }

    private static void appendIfPresent(StringBuilder builder, String label, String value) {
        if (!value.isBlank()) builder.append(label).append(": ").append(value).append('\n');
    }
}
