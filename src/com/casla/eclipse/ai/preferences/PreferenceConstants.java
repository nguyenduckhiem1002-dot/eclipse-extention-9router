package com.casla.eclipse.ai.preferences;

public final class PreferenceConstants {
    public static final String BASE_URL = "connection.baseUrl";
    public static final String MODEL_MODE = "model.mode";
    public static final String MANUAL_MODEL_ID = "model.manualId";
    public static final String LAST_RESOLVED_AUTO_ID = "model.lastResolvedAutoId";
    public static final String LAST_KNOWN_GOOD_MODEL = "model.lastKnownGood";
    public static final String MAX_TOKENS = "completion.maxTokens";
    public static final String TEMPERATURE = "completion.temperature";
    public static final String TIMEOUT_SECONDS = "completion.timeoutSeconds";
    public static final String CONTEXT_BEFORE = "completion.contextBefore";
    public static final String CONTEXT_AFTER = "completion.contextAfter";
    public static final String AUTOMATIC_SUGGESTION = "completion.automaticSuggestion";
    public static final String DEBOUNCE_MILLIS = "completion.debounceMillis";

    private PreferenceConstants() {}
}
