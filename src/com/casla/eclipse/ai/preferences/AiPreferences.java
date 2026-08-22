package com.casla.eclipse.ai.preferences;

import org.eclipse.jface.preference.IPreferenceStore;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ModelPreference;
import com.casla.eclipse.ai.api.ModelSelectionMode;

public final class AiPreferences {
    private final SecureApiKeyStore secureStore = new SecureApiKeyStore();

    public ConnectionConfig connection() {
        return new ConnectionConfig(
            store().getString(PreferenceConstants.BASE_URL),
            secureStore.read()
        );
    }

    public ModelPreference modelPreference() {
        return new ModelPreference(
            parseMode(store().getString(PreferenceConstants.MODEL_MODE)),
            store().getString(PreferenceConstants.MANUAL_MODEL_ID),
            store().getString(PreferenceConstants.LAST_RESOLVED_AUTO_ID),
            store().getString(PreferenceConstants.LAST_KNOWN_GOOD_MODEL)
        );
    }

    public CompletionSettings completionSettings() {
        return new CompletionSettings(
            store().getInt(PreferenceConstants.MAX_TOKENS),
            parseDouble(store().getString(PreferenceConstants.TEMPERATURE), 0.1),
            store().getInt(PreferenceConstants.TIMEOUT_SECONDS),
            store().getInt(PreferenceConstants.CONTEXT_BEFORE),
            store().getInt(PreferenceConstants.CONTEXT_AFTER),
            store().getBoolean(PreferenceConstants.AUTOMATIC_SUGGESTION),
            store().getInt(PreferenceConstants.DEBOUNCE_MILLIS)
        );
    }

    public void saveConnection(ConnectionConfig connection) throws Exception {
        store().setValue(PreferenceConstants.BASE_URL, connection.baseUrl());
        secureStore.write(connection.apiKey());
    }

    public void saveModelPreference(ModelPreference preference) {
        store().setValue(PreferenceConstants.MODEL_MODE, preference.mode().name());
        store().setValue(PreferenceConstants.MANUAL_MODEL_ID, preference.manualModelId());
        store().setValue(PreferenceConstants.LAST_RESOLVED_AUTO_ID, preference.lastResolvedAutoId());
        store().setValue(PreferenceConstants.LAST_KNOWN_GOOD_MODEL, preference.lastKnownGoodModel());
    }

    public void saveCompletionSettings(CompletionSettings settings) {
        store().setValue(PreferenceConstants.MAX_TOKENS, settings.maxTokens());
        store().setValue(PreferenceConstants.TEMPERATURE, Double.toString(settings.temperature()));
        store().setValue(PreferenceConstants.TIMEOUT_SECONDS, settings.timeoutSeconds());
        store().setValue(PreferenceConstants.CONTEXT_BEFORE, settings.contextBefore());
        store().setValue(PreferenceConstants.CONTEXT_AFTER, settings.contextAfter());
        store().setValue(PreferenceConstants.AUTOMATIC_SUGGESTION, settings.automaticSuggestion());
        store().setValue(PreferenceConstants.DEBOUNCE_MILLIS, settings.debounceMillis());
    }

    public void saveLastResolvedAutoModel(String modelId) {
        store().setValue(PreferenceConstants.LAST_RESOLVED_AUTO_ID, clean(modelId));
    }

    public void saveLastKnownGoodModel(String modelId) {
        store().setValue(PreferenceConstants.LAST_KNOWN_GOOD_MODEL, clean(modelId));
    }

    public void clearAutoModelCache() {
        store().setValue(PreferenceConstants.LAST_RESOLVED_AUTO_ID, "");
        store().setValue(PreferenceConstants.LAST_KNOWN_GOOD_MODEL, "");
    }

    private static IPreferenceStore store() {
        return AiPlugin.getDefault().getPreferenceStore();
    }

    private static ModelSelectionMode parseMode(String value) {
        try {
            return ModelSelectionMode.valueOf(value);
        } catch (RuntimeException error) {
            return ModelSelectionMode.AUTO;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
