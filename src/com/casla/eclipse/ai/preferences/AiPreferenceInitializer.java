package com.casla.eclipse.ai.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.ModelSelectionMode;

public final class AiPreferenceInitializer extends AbstractPreferenceInitializer {
    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = AiPlugin.getDefault().getPreferenceStore();
        store.setDefault(PreferenceConstants.BASE_URL, "http://localhost:20128/v1");
        store.setDefault(PreferenceConstants.MODEL_MODE, ModelSelectionMode.AUTO.name());
        store.setDefault(PreferenceConstants.MANUAL_MODEL_ID, "ag/claude-sonnet-4-6");
        store.setDefault(PreferenceConstants.LAST_RESOLVED_AUTO_ID, "");
        store.setDefault(PreferenceConstants.LAST_KNOWN_GOOD_MODEL, "");
        store.setDefault(PreferenceConstants.MAX_TOKENS, 128);
        store.setDefault(PreferenceConstants.TEMPERATURE, "0.1");
        store.setDefault(PreferenceConstants.TIMEOUT_SECONDS, 30);
        store.setDefault(PreferenceConstants.CONTEXT_BEFORE, 6000);
        store.setDefault(PreferenceConstants.CONTEXT_AFTER, 2000);
        store.setDefault(PreferenceConstants.AUTOMATIC_SUGGESTION, false);
        store.setDefault(PreferenceConstants.DEBOUNCE_MILLIS, 500);
    }
}
