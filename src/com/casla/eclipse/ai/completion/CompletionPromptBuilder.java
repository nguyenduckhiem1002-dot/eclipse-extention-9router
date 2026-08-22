package com.casla.eclipse.ai.completion;

public final class CompletionPromptBuilder {
    public record Prompt(String system, String user) {}

    public Prompt build(CodeContext context) {
        String system = """
            You are an inline Java code completion engine.
            Return only the code that should be inserted at <CURSOR>.
            Do not return markdown fences or explanations.
            Do not repeat code before or after the cursor.
            Preserve indentation, naming style, nullability, and error-handling conventions.
            Prefer the smallest useful completion.
            """.strip();

        String user = """
            Project: %s
            Language: %s
            File: %s
            Package: %s

            Imports:
            %s

            Code before cursor:
            %s

            <CURSOR>

            Code after cursor:
            %s
            """.formatted(
                context.projectName(),
                context.language(),
                context.filePath(),
                context.packageName(),
                context.imports(),
                context.beforeCursor(),
                context.afterCursor()
            );
        return new Prompt(system, user);
    }
}
