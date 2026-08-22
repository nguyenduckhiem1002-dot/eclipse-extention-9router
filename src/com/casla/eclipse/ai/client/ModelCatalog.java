package com.casla.eclipse.ai.client;

import java.time.Duration;
import java.util.List;

import com.casla.eclipse.ai.api.ModelInfo;

public record ModelCatalog(List<ModelInfo> models, Duration latency) {
    public ModelCatalog {
        models = models == null ? List.of() : List.copyOf(models);
        latency = latency == null ? Duration.ZERO : latency;
    }
}
