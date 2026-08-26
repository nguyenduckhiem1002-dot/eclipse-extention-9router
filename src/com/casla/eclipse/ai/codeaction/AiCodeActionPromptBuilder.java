package com.casla.eclipse.ai.codeaction;

import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.learning.AdaptiveLearningStore;

/** Builds replacement-oriented prompts for explicit AI code actions. */
public final class AiCodeActionPromptBuilder {
    private static final int CONTEXT_LIMIT = 2400;

    public record Prompt(String system, String user) {}

    public Prompt fix(CodeContext context, String target, String diagnostic) {
        String system = """
            You are an SAP ABAP code repair engine running inside Eclipse ADT.
            Return only the corrected ABAP source that must REPLACE <TARGET>.
            Do not return markdown fences, explanations, headings, or diff markers.
            Preserve business semantics unless the supplied diagnostic requires a change.
            Preserve identifiers that are valid in the current scope; do not invent APIs.
            Use canonical ABAP syntax and spacing (for example `a = b`, never `a =b`).
            Preserve selectors such as ->, =>, structure-component -, and CDS ~ without added spaces.
            For calls with multiple named parameters, prefer readable multi-line formatting and put EXPORTING/IMPORTING/CHANGING/RECEIVING on their own lines when that matches the surrounding source.
            Every complete ABAP statement must end with a period.
            If a safe correction cannot be determined from the supplied context, return the target unchanged.
            """.strip();

        StringBuilder user = new StringBuilder();
        if (diagnostic != null && !diagnostic.isBlank()) {
            user.append("ADT/compiler diagnostic:\n").append(limit(diagnostic, 1200)).append("\n\n");
        }
        if (context != null && context.structureHint() != null && !context.structureHint().isBlank()) {
            user.append("Structure: ").append(context.structureHint()).append("\n");
        }
        String style = context == null ? "" : AdaptiveLearningStore.get().promptHints(context);
        if (!style.isBlank()) user.append("Workspace style:\n").append(style).append("\n\n");

        user.append("Code before target:\n")
            .append(tail(context == null ? "" : context.beforeCursor(), CONTEXT_LIMIT))
            .append("\n\n<TARGET>\n")
            .append(target == null ? "" : target)
            .append("\n</TARGET>\n\nCode after target:\n")
            .append(head(context == null ? "" : context.afterCursor(), CONTEXT_LIMIT));
        return new Prompt(system, user.toString());
    }

    private static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max);
    }
    private static String tail(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(value.length() - max);
    }
    private static String head(String value, int max) {
        if (value == null || value.length() <= max) return value == null ? "" : value;
        return value.substring(0, max);
    }
}
