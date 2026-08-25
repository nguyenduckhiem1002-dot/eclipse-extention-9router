package com.casla.eclipse.ai.learning;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.completion.CodeContext;

/**
 * Local-only adaptive memory. It stores aggregate style metrics, never source
 * code or prompts. Persistence lives in the Eclipse plugin state directory.
 */
public final class AdaptiveLearningStore {
    private static final AdaptiveLearningStore INSTANCE = new AdaptiveLearningStore();

    private final ProjectStyleProfile styleProfile = new ProjectStyleProfile();
    private final Map<String, Integer> lastDocumentHashes = new HashMap<>();
    private boolean loaded;

    private AdaptiveLearningStore() {}

    public static AdaptiveLearningStore get() {
        return INSTANCE;
    }

    public synchronized void observeDocument(String objectKey, String language, String source) {
        ensureLoaded();
        if (!"ABAP".equalsIgnoreCase(language) || source == null || source.isBlank()) return;

        String key = objectKey == null ? "" : objectKey;
        int hash = source.hashCode();
        Integer previous = lastDocumentHashes.put(key, hash);
        if (previous != null && previous == hash) return;

        styleProfile.observeAbap(source);
        save();
    }

    public synchronized String promptHints(CodeContext context) {
        ensureLoaded();
        if (context == null || !"ABAP".equalsIgnoreCase(context.language())) return "";
        return styleProfile.promptHints();
    }

    public synchronized int observationCount() {
        ensureLoaded();
        return styleProfile.observations();
    }

    public synchronized void reset() {
        ensureLoaded();
        styleProfile.reset();
        lastDocumentHashes.clear();
        save();
    }

    private void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path path = storagePath();
        if (path == null || !Files.isRegularFile(path)) return;

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
            styleProfile.load(properties);
        } catch (IOException | RuntimeException error) {
            AiPlugin.logInfo("Adaptive learning state could not be loaded; starting with an empty profile.");
        }
    }

    private void save() {
        Path path = storagePath();
        if (path == null) return;

        Properties properties = new Properties();
        styleProfile.store(properties);
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "Casla AI adaptive learning - aggregate style metrics only");
            }
        } catch (IOException | RuntimeException error) {
            AiPlugin.logInfo("Adaptive learning state could not be persisted.");
        }
    }

    private static Path storagePath() {
        AiPlugin plugin = AiPlugin.getDefault();
        if (plugin == null) return null;
        try {
            return plugin.getStateLocation().append("adaptive-learning.properties").toFile().toPath();
        } catch (RuntimeException unavailable) {
            return null;
        }
    }
}
