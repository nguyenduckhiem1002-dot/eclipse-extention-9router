package com.casla.eclipse.ai.api;

import java.util.Locale;
import java.util.Set;

public record CompletionSettings(
    int maxTokens,
    double temperature,
    int timeoutSeconds,
    int contextBefore,
    int contextAfter,
    boolean automaticSuggestion,
    int debounceMillis,
    String reasoningEffort
) {
    /** "default" omits the reasoning_effort param entirely, letting the endpoint pick. */
    public static final Set<String> REASONING_EFFORT_LEVELS =
        Set.of("default", "none", "minimal", "low", "medium", "high");

    public CompletionSettings {
        maxTokens = clamp(maxTokens, 16, 4096);
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        timeoutSeconds = clamp(timeoutSeconds, 3, 180);
        contextBefore = clamp(contextBefore, 500, 100_000);
        contextAfter = clamp(contextAfter, 0, 50_000);
        debounceMillis = clamp(debounceMillis, 200, 5000);
        reasoningEffort = normalizeReasoningEffort(reasoningEffort);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeReasoningEffort(String value) {
        String cleaned = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return REASONING_EFFORT_LEVELS.contains(cleaned) ? cleaned : "default";
    }
}
