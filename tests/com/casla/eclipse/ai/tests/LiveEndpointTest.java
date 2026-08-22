package com.casla.eclipse.ai.tests;

import org.eclipse.core.runtime.NullProgressMonitor;

import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.client.ModelCatalog;
import com.casla.eclipse.ai.client.OpenAiCompatibleClient;

/** Optional integration test. Credentials are read only from the process environment. */
public final class LiveEndpointTest {
    public static void main(String[] args) throws Exception {
        String apiKey = require("AI_CODE_ASSISTANT_API_KEY");
        String baseUrl = value("AI_CODE_ASSISTANT_BASE_URL", "http://localhost:20128/v1");
        String model = value("AI_CODE_ASSISTANT_MODEL", "ag/claude-sonnet-4-6");
        NullProgressMonitor monitor = new NullProgressMonitor();

        try (OpenAiCompatibleClient client = new OpenAiCompatibleClient()) {
            ConnectionConfig connection = new ConnectionConfig(baseUrl, apiKey);
            ModelCatalog catalog = client.listModels(connection, 30, monitor);
            if (catalog.models().isEmpty()) throw new AssertionError("Model catalog is empty");

            CompletionResponse response = client.complete(
                connection,
                model,
                "Return only the requested text.",
                "Reply exactly API_OK",
                new CompletionSettings(16, 0.0, 60, 1000, 100, false, 500, "default"),
                monitor
            );
            if (!"API_OK".equals(response.content().trim())) {
                throw new AssertionError("Unexpected completion content");
            }
            System.out.println(
                "Live endpoint test passed: models=" + catalog.models().size()
                    + ", responseModel=" + response.responseModel()
            );
        }
    }

    private static String require(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private static String value(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
