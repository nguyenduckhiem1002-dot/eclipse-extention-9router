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
        // A fast, low/no-thinking model by default: Ctrl+Space and ghost text are
        // latency-sensitive, unlike a chat turn. Auto mode's resolver re-ranks the
        // live catalog with the same bias (see ModelResolver), so this id is only
        // the Manual-mode fallback and the seed before any catalog is loaded.
        // Plain "-low" rather than "extra-low": the extra-low tier was tried
        // first and didn't reliably follow "code only, no explanations" --
        // see CompletionSanitizer's prose-rejection guard, added after it
        // leaked plain-English explanations into completions.
        store.setDefault(PreferenceConstants.MANUAL_MODEL_ID, "ag/gemini-3.5-flash-low");
        store.setDefault(PreferenceConstants.LAST_RESOLVED_AUTO_ID, "");
        store.setDefault(PreferenceConstants.LAST_KNOWN_GOOD_MODEL, "");
        store.setDefault(PreferenceConstants.MAX_TOKENS, 256);
        store.setDefault(PreferenceConstants.TEMPERATURE, "0.1");
        store.setDefault(PreferenceConstants.TIMEOUT_SECONDS, 30);
        store.setDefault(PreferenceConstants.CONTEXT_BEFORE, 6000);
        store.setDefault(PreferenceConstants.CONTEXT_AFTER, 2000);
        store.setDefault(PreferenceConstants.AUTOMATIC_SUGGESTION, true);
        store.setDefault(PreferenceConstants.DEBOUNCE_MILLIS, 500);
        store.setDefault(PreferenceConstants.REASONING_EFFORT, "default");
    }
}
