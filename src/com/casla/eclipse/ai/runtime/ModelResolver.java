package com.casla.eclipse.ai.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.casla.eclipse.ai.api.ModelInfo;

public final class ModelResolver {
    public Optional<String> resolve(
        List<ModelInfo> models,
        String lastKnownGood,
        Set<String> excluded
    ) {
        String knownGood = clean(lastKnownGood);
        Set<String> exclusions = excluded == null ? Set.of() : excluded;
        return models.stream()
            .filter(model -> !model.id().isBlank())
            .filter(model -> !exclusions.contains(model.id()))
            .filter(this::canComplete)
            .max(Comparator.comparingInt(model -> score(model, knownGood)))
            .map(ModelInfo::id);
    }

    private boolean canComplete(ModelInfo model) {
        String id = model.id().toLowerCase(Locale.ROOT);
        return !id.contains("embedding")
            && !id.contains("rerank")
            && !id.contains("image")
            && !id.contains("audio");
    }

    int score(ModelInfo model, String lastKnownGood) {
        String id = model.id().toLowerCase(Locale.ROOT);
        int score = 0;
        if (model.id().equals(lastKnownGood)) score += 10_000;
        if (id.contains("claude-sonnet")) score += 1_000;
        if (id.contains("codex")) score += 900;
        if (id.contains("gpt-5")) score += 800;
        if (id.contains("gemini") && id.contains("flash")) score += 700;
        if (id.contains("coder")) score += 600;
        if (id.contains("review")) score -= 1_000;
        return score;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
