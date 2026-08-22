package com.casla.eclipse.ai.client;

public final class ApiException extends Exception {
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final String errorCode;

    public ApiException(int statusCode, String errorCode, String message) {
        super(message == null || message.isBlank() ? "AI request failed." : message);
        this.statusCode = statusCode;
        this.errorCode = errorCode == null ? "" : errorCode;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.errorCode = "NETWORK_ERROR";
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean isModelResolutionError() {
        if (statusCode == 404) return true;
        if (statusCode != 400) return false;
        String code = errorCode.toLowerCase();
        String message = getMessage().toLowerCase();
        return code.equals("model_not_found")
            || code.equals("unknown_model")
            || message.contains("model not found")
            || message.contains("unknown model")
            || message.contains("model is not available");
    }
}
