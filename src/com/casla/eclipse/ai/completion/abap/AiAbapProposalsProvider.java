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
import com.casla.eclipse.ai.completion.CompletionCache;
import com.casla.eclipse.ai.completion.GhostTextController;
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
 * freeze, long enough to catch a fast response.
 *
 * The result cache is CompletionCache, shared with GhostTextController's
 * automatic suggestions (same document+offset+shape key): whichever path
 * answers a position first serves the other. When a request outlives the
 * block window, it keeps running in the background; on arrival its answer is
 * still cached and additionally handed to GhostTextController.offerCompletion
 * so it shows up as ghost text a moment later instead of being discarded --
 * the popup already gave up, but the editor doesn't have to lose the answer.
 */
public final class AiAbapProposalsProvider implements IClientProposalsProvider {
    private static final long MAX_BLOCK_MILLIS = 300;

    // Daemon threads: nothing outside this class references AiPlugin, so
    // there is no shutdown hook to release this executor explicitly without
    // creating a hard dependency from the core plugin onto ADT's optional
    // classes. Daemon threads exit with the JVM on their own.
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "ai-abap-completion");
        thread.setDaemon(true);
        return thread;
    });

    private static final ConcurrentHashMap<String, CompletableFuture<String>> PENDING =
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

        // Tier 1: deterministic, no network, no cache needed.
        String local = AbapLocalCompleter.suggest(document, offset);
        if (!local.isBlank()) {
            return List.of(buildLocalProposal(local, offset));
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

        String key = GhostTextController.cacheKey(context, true);
        String cachedInsertion = CompletionCache.get().get(key);
        if (cachedInsertion != null && !cachedInsertion.isBlank()) {
            return List.of(buildProposal(cachedInsertion, context, runtime));
        }

        CompletableFuture<String> future =
            PENDING.computeIfAbsent(key, ignored -> fetchAsync(context, document, key));

        try {
            String insertion = future.get(MAX_BLOCK_MILLIS, TimeUnit.MILLISECONDS);
            return insertion == null || insertion.isBlank() ? List.of() : List.of(buildProposal(insertion, context, runtime));
        } catch (TimeoutException stillRunning) {
            // The popup's patience ran out, not the request itself: when it
            // lands, offer it to ghost text instead of throwing it away.
            IDocument requestDocument = document;
            future.thenAccept(insertion -> GhostTextController.get().offerCompletion(requestDocument, context, insertion));
            return List.of();
        } catch (Exception error) {
            PENDING.remove(key, future);
            return List.of();
        }
    }

    private static CompletableFuture<String> fetchAsync(CodeContext context, IDocument document, String key) {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> fetch(context, document), EXECUTOR);
        future.whenComplete((insertion, error) -> {
            PENDING.remove(key, future);
            if (error == null && insertion != null && !insertion.isBlank()) {
                CompletionCache.get().put(key, insertion);
            }
        });
        return future;
    }

    private static String fetch(CodeContext context, IDocument document) {
        try {
            CompletionResponse response = AiRuntime.get().complete(context, new NullProgressMonitor(), true);
            if (!context.isCurrent(document)) {
                return "";
            }
            // Already sanitized (and confirmed non-blank) by AiRuntime.complete().
            return response.content();
        } catch (OperationCanceledException ignored) {
            return "";
        } catch (ApiException error) {
            logCompletionError(error);
            return "";
        } catch (Exception error) {
            AiPlugin.logError("ABAP AI completion failed.", error);
            return "";
        }
    }

    /** EMPTY_COMPLETION is a routine "model had nothing to add" outcome, not a fault -- logging it as an error is just noise. */
    private static void logCompletionError(ApiException error) {
        if ("EMPTY_COMPLETION".equals(error.errorCode())) return;
        AiPlugin.logError("ABAP AI completion failed: " + error.getMessage(), error);
    }

    private static ICompletionProposal buildProposal(String insertion, CodeContext context, RuntimeSnapshot runtime) {
        return new CompletionProposal(
            insertion,
            context.cursorOffset(),
            0,
            insertion.length(),
            null,
            "AI suggestion · " + runtime.resolvedModelId(),
            null,
            "Generated by " + runtime.resolvedModelId()
        );
    }

    /** Tier 1 answers are deterministic, not AI-generated -- labeled distinctly rather than implying a model produced them. */
    private static ICompletionProposal buildLocalProposal(String insertion, int offset) {
        return new CompletionProposal(
            insertion, offset, 0, insertion.length(), null, "AI suggestion · local", null, "Deterministic block completion"
        );
    }
}
