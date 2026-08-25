package com.casla.eclipse.ai.completion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;

import com.casla.eclipse.ai.learning.AdaptiveLearningStore;
import com.casla.eclipse.ai.learning.CompletionContextClassifier;

/** Local-only reranker for gateways/callers that can provide multiple candidates. */
public final class CompletionReranker {
    public record Candidate(String text, String model, long latencyMillis) {}
    public record Ranked(Candidate candidate, double score) {}

    public List<Ranked> rank(CodeContext context, List<Candidate> candidates) {
        if (context == null || candidates == null || candidates.isEmpty()) return List.of();
        List<Ranked> ranked = new ArrayList<>();
        String bucket = CompletionContextClassifier.bucket(context);
        var memory = AdaptiveLearningStore.get();
        var accepted = memory.acceptedExamples(context, 3);
        String style = memory.promptHints(context).toLowerCase(Locale.ROOT);

        for (Candidate candidate : candidates) {
            if (candidate == null || candidate.text() == null || candidate.text().isBlank()) continue;
            String text = candidate.text();
            if (ValidationPipeline.isUnsafe(text, context.structureHint())) continue;
            double score = 1000.0;
            score += styleScore(text, style);
            score += exampleScore(text, accepted);
            var stats = memory.modelContextFeedbackSnapshot(candidate.model(), bucket);
            if (stats.generatedCount() < 5) stats = memory.modelFeedbackSnapshot(candidate.model());
            if (stats.generatedCount() > 0) {
                score += stats.acceptanceRate() * 350.0;
                score -= stats.editAfterAcceptRate() * 220.0;
            }
            score -= Math.min(250.0, Math.max(0L, candidate.latencyMillis()) * 0.08);
            score -= Math.max(0, text.length() - 1200) * 0.04;
            ranked.add(new Ranked(candidate, score));
        }
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed());
        return List.copyOf(ranked);
    }

    public Candidate best(CodeContext context, List<Candidate> candidates) {
        List<Ranked> ranked = rank(context, candidates);
        return ranked.isEmpty() ? null : ranked.get(0).candidate();
    }

    private static double styleScore(String text, String style) {
        String upper = text.toUpperCase(Locale.ROOT);
        double score = 0;
        if (style.contains("inline data") && upper.contains("DATA(")) score += 100;
        if (style.contains("explicit data") && upper.matches("(?s).*\\bDATA\\s+[A-Z0-9_]+\\s+TYPE\\b.*")) score += 90;
        if (style.contains("table expressions") && text.contains("[")) score += 75;
        if (style.contains("read table") && upper.contains("READ TABLE")) score += 75;
        if (style.contains("modern abap") && (upper.contains("VALUE #(") || upper.contains("CORRESPONDING #(") || upper.contains("COND #(") || upper.contains("REDUCE "))) score += 80;
        return score;
    }

    private static double exampleScore(String text, java.util.List<com.casla.eclipse.ai.learning.AcceptedExampleMemory.Example> examples) {
        Set<String> candidateTokens = tokens(text);
        double best = 0;
        for (var example : examples) {
            Set<String> exampleTokens = tokens(example.snippet());
            if (candidateTokens.isEmpty() || exampleTokens.isEmpty()) continue;
            int overlap = 0; for (String token : candidateTokens) if (exampleTokens.contains(token)) overlap++;
            best = Math.max(best, 180.0 * overlap / Math.max(1, Math.min(candidateTokens.size(), exampleTokens.size())));
        }
        return best;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+")) if (token.length() >= 3) result.add(token);
        return result;
    }
}
