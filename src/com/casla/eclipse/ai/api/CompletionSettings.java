package com.casla.eclipse.ai.api;

public record CompletionSettings(
    int maxTokens,
    double temperature,
    int timeoutSeconds,
    int contextBefore,
    int contextAfter,
    boolean automaticSuggestion,
    int debounceMillis
) {
    public CompletionSettings {
        maxTokens = clamp(maxTokens, 16, 4096);
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        timeoutSeconds = clamp(timeoutSeconds, 3, 180);
        contextBefore = clamp(contextBefore, 500, 100_000);
        contextAfter = clamp(contextAfter, 0, 50_000);
        debounceMillis = clamp(debounceMillis, 200, 5000);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
