package com.casla.eclipse.ai.completion;

import com.casla.eclipse.ai.learning.AdaptiveLearningStore;

public final class CompletionPromptBuilder {
    private static final String ABAP_RULES = """

        ABAP-specific rules:
        - METHODS (plural) declares a method signature inside a CLASS ... DEFINITION section; it is never followed by a body or ENDMETHOD.
        - METHOD (singular) ... ENDMETHOD implements a method inside a CLASS ... IMPLEMENTATION section; it is never used inside a DEFINITION section.
        - Never insert a METHOD/ENDMETHOD implementation while completing inside a DEFINITION section, and never insert a METHODS declaration while completing inside an IMPLEMENTATION section.
        - Every ABAP statement ends with a period; chained statements share one keyword, a colon, and comma-separated clauses.
        - Use canonical ABAP spacing around assignment/comparison operators: write `a = b`, not `a =b` or `a= b`.
        - Do not add spaces inside selectors such as `lo_obj->method`, `cl_type=>method`, `ls_row-field`, or `_assoc~field`.
        - For method calls with multiple named parameters, prefer a readable multi-line layout. Put EXPORTING/IMPORTING/CHANGING/RECEIVING on their own lines when used, indent parameters below them, and keep `).` on its own line when the surrounding code follows that style.
        - Keep genuinely short single-parameter calls on one line when that matches surrounding code.
        - Complete only the current statement or declaration unless the surrounding code clearly continues into more lines.
        - Learned examples are hints, not source-of-truth: adapt them to current scope and never invent missing identifiers.""";

    public record Prompt(String system, String user) {}

    public Prompt build(CodeContext context) { return new Prompt(buildSystem(context), buildUser(context)); }

    private String buildSystem(CodeContext context) {
        String roleDesc = switch (context.cursorContext()) {
            case JAVADOC -> "You are an inline " + context.language() + " documentation completion engine.\nThe cursor is inside a Javadoc block. Complete the Javadoc description, tags, or sentences accurately.";
            case LINE_COMMENT, BLOCK_COMMENT -> "You are an inline " + context.language() + " comment completion engine.\nThe cursor is inside a comment. Complete the natural language explanation or comment text.";
            case STRING_LITERAL -> "You are an inline " + context.language() + " text completion engine.\nThe cursor is inside a string literal. Return only the string text to be inserted.";
            case CODE -> "You are an inline " + context.language() + " code completion engine.";
        };
        String base = """
            %s
            Return only the code or text that should be inserted directly at <CURSOR>.
            Do not return markdown fences (```) or explanations.
            Do not repeat code before or after the cursor.
            Preserve indentation, naming style, nullability, and surrounding conventions.
            Use relevant context from related files when referencing types and methods.
            Never write a natural-language sentence describing the code. If you cannot produce valid code, return nothing.""".formatted(roleDesc);
        return (base + ("ABAP".equals(context.language()) ? ABAP_RULES : "")).strip();
    }

    private String buildUser(CodeContext context) {
        StringBuilder user = new StringBuilder();
        user.append("Language: ").append(context.language()).append('\n');
        appendIfPresent(user, "Project", context.projectName());
        appendIfPresent(user, "File", context.filePath());
        appendIfPresent(user, "Package", context.packageName());
        appendIfPresent(user, "Structure", context.structureHint());

        AdaptiveLearningStore memory = AdaptiveLearningStore.get();
        String learnedStyle = memory.promptHints(context);
        if (!learnedStyle.isBlank()) {
            user.append("\nLearned coding preferences from this workspace:\n").append(learnedStyle).append('\n');
        }

        var examples = memory.acceptedExamples(context, 2);
        if (!examples.isEmpty()) {
            user.append("\nPreviously accepted patterns from this workspace (adapt, do not copy blindly):\n");
            int i = 1;
            for (var example : examples) {
                user.append("--- accepted pattern ").append(i++).append(" ---\n");
                user.append(example.snippet()).append("\n");
            }
        }

        var rememberedObjects = memory.relatedObjects(context, 3);
        if (!rememberedObjects.isEmpty()) {
            user.append("\nRemembered ABAP object skeletons:\n");
            for (var object : rememberedObjects) {
                user.append("--- ").append(object.objectKey()).append(" ---\n");
                user.append(object.skeleton()).append("\n");
            }
        }

        if (!context.imports().isBlank()) user.append("\nImports:\n").append(context.imports()).append('\n');
        if (context.relatedFiles() != null && !context.relatedFiles().isEmpty()) {
            user.append("\nRelated context:\n");
            for (var related : context.relatedFiles()) {
                user.append("--- ").append(related.path()).append(" ---\n");
                user.append(related.summary()).append("\n\n");
            }
        }
        user.append("Code before cursor:\n").append(context.beforeCursor());
        user.append("\n\n<CURSOR>\n\n");
        user.append("Code after cursor:\n").append(context.afterCursor());
        return user.toString();
    }

    private static void appendIfPresent(StringBuilder builder, String label, String value) {
        if (value != null && !value.isBlank()) builder.append(label).append(": ").append(value).append('\n');
    }
}
