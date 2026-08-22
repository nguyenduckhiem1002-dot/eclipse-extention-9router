package com.casla.eclipse.ai.runtime;

public record ConnectionTestReport(
    boolean success,
    boolean endpointReachable,
    Boolean authenticationValid,
    boolean catalogSupported,
    int modelCount,
    String resolvedModelId,
    long latencyMillis,
    String message
) {
    public ConnectionTestReport {
        resolvedModelId = resolvedModelId == null ? "" : resolvedModelId;
        message = message == null ? "" : message;
    }
}
