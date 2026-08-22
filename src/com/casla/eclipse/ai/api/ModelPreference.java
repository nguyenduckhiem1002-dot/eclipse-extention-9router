package com.casla.eclipse.ai.api;

public record ModelPreference(
    ModelSelectionMode mode,
    String manualModelId,
    String lastResolvedAutoId,
    String lastKnownGoodModel
) {
    public ModelPreference {
        mode = mode == null ? ModelSelectionMode.AUTO : mode;
        manualModelId = clean(manualModelId);
        lastResolvedAutoId = clean(lastResolvedAutoId);
        lastKnownGoodModel = clean(lastKnownGoodModel);
    }

    public ModelPreference clearAutoResolution() {
        return new ModelPreference(mode, manualModelId, "", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
