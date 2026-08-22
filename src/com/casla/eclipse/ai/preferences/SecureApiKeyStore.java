package com.casla.eclipse.ai.preferences;

import java.io.IOException;

import org.eclipse.equinox.security.storage.ISecurePreferences;
import org.eclipse.equinox.security.storage.SecurePreferencesFactory;
import org.eclipse.equinox.security.storage.StorageException;

import com.casla.eclipse.ai.AiPlugin;

public final class SecureApiKeyStore {
    private static final String NODE = "/com.casla.eclipse.ai/connection";
    private static final String KEY = "apiKey";

    public String read() {
        try {
            return node().get(KEY, "");
        } catch (StorageException error) {
            AiPlugin.logError("Could not read the API key from Secure Storage.", error);
            return "";
        }
    }

    public void write(String apiKey) throws StorageException, IOException {
        ISecurePreferences preferences = node();
        preferences.put(KEY, apiKey == null ? "" : apiKey.trim(), true);
        preferences.flush();
    }

    private static ISecurePreferences node() {
        return SecurePreferencesFactory.getDefault().node(NODE);
    }
}
