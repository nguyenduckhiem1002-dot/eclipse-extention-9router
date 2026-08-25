package com.casla.eclipse.ai.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.learning.AdaptiveLearningStore;
import com.casla.eclipse.ai.learning.CompletionContextClassifier;
import com.casla.eclipse.ai.learning.CompletionFeedbackStats;

/** Context-aware model routing with conservative Bayesian smoothing. */
public final class AdaptiveModelRouter {
    private static final int MIN_CONTEXT_SAMPLES = 8;
    private static final double PRIOR_ACCEPTANCE = 0.55;
    private static final double PRIOR_WEIGHT = 12.0;

    private final ModelResolver baseResolver;

    public AdaptiveModelRouter(ModelResolver baseResolver) {
        this.baseResolver = baseResolver;
    }

    public Optional<String> resolve(List<ModelInfo> models, String fallbackKnownGood, Set<String> excluded, CodeContext context) {
        if (models == null || models.isEmpty()) return Optional.empty();
        String bucket = CompletionContextClassifier.bucket(context);
        Set<String> exclusions = excluded == null ? Set.of() : excluded;
        return models.stream()
            .filter(model -> model != null && !model.id().isBlank() && !exclusions.contains(model.id()))
            .filter(model -> baseResolver.resolve(List.of(model), "", Set.of()).isPresent())
            .max(Comparator.comparingDouble(model -> score(model, fallbackKnownGood, bucket)))
            .map(ModelInfo::id);
    }

    double score(ModelInfo model, String fallbackKnownGood, String bucket) {
        double score = baseResolver.score(model, fallbackKnownGood);
        CompletionFeedbackStats contextStats = AdaptiveLearningStore.get().modelContextFeedbackSnapshot(model.id(), bucket);
        CompletionFeedbackStats totalStats = AdaptiveLearningStore.get().modelFeedbackSnapshot(model.id());

        CompletionFeedbackStats chosen = contextStats.generatedCount() >= MIN_CONTEXT_SAMPLES ? contextStats : totalStats;
        long samples = chosen.generatedCount();
        if (samples == 0) return score;

        double posteriorAcceptance = (chosen.acceptedCount() + PRIOR_ACCEPTANCE * PRIOR_WEIGHT) / (samples + PRIOR_WEIGHT);
        double confidence = Math.min(1.0, samples / 30.0);
        double editPenalty = chosen.editAfterAcceptRate();
        double dismissalRate = (double) chosen.dismissedCount() / Math.max(1L, samples);
        double supersededRate = (double) chosen.supersededCount() / Math.max(1L, samples);

        score += confidence * ((posteriorAcceptance - PRIOR_ACCEPTANCE) * 2400.0);
        score -= confidence * editPenalty * 900.0;
        score -= confidence * dismissalRate * 500.0;
        score -= confidence * supersededRate * 150.0;
        if (contextStats.generatedCount() >= MIN_CONTEXT_SAMPLES) score += 120.0;
        return score;
    }
}
