package com.casla.eclipse.ai.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record ModelInfo(String id, String ownedBy, Map<String, Object> capabilities) {
    public ModelInfo {
        id = id == null ? "" : id.trim();
        ownedBy = ownedBy == null ? "" : ownedBy.trim();
        capabilities = capabilities == null
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(capabilities));
    }
}
