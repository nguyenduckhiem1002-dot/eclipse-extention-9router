package com.casla.eclipse.ai.learning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;

import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.RelatedFileCollector;

/** Persistent bounded index of lightweight ABAP object skeletons. */
public final class ObservedAbapObjectIndex {
    public record ObjectEntry(String objectKey, String skeleton, int sourceHash, long updatedAt) {}

    private static final int DEFAULT_MAX_OBJECTS = 250;
    private static final int MAX_SKELETON_CHARS = 1800;
    private final LinkedHashMap<String, ObjectEntry> entries = new LinkedHashMap<>();
    private int maxObjects = DEFAULT_MAX_OBJECTS;

    public synchronized void setMaxObjects(int value) {
        maxObjects = Math.max(20, Math.min(1000, value));
        trim();
    }

    public synchronized void observe(String objectKey, String source) {
        String key = clean(objectKey);
        if (key.isBlank() || source == null || source.isBlank()) return;
        int hash = source.hashCode();
        ObjectEntry previous = entries.get(key);
        if (previous != null && previous.sourceHash() == hash) return;
        String skeleton = RelatedFileCollector.extractAbapSkeleton(source);
        if (skeleton.isBlank()) return;
        if (skeleton.length() > MAX_SKELETON_CHARS) skeleton = skeleton.substring(0, MAX_SKELETON_CHARS);
        entries.put(key, new ObjectEntry(key, skeleton, hash, System.currentTimeMillis()));
        trim();
    }

    public synchronized List<ObjectEntry> retrieve(CodeContext context, int limit) {
        if (context == null || entries.isEmpty()) return List.of();
        Set<String> query = tokens(context.beforeCursor() + " " + context.structureHint());
        String current = clean(context.filePath());
        return entries.values().stream()
            .filter(entry -> current.isBlank() || !sameObject(entry.objectKey(), current))
            .map(entry -> Map.entry(entry, score(entry, query)))
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<ObjectEntry, Integer>comparingByValue(Comparator.reverseOrder()))
            .limit(Math.max(0, Math.min(4, limit)))
            .map(Map.Entry::getKey)
            .toList();
    }

    public synchronized int size() { return entries.size(); }
    public synchronized void reset() { entries.clear(); }

    public synchronized void store(Properties properties) {
        properties.setProperty("objects.max", Integer.toString(maxObjects));
        properties.setProperty("objects.count", Integer.toString(entries.size()));
        int i = 0;
        for (ObjectEntry entry : entries.values()) {
            String prefix = "objects." + i++ + ".";
            properties.setProperty(prefix + "key", encode(entry.objectKey()));
            properties.setProperty(prefix + "skeleton", encode(entry.skeleton()));
            properties.setProperty(prefix + "hash", Integer.toString(entry.sourceHash()));
            properties.setProperty(prefix + "updated", Long.toString(entry.updatedAt()));
        }
    }

    public synchronized void load(Properties properties) {
        entries.clear();
        maxObjects = parseInt(properties.getProperty("objects.max"), DEFAULT_MAX_OBJECTS);
        int count = Math.max(0, Math.min(1000, parseInt(properties.getProperty("objects.count"), 0)));
        for (int i = 0; i < count; i++) {
            String prefix = "objects." + i + ".";
            String key = decode(properties.getProperty(prefix + "key", ""));
            String skeleton = decode(properties.getProperty(prefix + "skeleton", ""));
            if (key.isBlank() || skeleton.isBlank()) continue;
            entries.put(key, new ObjectEntry(
                key,
                skeleton,
                parseInt(properties.getProperty(prefix + "hash"), 0),
                parseLong(properties.getProperty(prefix + "updated"), System.currentTimeMillis())
            ));
        }
        trim();
    }

    private static int score(ObjectEntry entry, Set<String> query) {
        Set<String> objectTokens = tokens(entry.objectKey() + " " + entry.skeleton());
        int score = 0;
        for (String token : query) if (objectTokens.contains(token)) score += 2;
        long ageDays = Math.max(0L, (System.currentTimeMillis() - entry.updatedAt()) / 86_400_000L);
        score -= (int) Math.min(12L, ageDays / 30L);
        return score;
    }

    private void trim() {
        if (entries.size() <= maxObjects) return;
        List<ObjectEntry> ordered = new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparingLong(ObjectEntry::updatedAt));
        int remove = entries.size() - maxObjects;
        for (int i = 0; i < remove; i++) entries.remove(ordered.get(i).objectKey());
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) if (token.length() >= 3) result.add(token);
        return result;
    }
    private static boolean sameObject(String a, String b) { return a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a) || a.endsWith("\\" + b) || b.endsWith("\\" + a); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(clean(value).getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) { try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); } catch (IllegalArgumentException invalid) { return ""; } }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (RuntimeException e) { return fallback; } }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (RuntimeException e) { return fallback; } }
}
