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

/** Local-only adaptive memory persisted under the Eclipse plugin state directory. */
public final class AdaptiveLearningStore {
    private static final AdaptiveLearningStore INSTANCE = new AdaptiveLearningStore();

    private final ProjectStyleProfile styleProfile = new ProjectStyleProfile();
    private final CompletionFeedbackStats totalFeedback = new CompletionFeedbackStats();
    private final Map<String, CompletionFeedbackStats> feedbackByModel = new HashMap<>();
    private final Map<String, CompletionFeedbackStats> feedbackByModelContext = new HashMap<>();
    private final Map<String, Integer> lastDocumentHashes = new HashMap<>();
    private final AcceptedExampleMemory acceptedExamples = new AcceptedExampleMemory();
    private final ObservedAbapObjectIndex objectIndex = new ObservedAbapObjectIndex();
    private boolean loaded;
    private boolean paused;
    private boolean nextEditEnabled = true;

    private AdaptiveLearningStore() {}
    public static AdaptiveLearningStore get() { return INSTANCE; }

    public synchronized void observeDocument(String objectKey, String language, String source) {
        ensureLoaded();
        if (paused || !"ABAP".equalsIgnoreCase(language) || source == null || source.isBlank()) return;
        String key = objectKey == null ? "" : objectKey;
        int hash = source.hashCode();
        Integer previous = lastDocumentHashes.put(key, hash);
        if (previous != null && previous == hash) return;
        styleProfile.observeAbap(source);
        objectIndex.observe(key, source);
        save();
    }

    public synchronized void recordFeedback(String model, String contextBucket, FeedbackEvent event) {
        ensureLoaded();
        if (paused) return;
        apply(totalFeedback, event);
        String cleanModel = clean(model);
        String cleanBucket = clean(contextBucket);
        if (!cleanModel.isBlank()) {
            apply(feedbackByModel.computeIfAbsent(cleanModel, ignored -> new CompletionFeedbackStats()), event);
            if (!cleanBucket.isBlank()) {
                apply(feedbackByModelContext.computeIfAbsent(cleanModel + "\u001f" + cleanBucket, ignored -> new CompletionFeedbackStats()), event);
            }
        }
        save();
    }

    public synchronized void recordFeedback(String model, FeedbackEvent event) { recordFeedback(model, "", event); }

    public synchronized void rememberAcceptedExample(String objectKey, String structureHint, String completion, String model) {
        rememberAcceptedExample(objectKey, structureHint, "unknown", "", completion, model);
    }

    public synchronized void rememberAcceptedExample(
        String objectKey,
        String structureHint,
        String contextBucket,
        String contextShape,
        String completion,
        String model
    ) {
        ensureLoaded();
        if (paused) return;
        acceptedExamples.remember(objectKey, structureHint, contextBucket, contextShape, completion, model);
        save();
    }

    public synchronized String promptHints(CodeContext context) {
        ensureLoaded();
        if (context == null || !"ABAP".equalsIgnoreCase(context.language())) return "";
        return styleProfile.promptHints();
    }

    public synchronized java.util.List<AcceptedExampleMemory.Example> acceptedExamples(CodeContext context, int limit) {
        ensureLoaded();
        return paused ? java.util.List.of() : acceptedExamples.retrieve(context, limit);
    }

    public synchronized java.util.List<ObservedAbapObjectIndex.ObjectEntry> relatedObjects(CodeContext context, int limit) {
        ensureLoaded();
        return paused ? java.util.List.of() : objectIndex.retrieve(context, limit);
    }

    public synchronized int observationCount() { ensureLoaded(); return styleProfile.observations(); }
    public synchronized int exampleCount() { ensureLoaded(); return acceptedExamples.size(); }
    public synchronized int objectCount() { ensureLoaded(); return objectIndex.size(); }
    public synchronized boolean isPaused() { ensureLoaded(); return paused; }
    public synchronized void setPaused(boolean value) { ensureLoaded(); paused = value; save(); }
    public synchronized boolean isNextEditEnabled() { ensureLoaded(); return nextEditEnabled && !paused; }
    public synchronized void setNextEditEnabled(boolean value) { ensureLoaded(); nextEditEnabled = value; save(); }
    public synchronized void setMemoryLimit(int value) { ensureLoaded(); acceptedExamples.setMaxExamples(value); objectIndex.setMaxObjects(value); save(); }

    public synchronized CompletionFeedbackStats feedbackSnapshot() { ensureLoaded(); return copy(totalFeedback); }
    public synchronized CompletionFeedbackStats modelFeedbackSnapshot(String model) {
        ensureLoaded();
        CompletionFeedbackStats stats = feedbackByModel.get(model);
        return stats == null ? new CompletionFeedbackStats() : copy(stats);
    }
    public synchronized CompletionFeedbackStats modelContextFeedbackSnapshot(String model, String bucket) {
        ensureLoaded();
        CompletionFeedbackStats stats = feedbackByModelContext.get(clean(model) + "\u001f" + clean(bucket));
        return stats == null ? new CompletionFeedbackStats() : copy(stats);
    }

    public synchronized String diagnosticsSummary() {
        ensureLoaded();
        CompletionFeedbackStats stats = totalFeedback;
        return "Adaptive learning: " + (paused ? "paused" : "active")
            + " | next-edit=" + (nextEditEnabled ? "on" : "off")
            + " | observations=" + styleProfile.observations()
            + " | examples=" + acceptedExamples.size()
            + " | objects=" + objectIndex.size()
            + " | generated=" + stats.generatedCount()
            + " | acceptance=" + Math.round(stats.acceptanceRate() * 100.0) + "%"
            + " | edited-after-accept=" + Math.round(stats.editAfterAcceptRate() * 100.0) + "%";
    }

    public synchronized void resetExamples() { ensureLoaded(); acceptedExamples.reset(); save(); }
    public synchronized void resetObjects() { ensureLoaded(); objectIndex.reset(); save(); }
    public synchronized void resetFeedback() { ensureLoaded(); totalFeedback.reset(); feedbackByModel.clear(); feedbackByModelContext.clear(); save(); }

    /** Resets only persisted/aggregate state. UI/transient trackers reset separately to avoid lock-order inversion. */
    public synchronized void reset() {
        ensureLoaded();
        styleProfile.reset();
        totalFeedback.reset();
        feedbackByModel.clear();
        feedbackByModelContext.clear();
        acceptedExamples.reset();
        objectIndex.reset();
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
            totalFeedback.load(properties, "feedback.total.");
            paused = Boolean.parseBoolean(properties.getProperty("learning.paused", "false"));
            nextEditEnabled = Boolean.parseBoolean(properties.getProperty("learning.nextEditEnabled", "true"));
            acceptedExamples.load(properties);
            objectIndex.load(properties);
            for (String model : decodeModelIndex(properties.getProperty("feedback.models", ""))) {
                CompletionFeedbackStats stats = new CompletionFeedbackStats();
                stats.load(properties, "feedback.model." + encode(model) + ".");
                feedbackByModel.put(model, stats);
            }
            for (String key : decodeModelIndex(properties.getProperty("feedback.modelContexts", ""))) {
                CompletionFeedbackStats stats = new CompletionFeedbackStats();
                stats.load(properties, "feedback.context." + encode(key) + ".");
                feedbackByModelContext.put(key, stats);
            }
        } catch (IOException | RuntimeException error) {
            AiPlugin.logInfo("Adaptive learning state could not be loaded; starting with partial/empty memory.");
        }
    }

    private void save() {
        Path path = storagePath();
        if (path == null) return;
        Properties properties = new Properties();
        styleProfile.store(properties);
        totalFeedback.store(properties, "feedback.total.");
        properties.setProperty("learning.paused", Boolean.toString(paused));
        properties.setProperty("learning.nextEditEnabled", Boolean.toString(nextEditEnabled));
        acceptedExamples.store(properties);
        objectIndex.store(properties);
        properties.setProperty("feedback.models", encodeModelIndex(feedbackByModel.keySet()));
        for (var entry : feedbackByModel.entrySet()) entry.getValue().store(properties, "feedback.model." + encode(entry.getKey()) + ".");
        properties.setProperty("feedback.modelContexts", encodeModelIndex(feedbackByModelContext.keySet()));
        for (var entry : feedbackByModelContext.entrySet()) entry.getValue().store(properties, "feedback.context." + encode(entry.getKey()) + ".");
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream output = Files.newOutputStream(path)) {
                properties.store(output, "Casla AI adaptive learning - local workspace memory");
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
        Properties p = new Properties();
        source.store(p, "copy.");
        CompletionFeedbackStats result = new CompletionFeedbackStats();
        result.load(p, "copy.");
        return result;
    }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(clean(value).getBytes(StandardCharsets.UTF_8)); }
    private static String encodeModelIndex(Iterable<String> values) { StringBuilder b = new StringBuilder(); for (String value : values) { if (b.length() > 0) b.append(','); b.append(encode(value)); } return b.toString(); }
    private static java.util.List<String> decodeModelIndex(String value) {
        if (value == null || value.isBlank()) return java.util.List.of();
        java.util.List<String> result = new java.util.ArrayList<>();
        for (String encoded : value.split(",")) {
            if (encoded.isBlank()) continue;
            try { result.add(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8)); }
            catch (IllegalArgumentException ignored) {}
        }
        return result;
    }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static Path storagePath() {
        AiPlugin plugin = AiPlugin.getDefault();
        if (plugin == null) return null;
        try { return plugin.getStateLocation().append("adaptive-learning.properties").toFile().toPath(); }
        catch (RuntimeException unavailable) { return null; }
    }
}
