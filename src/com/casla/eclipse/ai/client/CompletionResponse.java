package com.casla.eclipse.ai.client;

public record CompletionResponse(
    String content,
    String responseModel,
    String requestId,
    long promptTokens,
    long completionTokens
) {
    public CompletionResponse {
        content = content == null ? "" : content;
        responseModel = responseModel == null ? "" : responseModel;
        requestId = requestId == null ? "" : requestId;
    }
}
