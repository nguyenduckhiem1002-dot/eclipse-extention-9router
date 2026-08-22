package com.casla.eclipse.ai.tests;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.CompletionSanitizer;
import com.casla.eclipse.ai.internal.json.Json;
import com.casla.eclipse.ai.runtime.ModelResolver;

public final class CoreTests {
    private static int passed;

    public static void main(String[] args) {
        testJsonRoundTrip();
        testJsonUnicodeAndNumbers();
        testModelResolutionPrefersKnownGood();
        testModelResolutionAvoidsReviewAndEmbedding();
        testModelExclusion();
        testModelCapabilitiesAllowNullWireValues();
        testConnectionNormalization();
        testRemoteHttpRejected();
        testCompletionFenceRemoval();
        testCompletionDeduplication();
        System.out.println("Core tests passed: " + passed);
    }

    private static void testJsonRoundTrip() {
        Map<String, Object> input = Map.of(
            "model", "ag/claude-sonnet-4-6",
            "stream", true,
            "messages", List.of(Map.of("role", "user", "content", "hello"))
        );
        Map<String, Object> parsed = Json.object(Json.parse(Json.stringify(input)));
        check("ag/claude-sonnet-4-6".equals(parsed.get("model")), "JSON model round-trip");
        check(Boolean.TRUE.equals(parsed.get("stream")), "JSON boolean round-trip");
    }

    private static void testJsonUnicodeAndNumbers() {
        Map<String, Object> parsed = Json.object(Json.parse("{\"text\":\"Xin chào \\u263a\",\"n\":128}"));
        check("Xin chào ☺".equals(parsed.get("text")), "JSON unicode decoding");
        check(((Number) parsed.get("n")).longValue() == 128, "JSON number decoding");
    }

    private static void testModelResolutionPrefersKnownGood() {
        List<ModelInfo> models = List.of(
            model("ag/claude-sonnet-4-6"),
            model("cx/gpt-5.4-mini")
        );
        String resolved = new ModelResolver().resolve(models, "cx/gpt-5.4-mini", Set.of()).orElseThrow();
        check("cx/gpt-5.4-mini".equals(resolved), "Known-good model wins");
    }

    private static void testModelResolutionAvoidsReviewAndEmbedding() {
        List<ModelInfo> models = List.of(
            model("text-embedding-3-large"),
            model("cx/gpt-5.5-review"),
            model("ag/claude-sonnet-4-6")
        );
        String resolved = new ModelResolver().resolve(models, "", Set.of()).orElseThrow();
        check("ag/claude-sonnet-4-6".equals(resolved), "Resolver filters unsuitable models");
    }

    private static void testModelExclusion() {
        List<ModelInfo> models = List.of(
            model("ag/claude-sonnet-4-6"),
            model("cx/gpt-5.4-mini")
        );
        String resolved = new ModelResolver().resolve(
            models, "", Set.of("ag/claude-sonnet-4-6")
        ).orElseThrow();
        check("cx/gpt-5.4-mini".equals(resolved), "Failover excludes failed model");
    }

    private static void testModelCapabilitiesAllowNullWireValues() {
        java.util.LinkedHashMap<String, Object> capabilities = new java.util.LinkedHashMap<>();
        capabilities.put("reasoning", true);
        capabilities.put("thinkingRange", null);
        ModelInfo model = new ModelInfo("model", "owner", capabilities);
        check(model.capabilities().containsKey("thinkingRange"), "Model capabilities preserve null wire values");
    }

    private static void testConnectionNormalization() {
        ConnectionConfig connection = new ConnectionConfig(" http://localhost:20128/v1/// ", " key ");
        check("http://localhost:20128/v1".equals(connection.baseUrl()), "Base URL normalization");
        check("key".equals(connection.apiKey()), "API key whitespace normalization");
    }

    private static void testRemoteHttpRejected() {
        check(ConnectionConfig.validateBaseUrl("http://example.com/v1") != null, "Remote HTTP rejected");
        check(ConnectionConfig.validateBaseUrl("https://example.com/v1") == null, "Remote HTTPS accepted");
    }

    private static void testCompletionFenceRemoval() {
        String result = new CompletionSanitizer().sanitize("```java\nreturn value;\n```", context("", ""));
        check("return value;".equals(result), "Markdown fence removal");
    }

    private static void testCompletionDeduplication() {
        String result = new CompletionSanitizer().sanitize(
            "public void run() {\n    execute();\n}",
            context("public void run() {", "\n}")
        );
        check(!result.startsWith("public void run() {"), "Repeated prefix removal");
        check(!result.endsWith("\n}"), "Repeated suffix removal");
    }

    private static ModelInfo model(String id) {
        return new ModelInfo(id, "test", Map.of());
    }

    private static CodeContext context(String before, String after) {
        return new CodeContext("p", "/A.java", "Java", "", "", before, after, before.length(), 1L, "x");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        passed++;
    }
}
