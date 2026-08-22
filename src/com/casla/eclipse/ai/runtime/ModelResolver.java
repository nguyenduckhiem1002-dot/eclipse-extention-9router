package com.casla.eclipse.ai.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * Inline completion is latency-bound, not quality-bound the way a chat
     * turn is: a slow-but-smart model that answers after the editor has
     * moved on is worse than a fast, merely-good one. So this favors small /
     * low-effort tiers and penalizes models whose reasoning can't be turned
     * down, using both the id (gateway naming conventions like "-low" or
     * "-flash") and, where present, the wire capabilities.
     */
    int score(ModelInfo model, String lastKnownGood) {
        String id = model.id().toLowerCase(Locale.ROOT);
        int score = 0;
        if (model.id().equals(lastKnownGood)) score += 10_000;

        if (id.contains("extra-low") || id.contains("nano")) score += 750;
        else if (id.contains("-low")) score += 600;
        else if (id.contains("-medium")) score -= 100;
        else if (id.contains("-high")) score -= 500;

        if (id.contains("flash")) score += 700;
        if (id.contains("mini")) score += 650;
        if (id.contains("haiku")) score += 650;
        if (id.contains("coder")) score += 600;
        if (id.contains("codex")) score += 500;
        if (id.contains("gpt-5")) score += 300;
        if (id.contains("claude-sonnet")) score += 200;

        if (id.contains("opus")) score -= 700;
        if (id.contains("thinking")) score -= 600;
        if (id.contains("pro")) score -= 300;
        if (id.contains("review")) score -= 1_000;

        score += capabilityScore(model.capabilities());
        return score;
    }

    private int capabilityScore(Map<String, Object> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) return 0;
        int score = 0;
        boolean reasoning = Boolean.TRUE.equals(capabilities.get("reasoning"));
        boolean thinkingCanDisable = Boolean.TRUE.equals(capabilities.get("thinkingCanDisable"));
        if (reasoning && !thinkingCanDisable) {
            // Always-on thinking with no way to turn it down: the worst case
            // for inline completion latency.
            score -= 800;
        } else if (reasoning) {
            score -= 150;
        }
        if (capabilities.get("maxOutput") instanceof Number maxOutput && maxOutput.longValue() > 32_000) {
            // Not a hard cost by itself, but it correlates with heavier
            // "flagship" tiers rather than completion-sized models.
            score -= 100;
        }
        return score;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
