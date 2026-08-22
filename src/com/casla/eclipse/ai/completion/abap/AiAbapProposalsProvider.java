package com.casla.eclipse.ai.completion.abap;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.contentassist.CompletionProposal;
import org.eclipse.jface.text.contentassist.ICompletionProposal;

import com.sap.adt.util.ui.async.codecompletion.IClientProposalsProvider;

import com.casla.eclipse.ai.AiPlugin;
import com.casla.eclipse.ai.api.RuntimeSnapshot;
import com.casla.eclipse.ai.client.ApiException;
import com.casla.eclipse.ai.client.CompletionResponse;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.CompletionSanitizer;
import com.casla.eclipse.ai.preferences.AiPreferences;
import com.casla.eclipse.ai.runtime.AiRuntime;

/**
 * Contributes AI suggestions to the ABAP source editor via ADT's own
 * clientProposalProvider extension point (see plugin.xml).
 *
 * ADT's IdeContentAssistant extends the standard JFace ContentAssistant.
 * Unlike JDT's javaCompletionProposalComputer, this extension point has no
 * requiresUIThread flag; in practice ADT invokes it on the UI thread, so a
 * synchronous HTTP call here freezes the editor for the request's duration.
 * To avoid that, the request runs on a background executor and this method
 * blocks for at most MAX_BLOCK_MILLIS -- short enough not to read as a
 * freeze, long enough to catch a fast response. A slower request keeps
 * running in the background and its result is cached under the exact
 * document+offset fingerprint, so the next invocation at the same position
 * (typically moments away, since ADT re-queries providers as the popup
 * stays open or is re-opened) can return it instantly instead of blocking
 * again.
 */
public final class AiAbapProposalsProvider implements IClientProposalsProvider {
    private static final long MAX_BLOCK_MILLIS = 400;

    // Daemon threads: nothing outside this class references AiPlugin, so
    // there is no shutdown hook to release this executor explicitly without
    // creating a hard dependency from the core plugin onto ADT's optional
    // classes. Daemon threads exit with the JVM on their own.
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-abap-completion");
        thread.setDaemon(true);
        return thread;
    });

    private static final ConcurrentHashMap<String, CompletableFuture<List<ICompletionProposal>>> PENDING =
        new ConcurrentHashMap<>();

    @Override
    public List<ICompletionProposal> getClientCompletionProposals(ITextViewer viewer, int offset) {
        RuntimeSnapshot runtime = AiRuntime.get().snapshot();
        if (!runtime.canComplete()) {
            return List.of();
        }

        IDocument document = viewer == null ? null : viewer.getDocument();
        if (document == null) {
            return List.of();
        }

        CodeContext context;
        try {
            context = new AbapContextExtractor().extract(
                document, offset, new AiPreferences().completionSettings()
            );
        } catch (Exception error) {
            AiPlugin.logError("Could not prepare the ABAP completion context.", error);
            return List.of();
        }

        String key = context.fingerprint();
        CompletableFuture<List<ICompletionProposal>> future =
            PENDING.computeIfAbsent(key, ignored -> fetchAsync(context, document, runtime, key));

        try {
            return future.get(MAX_BLOCK_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException stillRunning) {
            return List.of();
        } catch (Exception error) {
            PENDING.remove(key, future);
            return List.of();
        }
    }

    private static CompletableFuture<List<ICompletionProposal>> fetchAsync(
        CodeContext context, IDocument document, RuntimeSnapshot runtime, String key
    ) {
        CompletableFuture<List<ICompletionProposal>> future =
            CompletableFuture.supplyAsync(() -> fetch(context, document, runtime), EXECUTOR);
        future.whenComplete((result, error) -> PENDING.remove(key, future));
        return future;
    }

    private static List<ICompletionProposal> fetch(
        CodeContext context, IDocument document, RuntimeSnapshot runtime
    ) {
        try {
            CompletionResponse response = AiRuntime.get().complete(context, new NullProgressMonitor());
            if (!context.isCurrent(document)) {
                return List.of();
            }
            String insertion = new CompletionSanitizer().sanitize(response.content(), context);
            if (insertion.isBlank()) {
                return List.of();
            }
            return List.of(new CompletionProposal(
                insertion,
                context.cursorOffset(),
                0,
                insertion.length(),
                null,
                "AI suggestion · " + runtime.resolvedModelId(),
                null,
                "Generated by " + response.responseModel()
            ));
        } catch (OperationCanceledException ignored) {
            return List.of();
        } catch (ApiException error) {
            AiPlugin.logError("ABAP AI completion failed: " + error.getMessage(), error);
            return List.of();
        } catch (Exception error) {
            AiPlugin.logError("ABAP AI completion failed.", error);
            return List.of();
        }
    }
}
