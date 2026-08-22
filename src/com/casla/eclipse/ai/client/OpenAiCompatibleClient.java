package com.casla.eclipse.ai.client;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;

import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.internal.json.Json;

public final class OpenAiCompatibleClient implements AutoCloseable {
    private final HttpClient httpClient;

    public OpenAiCompatibleClient() {
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // Several OpenAI-compatible desktop gateways do not correctly
            // handle the JDK's h2c/HTTP2 negotiation on localhost.
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    public ModelCatalog listModels(
        ConnectionConfig connection,
        int timeoutSeconds,
        IProgressMonitor monitor
    ) throws ApiException, OperationCanceledException {
        ensureNotCanceled(monitor);
        Instant started = Instant.now();
        HttpRequest request = requestBuilder(connection.endpoint("/models"), connection, timeoutSeconds)
            .GET()
            .build();

        HttpResponse<String> response = sendString(request, monitor);
        if (response.statusCode() != 200) {
            throw apiError(response.statusCode(), response.body());
        }

        try {
            Map<String, Object> root = Json.object(Json.parse(response.body()));
            List<ModelInfo> models = new ArrayList<>();
            for (Object item : Json.array(root.get("data"))) {
                Map<String, Object> model = Json.object(item);
                String id = Json.string(model.get("id"));
                if (!id.isBlank()) {
                    models.add(new ModelInfo(
                        id,
                        Json.string(model.get("owned_by")),
                        Json.object(model.get("capabilities"))
                    ));
                }
            }
            return new ModelCatalog(models, Duration.between(started, Instant.now()));
        } catch (RuntimeException error) {
            throw new ApiException(200, "INVALID_RESPONSE", "The models response is not valid JSON.");
        }
    }

    public CompletionResponse complete(
        ConnectionConfig connection,
        String model,
        String systemPrompt,
        String userPrompt,
        CompletionSettings settings,
        IProgressMonitor monitor
    ) throws ApiException, OperationCanceledException {
        ensureNotCanceled(monitor);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", systemPrompt),
            Map.of("role", "user", "content", userPrompt)
        ));
        body.put("max_tokens", settings.maxTokens());
        body.put("temperature", settings.temperature());
        body.put("stream", true);

        HttpRequest request = requestBuilder(
            connection.endpoint("/chat/completions"),
            connection,
            settings.timeoutSeconds()
        )
            .header("Accept", "text/event-stream, application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
            .build();

        HttpResponse<InputStream> response = sendStream(request, monitor);
        if (response.statusCode() != 200) {
            String errorBody = readLimited(response.body(), 64 * 1024);
            throw apiError(response.statusCode(), errorBody);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        try {
            if (contentType.toLowerCase().contains("text/event-stream")) {
                return readSseCompletion(response.body(), monitor);
            }
            return readJsonCompletion(readLimited(response.body(), 4 * 1024 * 1024));
        } catch (IOException error) {
            throw new ApiException("Failed while reading the AI response stream.", error);
        }
    }

    private CompletionResponse readSseCompletion(
        InputStream stream,
        IProgressMonitor monitor
    ) throws IOException, ApiException {
        StringBuilder content = new StringBuilder();
        String model = "";
        String requestId = "";
        long promptTokens = 0;
        long completionTokens = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                ensureNotCanceled(monitor);
                if (!line.startsWith("data:")) continue;
                String payload = line.substring(5).trim();
                if (payload.isEmpty()) continue;
                if (payload.equals("[DONE]")) break;

                Map<String, Object> event;
                try {
                    event = Json.object(Json.parse(payload));
                } catch (RuntimeException malformedChunk) {
                    continue;
                }
                requestId = firstNonBlank(requestId, Json.string(event.get("id")));
                model = firstNonBlank(model, Json.string(event.get("model")));

                List<Object> choices = Json.array(event.get("choices"));
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = Json.object(choices.get(0));
                    Map<String, Object> delta = Json.object(choice.get("delta"));
                    content.append(extractText(delta.get("content")));
                }

                Map<String, Object> usage = Json.object(event.get("usage"));
                promptTokens = number(usage.get("prompt_tokens"), promptTokens);
                completionTokens = number(usage.get("completion_tokens"), completionTokens);
                if (!choices.isEmpty()) {
                    Map<String, Object> choice = Json.object(choices.get(0));
                    if (!Json.string(choice.get("finish_reason")).isBlank()) break;
                }
            }
        }

        if (content.isEmpty()) {
            throw new ApiException(200, "EMPTY_COMPLETION", "The model returned an empty completion.");
        }
        return new CompletionResponse(content.toString(), model, requestId, promptTokens, completionTokens);
    }

    private CompletionResponse readJsonCompletion(String body) throws ApiException {
        try {
            Map<String, Object> root = Json.object(Json.parse(body));
            List<Object> choices = Json.array(root.get("choices"));
            if (choices.isEmpty()) throw new IllegalArgumentException("choices is empty");
            Map<String, Object> choice = Json.object(choices.get(0));
            Map<String, Object> message = Json.object(choice.get("message"));
            String content = extractText(message.get("content"));
            if (content.isBlank()) {
                throw new ApiException(200, "EMPTY_COMPLETION", "The model returned an empty completion.");
            }
            Map<String, Object> usage = Json.object(root.get("usage"));
            return new CompletionResponse(
                content,
                Json.string(root.get("model")),
                Json.string(root.get("id")),
                number(usage.get("prompt_tokens"), 0),
                number(usage.get("completion_tokens"), 0)
            );
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new ApiException(200, "INVALID_RESPONSE", "The completion response is not valid JSON.");
        }
    }

    private static String extractText(Object content) {
        if (content instanceof String text) return text;
        StringBuilder output = new StringBuilder();
        for (Object partValue : Json.array(content)) {
            Map<String, Object> part = Json.object(partValue);
            if ("text".equals(Json.string(part.get("type")))) {
                output.append(Json.string(part.get("text")));
            }
        }
        return output.toString();
    }

    private HttpRequest.Builder requestBuilder(
        URI uri,
        ConnectionConfig connection,
        int timeoutSeconds
    ) {
        return HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
            .header("Authorization", "Bearer " + connection.apiKey())
            .header("Content-Type", "application/json")
            .header("User-Agent", "Casla-Eclipse-AI/0.1.0");
    }

    private HttpResponse<String> sendString(
        HttpRequest request,
        IProgressMonitor monitor
    ) throws ApiException {
        try {
            ensureNotCanceled(monitor);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        } catch (ConnectException error) {
            throw new ApiException("Connection refused by " + request.uri().getAuthority() + ".", error);
        } catch (IOException error) {
            throw new ApiException("Could not connect to the AI endpoint.", error);
        }
    }

    private HttpResponse<InputStream> sendStream(
        HttpRequest request,
        IProgressMonitor monitor
    ) throws ApiException {
        try {
            ensureNotCanceled(monitor);
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new OperationCanceledException();
        } catch (ConnectException error) {
            throw new ApiException("Connection refused by " + request.uri().getAuthority() + ".", error);
        } catch (IOException error) {
            throw new ApiException("Could not connect to the AI endpoint.", error);
        }
    }

    private static ApiException apiError(int statusCode, String body) {
        try {
            Map<String, Object> root = Json.object(Json.parse(body));
            Map<String, Object> error = Json.object(root.get("error"));
            String code = Json.string(error.get("code"));
            String message = Json.string(error.get("message"));
            if (message.isBlank()) message = Json.string(root.get("message"));
            if (message.isBlank()) message = "AI endpoint returned HTTP " + statusCode + ".";
            return new ApiException(statusCode, code, message);
        } catch (RuntimeException malformedError) {
            return new ApiException(statusCode, "HTTP_" + statusCode, "AI endpoint returned HTTP " + statusCode + ".");
        }
    }

    private static String readLimited(InputStream input, int limit) throws ApiException {
        try (input) {
            byte[] bytes = input.readNBytes(limit);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new ApiException("Could not read the AI endpoint response.", error);
        }
    }

    private static void ensureNotCanceled(IProgressMonitor monitor) {
        if (monitor != null && monitor.isCanceled()) throw new OperationCanceledException();
    }

    private static String firstNonBlank(String current, String candidate) {
        return current == null || current.isBlank() ? candidate : current;
    }

    private static long number(Object value, long fallback) {
        return value instanceof Number number ? number.longValue() : fallback;
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close operation on Java 21.
    }
}
