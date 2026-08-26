package com.casla.eclipse.ai.learning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.casla.eclipse.ai.completion.CodeContext;

/** Bounded local-only memory of successful completion patterns. */
public final class AcceptedExampleMemory {
    public record Example(
        String objectKey,
        String structureHint,
        String contextBucket,
        String shapeSignature,
        String snippet,
        String model,
        long timestamp,
        int uses
    ) {}

    private static final int DEFAULT_MAX = 300;
    private static final int MAX_SNIPPET_CHARS = 900;
    private final LinkedHashMap<String, Example> examples = new LinkedHashMap<>();
    private int maxExamples = DEFAULT_MAX;

    public synchronized void setMaxExamples(int value) {
        maxExamples = Math.max(20, Math.min(1000, value));
        trim();
    }

    public synchronized void remember(String objectKey, String structureHint, String completion, String model) {
        remember(objectKey, structureHint, "unknown", AbapStructuralSignature.of(completion), completion, model);
    }

    public synchronized void remember(
        String objectKey,
        String structureHint,
        String contextBucket,
        String contextShape,
        String completion,
        String model
    ) {
        String normalized = normalize(completion);
        if (normalized.isBlank()) return;
        String bucket = clean(contextBucket).isBlank() ? "unknown" : clean(contextBucket);
        String shape = clean(contextShape);
        if (shape.isBlank()) shape = AbapStructuralSignature.of(normalized);
        String key = fingerprint(normalized + "|" + clean(structureHint) + "|" + bucket + "|" + shape);
        Example previous = examples.get(key);
        int uses = previous == null ? 1 : previous.uses() + 1;
        examples.put(key, new Example(
            clean(objectKey), clean(structureHint), bucket, shape,
            normalized, clean(model), System.currentTimeMillis(), uses
        ));
        trim();
    }

    public synchronized List<Example> retrieve(CodeContext context, int limit) {
        if (context == null || examples.isEmpty()) return List.of();
        Set<String> queryTokens = tokens(context.beforeCursor() + " " + context.structureHint());
        String queryBucket = CompletionContextClassifier.bucket(context);
        String queryShape = AbapStructuralSignature.of(context.beforeCursor());
        Map<String, Integer> documentFrequency = documentFrequency();
        int corpusSize = examples.size();

        return examples.values().stream()
            .map(example -> Map.entry(example, score(
                example, context, queryTokens, queryBucket, queryShape, documentFrequency, corpusSize
            )))
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<Example, Double>comparingByValue(Comparator.reverseOrder()))
            .limit(Math.max(0, Math.min(3, limit)))
            .map(Map.Entry::getKey)
            .toList();
    }

    public synchronized int size() { return examples.size(); }
    public synchronized void reset() { examples.clear(); }

    public synchronized void store(Properties properties) {
        properties.setProperty("examples.max", Integer.toString(maxExamples));
        properties.setProperty("examples.count", Integer.toString(examples.size()));
        int i = 0;
        for (Example example : examples.values()) {
            String prefix = "examples." + i++ + ".";
            properties.setProperty(prefix + "object", encode(example.objectKey()));
            properties.setProperty(prefix + "structure", encode(example.structureHint()));
            properties.setProperty(prefix + "bucket", encode(example.contextBucket()));
            properties.setProperty(prefix + "shape", encode(example.shapeSignature()));
            properties.setProperty(prefix + "snippet", encode(example.snippet()));
            properties.setProperty(prefix + "model", encode(example.model()));
            properties.setProperty(prefix + "timestamp", Long.toString(example.timestamp()));
            properties.setProperty(prefix + "uses", Integer.toString(example.uses()));
        }
    }

    public synchronized void load(Properties properties) {
        examples.clear();
        maxExamples = parseInt(properties.getProperty("examples.max"), DEFAULT_MAX);
        int count = Math.max(0, Math.min(1000, parseInt(properties.getProperty("examples.count"), 0)));
        for (int i = 0; i < count; i++) {
            String prefix = "examples." + i + ".";
            String snippet = decode(properties.getProperty(prefix + "snippet", ""));
            if (snippet.isBlank()) continue;
            String bucket = decode(properties.getProperty(prefix + "bucket", ""));
            if (bucket.isBlank()) bucket = "unknown";
            String shape = decode(properties.getProperty(prefix + "shape", ""));
            if (shape.isBlank()) shape = AbapStructuralSignature.of(snippet);
            Example example = new Example(
                decode(properties.getProperty(prefix + "object", "")),
                decode(properties.getProperty(prefix + "structure", "")),
                bucket,
                shape,
                snippet,
                decode(properties.getProperty(prefix + "model", "")),
                parseLong(properties.getProperty(prefix + "timestamp"), System.currentTimeMillis()),
                Math.max(1, parseInt(properties.getProperty(prefix + "uses"), 1))
            );
            examples.put(fingerprint(
                example.snippet() + "|" + example.structureHint() + "|" + example.contextBucket() + "|" + example.shapeSignature()
            ), example);
        }
        trim();
    }

    private double score(
        Example example,
        CodeContext context,
        Set<String> queryTokens,
        String queryBucket,
        String queryShape,
        Map<String, Integer> df,
        int corpusSize
    ) {
        double score = 0;
        String structure = clean(context.structureHint()).toLowerCase(Locale.ROOT);
        if (!structure.isBlank() && example.structureHint().toLowerCase(Locale.ROOT).contains(structure)) score += 30;
        String file = clean(context.filePath());
        if (!file.isBlank() && sameObject(example.objectKey(), file)) score += 15;

        String exampleBucket = clean(example.contextBucket());
        if (!queryBucket.isBlank() && !"unknown".equals(queryBucket) && !exampleBucket.isBlank() && !"unknown".equals(exampleBucket)) {
            score += queryBucket.equals(exampleBucket) ? 40 : -18;
        }

        Set<String> exampleTokens = tokens(example.snippet());
        for (String token : queryTokens) {
            if (!exampleTokens.contains(token)) continue;
            int frequency = df.getOrDefault(token, 0);
            double idf = Math.log((corpusSize + 1.0) / (frequency + 1.0)) + 1.0;
            score += 3.5 * idf;
        }

        score += shapeSimilarity(queryShape, example.shapeSignature()) * 70.0;
        score += Math.min(15, example.uses() * 2);
        long ageDays = Math.max(0L, (System.currentTimeMillis() - example.timestamp()) / 86_400_000L);
        score -= Math.min(20L, ageDays / 14L);
        return score;
    }

    private Map<String, Integer> documentFrequency() {
        Map<String, Integer> result = new HashMap<>();
        for (Example example : examples.values()) {
            for (String token : tokens(example.snippet())) result.merge(token, 1, Integer::sum);
        }
        return result;
    }

    private static double shapeSimilarity(String a, String b) {
        Set<String> left = tokens(a);
        Set<String> right = tokens(b);
        if (left.isEmpty() || right.isEmpty()) return 0;
        int overlap = 0;
        for (String token : left) if (right.contains(token)) overlap++;
        int union = left.size() + right.size() - overlap;
        return union == 0 ? 0 : (double) overlap / union;
    }

    private void trim() {
        if (examples.size() <= maxExamples) return;
        List<Map.Entry<String, Example>> ordered = new ArrayList<>(examples.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().timestamp()));
        int remove = examples.size() - maxExamples;
        for (int i = 0; i < remove; i++) examples.remove(ordered.get(i).getKey());
    }

    static String normalize(String text) {
        if (text == null) return "";
        String result = text.strip();
        result = result.replaceAll("'[^'\\n]{5,}'", "'<literal>'");
        result = result.replaceAll("`[^`\\n]{5,}`", "`<literal>`");
        result = result.replaceAll("\\b\\d{4,}\\b", "<number>");
        if (result.length() > MAX_SNIPPET_CHARS) result = result.substring(0, MAX_SNIPPET_CHARS);
        return result;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        if (text == null) return result;
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_<>]+")) {
            if (token.length() >= 2) result.add(token);
        }
        return result;
    }

    private static boolean sameObject(String a, String b) {
        if (a.isBlank() || b.isBlank()) return false;
        return a.equals(b) || a.endsWith("/" + b) || b.endsWith("/" + a) || a.endsWith("\\" + b) || b.endsWith("\\" + a);
    }

    private static String fingerprint(String text) { return Integer.toHexString(text.hashCode()); }
    private static String clean(String value) { return value == null ? "" : value.trim(); }
    private static String encode(String value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(clean(value).getBytes(StandardCharsets.UTF_8)); }
    private static String decode(String value) {
        try { return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException invalid) { return ""; }
    }
    private static int parseInt(String value, int fallback) { try { return Integer.parseInt(value); } catch (RuntimeException e) { return fallback; } }
    private static long parseLong(String value, long fallback) { try { return Long.parseLong(value); } catch (RuntimeException e) { return fallback; } }
}
