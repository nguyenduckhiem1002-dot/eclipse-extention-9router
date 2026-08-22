package com.casla.eclipse.ai.api;

public record RuntimeSnapshot(
    ConnectionStatus connectionStatus,
    ModelStatus modelStatus,
    String resolvedModelId,
    String message
) {
    public RuntimeSnapshot {
        resolvedModelId = resolvedModelId == null ? "" : resolvedModelId;
        message = message == null ? "" : message;
    }

    public boolean canComplete() {
        return connectionStatus == ConnectionStatus.OK
            && modelStatus == ModelStatus.RESOLVED
            && !resolvedModelId.isBlank();
    }
}
