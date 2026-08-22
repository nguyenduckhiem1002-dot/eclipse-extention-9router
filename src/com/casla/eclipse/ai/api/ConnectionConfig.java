package com.casla.eclipse.ai.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;

public record ConnectionConfig(String baseUrl, String apiKey) {
    public ConnectionConfig {
        baseUrl = normalizeBaseUrl(baseUrl);
        apiKey = apiKey == null ? "" : apiKey.trim();
    }

    public URI endpoint(String path) {
        String suffix = path.startsWith("/") ? path : "/" + path;
        return URI.create(baseUrl + suffix);
    }

    public boolean isComplete() {
        return !baseUrl.isBlank() && !apiKey.isBlank();
    }

    public static String validateBaseUrl(String raw) {
        String normalized = normalizeBaseUrl(raw);
        if (normalized.isBlank()) {
            return "Base URL is required.";
        }
        try {
            URI uri = new URI(normalized);
            String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return "Base URL must use http or https.";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                return "Base URL must contain a host.";
            }
            if (uri.getQuery() != null || uri.getFragment() != null) {
                return "Base URL must not contain a query or fragment.";
            }
            boolean local = uri.getHost().equalsIgnoreCase("localhost")
                || uri.getHost().equals("127.0.0.1")
                || uri.getHost().equals("::1")
                || uri.getHost().equals("[::1]");
            if (scheme.equals("http") && !local) {
                return "Remote endpoints must use HTTPS.";
            }
            return null;
        } catch (URISyntaxException | IllegalArgumentException error) {
            return "Base URL is not valid.";
        }
    }

    public static String normalizeBaseUrl(String raw) {
        String value = raw == null ? "" : raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
