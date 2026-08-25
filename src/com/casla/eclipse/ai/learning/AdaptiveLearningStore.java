package com.casla.eclipse.ai.learning;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.learning.CompletionFeedbackTracker.FeedbackEvent;

/**
 * Local-only adaptive memory. It stores aggregate style and feedback metrics,
 * never source code, prompts, or generated completion text.
 */
public final class AdaptiveLearningStore {
    private static final AdaptiveLearningStore INSTANCE = new AdaptiveLearningStore();

    private final ProjectStyleProfile styleProfile = new ProjectStyleProfile();
    private final CompletionFeedbackStats totalFeedback = new CompletionFeedbackStats();
    private final Map<String, CompletionFeedbackStats> feedbackByModel = new HashMap<>();
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

    public synchronized void recordFeedback(String model, FeedbackEvent event) {
        ensureLoaded();
        apply(totalFeedback, event);
        if (model != null && !model.isBlank()) {
            apply(feedbackByModel.computeIfAbsent(model, ignored -> new CompletionFeedbackStats()), event);
        }
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

    public synchronized CompletionFeedbackStats feedbackSnapshot() {
        ensureLoaded();
        return copy(totalFeedback);
    }

    public synchronized CompletionFeedbackStats modelFeedbackSnapshot(String model) {
        ensureLoaded();
        CompletionFeedbackStats stats = feedbackByModel.get(model);
        return stats == null ? new CompletionFeedbackStats() : copy(stats);
    }

    public synchronized void reset() {
        ensureLoaded();
        styleProfile.reset();
        totalFeedback.reset();
        feedbackByModel.clear();
        lastDocumentHashes.clear();
        CompletionFeedbackTracker.get().resetTransient();
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
            totalFeedback.load(properties, "feedback.total.");
            for (String model : decodeModelIndex(properties.getProperty("feedback.models", ""))) {
                CompletionFeedbackStats stats = new CompletionFeedbackStats();
                stats.load(properties, "feedback.model." + encodeModel(model) + ".");
                feedbackByModel.put(model, stats);
            }
        } catch (IOException | RuntimeException error) {
            AiPlugin.logInfo("Adaptive learning state could not be loaded; starting with an empty profile.");
        }
    }

    private void save() {
        Path path = storagePath();
        if (path == null) return;

        Properties properties = new Properties();
        styleProfile.store(properties);
        totalFeedback.store(properties, "feedback.total.");
        properties.setProperty("feedback.models", encodeModelIndex(feedbackByModel.keySet()));
        for (var entry : feedbackByModel.entrySet()) {
            entry.getValue().store(properties, "feedback.model." + encodeModel(entry.getKey()) + ".");
        }
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "Casla AI adaptive learning - aggregate metrics only");
            }
        } catch (IOException | RuntimeException error) {
            AiPlugin.logInfo("Adaptive learning state could not be persisted.");
        }
    }

    private static void apply(CompletionFeedbackStats stats, FeedbackEvent event) {
        switch (event) {
            case GENERATED -> stats.generated();
            case ACCEPT_FULL -> stats.acceptedFull();
            case ACCEPT_WORD -> stats.acceptedWord();
            case ACCEPT_LINE -> stats.acceptedLine();
            case TYPED_MATCH -> stats.typedMatch();
            case DISMISSED -> stats.dismissed();
            case SUPERSEDED -> stats.superseded();
            case EDITED_AFTER_ACCEPT -> stats.editedAfterAccept();
        }
    }

    private static CompletionFeedbackStats copy(CompletionFeedbackStats source) {
        Properties properties = new Properties();
        source.store(properties, "copy.");
        CompletionFeedbackStats copy = new CompletionFeedbackStats();
        copy.load(properties, "copy.");
        return copy;
    }

    private static String encodeModel(String model) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(model.getBytes(StandardCharsets.UTF_8));
    }

    private static String encodeModelIndex(Iterable<String> models) {
        StringBuilder builder = new StringBuilder();
        for (String model : models) {
            if (builder.length() > 0) builder.append(',');
            builder.append(encodeModel(model));
        }
        return builder.toString();
    }

    private static java.util.List<String> decodeModelIndex(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        java.util.List<String> models = new java.util.ArrayList<>();
        for (String encoded : value.split(",")) {
            if (encoded.isBlank()) continue;
            try {
                models.add(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // Ignore corrupt historical entries instead of failing the whole profile.
            }
        }
        return models;
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
