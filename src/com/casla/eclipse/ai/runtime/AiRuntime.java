package com.casla.eclipse.ai.runtime;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.CompletionSettings;
import com.casla.eclipse.ai.api.ConnectionConfig;
import com.casla.eclipse.ai.api.ConnectionStatus;
import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.api.ModelPreference;
import com.casla.eclipse.ai.api.ModelSelectionMode;
import com.casla.eclipse.ai.api.ModelStatus;
import com.casla.eclipse.ai.api.RuntimeSnapshot;
import com.casla.eclipse.ai.client.ApiException;
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.client.ModelCatalog;
import com.casla.eclipse.ai.client.OpenAiCompatibleClient;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.CompletionPromptBuilder;
import com.casla.eclipse.ai.completion.CompletionSanitizer;
import com.casla.eclipse.ai.completion.ValidationPipeline;
import com.casla.eclipse.ai.completion.abap.AbapCompletionQualityGate;
import com.casla.eclipse.ai.learning.CompletionFeedbackTracker;
import com.casla.eclipse.ai.preferences.AiPreferences;

public final class AiRuntime {
    private static final AiRuntime INSTANCE = new AiRuntime();

    private final AiPreferences preferences = new AiPreferences();
    private final OpenAiCompatibleClient client = new OpenAiCompatibleClient();
    private final ModelResolver resolver = new ModelResolver();
    private final AdaptiveModelRouter adaptiveRouter = new AdaptiveModelRouter(resolver);
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<RuntimeSnapshot> snapshot = new AtomicReference<>(
        new RuntimeSnapshot(ConnectionStatus.UNVERIFIED, ModelStatus.UNRESOLVED, "", "Not verified")
    );

    private volatile ConnectionConfig activeConnection;
    private volatile ModelPreference activeModelPreference;
    private volatile List<ModelInfo> catalog = List.of();

    private AiRuntime() {
        activeConnection = new ConnectionConfig("", "");
        activeModelPreference = new ModelPreference(ModelSelectionMode.AUTO, "", "", "");
    }

    public static AiRuntime get() { return INSTANCE; }
    public RuntimeSnapshot snapshot() { return snapshot.get(); }
    public List<ModelInfo> catalog() { return List.copyOf(catalog); }
    public long generation() { return generation.get(); }

    public void bootstrap() {
        activeConnection = preferences.connection();
        activeModelPreference = preferences.modelPreference();
        if (!activeConnection.isComplete()) return;
        Job job = new Job("Verify AI Code Assistant connection") {
            @Override protected IStatus run(IProgressMonitor monitor) {
                try { testConnection(activeConnection, activeModelPreference, monitor); }
                catch (OperationCanceledException ignored) { return Status.CANCEL_STATUS; }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule(750);
    }

    public synchronized void invalidateDraftConfiguration() {
        generation.incrementAndGet();
        snapshot.set(new RuntimeSnapshot(ConnectionStatus.UNVERIFIED, ModelStatus.UNRESOLVED, "", "Configuration changed — test the connection again."));
    }

    public synchronized void invalidateDraftModel() {
        generation.incrementAndGet();
        RuntimeSnapshot current = snapshot.get();
        ConnectionStatus connection = current.connectionStatus() == ConnectionStatus.OK ? ConnectionStatus.OK : ConnectionStatus.UNVERIFIED;
        snapshot.set(new RuntimeSnapshot(connection, ModelStatus.UNRESOLVED, "", "Model selection changed — resolve the model again."));
    }

    public synchronized void resolveAutoFromCatalog() {
        if (catalog.isEmpty() || activeConnection == null || !activeConnection.isComplete()) return;
        String resolved = resolver.resolve(catalog, activeModelPreference.lastKnownGoodModel(), Set.of()).orElse("");
        if (resolved.isBlank()) return;
        activeModelPreference = new ModelPreference(ModelSelectionMode.AUTO, activeModelPreference.manualModelId(), resolved, activeModelPreference.lastKnownGoodModel());
        RuntimeSnapshot current = snapshot.get();
        if (current.connectionStatus() == ConnectionStatus.OK) snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.RESOLVED, resolved, "Connected"));
    }

    public synchronized void reloadPersistedAsync() {
        generation.incrementAndGet();
        activeConnection = preferences.connection();
        activeModelPreference = preferences.modelPreference();
        catalog = List.of();
        snapshot.set(new RuntimeSnapshot(ConnectionStatus.UNVERIFIED, ModelStatus.UNRESOLVED, "", "Not verified"));
        bootstrap();
    }

    public synchronized void commitConfiguration(ConnectionConfig connection, ModelPreference modelPreference) {
        boolean sameConnection = connection.equals(activeConnection);
        activeConnection = connection;
        activeModelPreference = modelPreference;
        if (!sameConnection) {
            generation.incrementAndGet();
            catalog = List.of();
            snapshot.set(new RuntimeSnapshot(ConnectionStatus.UNVERIFIED, ModelStatus.UNRESOLVED, "", "Not verified"));
        }
    }

    public ConnectionTestReport testConnection(ConnectionConfig connection, ModelPreference modelPreference, IProgressMonitor monitor) {
        long started = System.nanoTime();
        activeConnection = connection;
        activeModelPreference = modelPreference;
        snapshot.set(new RuntimeSnapshot(ConnectionStatus.CHECKING, ModelStatus.UNRESOLVED, "", "Checking endpoint…"));
        try {
            ModelCatalog loaded = client.listModels(connection, preferences.completionSettings().timeoutSeconds(), monitor);
            catalog = loaded.models();
            snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.RESOLVING, "", "Resolving model…"));
            String resolved = resolveModel(modelPreference, catalog, Set.of());
            if (resolved.isBlank()) {
                snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.ERROR, "", "No usable model was found."));
                return report(false, true, true, true, catalog.size(), "", started, "No usable model was found.");
            }
            snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.RESOLVED, resolved, "Connected"));
            if (modelPreference.mode() == ModelSelectionMode.AUTO) {
                activeModelPreference = new ModelPreference(modelPreference.mode(), modelPreference.manualModelId(), resolved, modelPreference.lastKnownGoodModel());
            }
            return report(true, true, true, true, catalog.size(), resolved, started, "Connected");
        } catch (ApiException error) {
            if (error.statusCode() == 404) {
                catalog = List.of();
                String manual = modelPreference.mode() == ModelSelectionMode.MANUAL ? modelPreference.manualModelId() : "";
                ModelStatus modelStatus = manual.isBlank() ? ModelStatus.ERROR : ModelStatus.RESOLVED;
                snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, modelStatus, manual, "Endpoint does not support listing models."));
                return report(!manual.isBlank(), true, null, false, 0, manual, started, "Endpoint does not support /models; enter a model ID manually.");
            }
            boolean reachable = error.statusCode() > 0;
            Boolean auth = error.statusCode() == 401 || error.statusCode() == 403 ? Boolean.FALSE : null;
            snapshot.set(new RuntimeSnapshot(ConnectionStatus.ERROR, ModelStatus.UNRESOLVED, "", error.getMessage()));
            return report(false, reachable, auth, false, 0, "", started, error.getMessage());
        }
    }

    public CompletionResponse complete(CodeContext context, IProgressMonitor monitor) throws ApiException, OperationCanceledException {
        return complete(context, monitor, false);
    }

    public CompletionResponse complete(CodeContext context, IProgressMonitor monitor, boolean singleLine) throws ApiException, OperationCanceledException {
        RuntimeSnapshot state = snapshot.get();
        if (!state.canComplete()) throw new ApiException(0, "NOT_READY", "AI connection or model is not ready.");

        long requestGeneration = generation.get();
        CompletionSettings settings = preferences.completionSettings();
        CompletionPromptBuilder.Prompt prompt = new CompletionPromptBuilder().build(context);
        String model = chooseModelForContext(state.resolvedModelId(), context, Set.of());

        try {
            CompletionResponse response = client.complete(activeConnection, model, prompt.system(), prompt.user(), settings, monitor, singleLine);
            ensureCurrent(requestGeneration);
            CompletionResponse sanitized = withSelectedModel(sanitizeOrThrow(response, context), model);
            markKnownGood(model);
            CompletionFeedbackTracker.get().generated(context, sanitized);
            return sanitized;
        } catch (ApiException firstError) {
            if (activeModelPreference.mode() != ModelSelectionMode.AUTO || !firstError.isModelResolutionError()) {
                updateRuntimeError(firstError); throw firstError;
            }
            String fallback = chooseModelForContext(state.resolvedModelId(), context, Set.of(model));
            if (fallback.isBlank()) {
                snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.ERROR, "", "No fallback model is available."));
                throw firstError;
            }
            snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.RESOLVED, fallback, "Using fallback model"));
            preferences.saveLastResolvedAutoModel(fallback);
            try {
                CompletionResponse response = client.complete(activeConnection, fallback, prompt.system(), prompt.user(), settings, monitor, singleLine);
                ensureCurrent(requestGeneration);
                CompletionResponse sanitized = withSelectedModel(sanitizeOrThrow(response, context), fallback);
                markKnownGood(fallback);
                CompletionFeedbackTracker.get().generated(context, sanitized);
                return sanitized;
            } catch (ApiException secondError) {
                updateRuntimeError(secondError); throw secondError;
            }
        }
    }

    /**
     * Runs a bounded code-action prompt (fix/refactor/etc.) through the same
     * connection, model routing, sanitizer and ABAP quality gates as ordinary
     * completion, without registering the result as an inline completion.
     */
    public CompletionResponse completeCodeAction(
        CodeContext context,
        String systemPrompt,
        String userPrompt,
        IProgressMonitor monitor
    ) throws ApiException, OperationCanceledException {
        RuntimeSnapshot state = snapshot.get();
        if (!state.canComplete()) throw new ApiException(0, "NOT_READY", "AI connection or model is not ready.");
        long requestGeneration = generation.get();
        CompletionSettings settings = preferences.completionSettings();
        String model = chooseModelForContext(state.resolvedModelId(), context, Set.of());

        try {
            CompletionResponse response = client.complete(activeConnection, model, systemPrompt, userPrompt, settings, monitor, false);
            ensureCurrent(requestGeneration);
            CompletionResponse sanitized = withSelectedModel(sanitizeOrThrow(response, context), model);
            markKnownGood(model);
            return sanitized;
        } catch (ApiException firstError) {
            if (activeModelPreference.mode() != ModelSelectionMode.AUTO || !firstError.isModelResolutionError()) {
                updateRuntimeError(firstError);
                throw firstError;
            }
            String fallback = chooseModelForContext(state.resolvedModelId(), context, Set.of(model));
            if (fallback.isBlank()) throw firstError;
            CompletionResponse response = client.complete(activeConnection, fallback, systemPrompt, userPrompt, settings, monitor, false);
            ensureCurrent(requestGeneration);
            CompletionResponse sanitized = withSelectedModel(sanitizeOrThrow(response, context), fallback);
            markKnownGood(fallback);
            return sanitized;
        }
    }

    private String chooseModelForContext(String fallback, CodeContext context, Set<String> excluded) {
        if (activeModelPreference.mode() == ModelSelectionMode.MANUAL || catalog.isEmpty()) return excluded.contains(fallback) ? "" : fallback;
        return adaptiveRouter.resolve(catalog, activeModelPreference.lastKnownGoodModel(), excluded, context).orElse(fallback);
    }

    private CompletionResponse sanitizeOrThrow(CompletionResponse response, CodeContext context) throws ApiException {
        String insertion = new CompletionSanitizer().sanitize(response.content(), context);
        insertion = AbapCompletionQualityGate.refine(insertion, context);
        if (insertion.isBlank() || ValidationPipeline.isUnsafe(insertion, context.structureHint())) {
            throw new ApiException(200, "EMPTY_COMPLETION", "The model returned an empty or unsafe completion.");
        }
        return new CompletionResponse(insertion, response.responseModel(), response.requestId(), response.promptTokens(), response.completionTokens());
    }

    private static CompletionResponse withSelectedModel(CompletionResponse response, String selectedModel) {
        if (response.responseModel() != null && !response.responseModel().isBlank()) return response;
        return new CompletionResponse(response.content(), selectedModel, response.requestId(), response.promptTokens(), response.completionTokens());
    }

    public void shutdown() {
        generation.incrementAndGet();
        CompletionFeedbackTracker.get().resetTransient();
        client.close();
    }

    private String resolveModel(ModelPreference preference, List<ModelInfo> models, Set<String> excluded) {
        if (preference.mode() == ModelSelectionMode.MANUAL) return excluded.contains(preference.manualModelId()) ? "" : preference.manualModelId();
        return resolver.resolve(models, preference.lastKnownGoodModel(), excluded).orElse("");
    }

    private void markKnownGood(String model) {
        preferences.saveLastKnownGoodModel(model);
        activeModelPreference = new ModelPreference(activeModelPreference.mode(), activeModelPreference.manualModelId(), activeModelPreference.lastResolvedAutoId(), model);
    }

    private void updateRuntimeError(ApiException error) {
        if (error.statusCode() == 401 || error.statusCode() == 403 || error.statusCode() == 0) snapshot.set(new RuntimeSnapshot(ConnectionStatus.ERROR, ModelStatus.UNRESOLVED, "", error.getMessage()));
        else if (error.isModelResolutionError()) snapshot.set(new RuntimeSnapshot(ConnectionStatus.OK, ModelStatus.ERROR, "", error.getMessage()));
    }

    private void ensureCurrent(long requestGeneration) { if (requestGeneration != generation.get()) throw new OperationCanceledException(); }

    private static ConnectionTestReport report(boolean success, boolean reachable, Boolean authentication, boolean catalogSupported, int modelCount, String model, long started, String message) {
        long latency = (System.nanoTime() - started) / 1_000_000;
        return new ConnectionTestReport(success, reachable, authentication, catalogSupported, modelCount, model, latency, message);
    }
}
