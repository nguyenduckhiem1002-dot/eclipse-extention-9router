package com.casla.eclipse.ai.tests;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.casla.eclipse.ai.api.ModelInfo;
import com.casla.eclipse.ai.completion.CodeContext;
import com.casla.eclipse.ai.completion.CompletionReranker;
import com.casla.eclipse.ai.completion.CursorContextType;
import com.casla.eclipse.ai.learning.AcceptedExampleMemory;
import com.casla.eclipse.ai.learning.AdaptiveLearningStore;
import com.casla.eclipse.ai.learning.CompletionContextClassifier;
import com.casla.eclipse.ai.learning.CompletionFeedbackStats;
import com.casla.eclipse.ai.learning.CompletionFeedbackTracker.FeedbackEvent;
import com.casla.eclipse.ai.learning.ObservedAbapObjectIndex;
import com.casla.eclipse.ai.learning.ProjectStyleProfile;
import com.casla.eclipse.ai.runtime.AdaptiveModelRouter;
import com.casla.eclipse.ai.runtime.ModelResolver;

/** Focused headless checks for adaptive learning, memory, routing and reranking. */
public final class AdaptiveLearningTests {
    private AdaptiveLearningTests() {}

    public static void main(String[] args) {
        learnsModernInlineStyle();
        learnsClassicStyle();
        roundTripsPersistence();
        feedbackStatsRoundTrip();
        feedbackRatesAreStable();
        acceptedExampleRetrieval();
        objectIndexRetrievalAndPersistence();
        contextClassifierFindsRapAndSql();
        adaptiveRouterLearnsBetterModel();
        rerankerPrefersLearnedStyle();
        System.out.println("Adaptive learning tests passed");
    }

    private static void learnsModernInlineStyle() {
        ProjectStyleProfile profile = new ProjectStyleProfile();
        String source = """
            METHOD run.
              DATA(result) = VALUE string( ).
              DATA(item) = items[ id = iv_id ].
              IF line_exists( items[ id = iv_id ] ).
                DATA(copy) = CORRESPONDING #( item ).
              ENDIF.
            ENDMETHOD.
            """;
        profile.observeAbap(source);
        profile.observeAbap(source + "\nDATA(extra) = COND string( WHEN item IS INITIAL THEN `x` ).");
        profile.observeAbap(source + "\nDATA(other) = REDUCE i( INIT x = 0 FOR row IN items NEXT x += 1 ).");
        String hints = profile.promptHints();
        check(hints.contains("inline DATA"), "modern profile should prefer inline DATA");
        check(hints.contains("table expressions"), "modern profile should prefer table expressions");
        check(hints.contains("modern ABAP"), "modern profile should mention modern ABAP expressions");
        check(hints.contains("uppercase"), "modern profile should preserve uppercase keywords");
    }

    private static void learnsClassicStyle() {
        ProjectStyleProfile profile = new ProjectStyleProfile();
        String source = """
            METHOD run.
              DATA lv_value TYPE string.
              DATA ls_item TYPE ty_item.
              READ TABLE lt_items INTO ls_item WITH KEY id = iv_id.
              IF sy-subrc = 0.
                lv_value = ls_item-name.
              ENDIF.
            ENDMETHOD.
            """;
        profile.observeAbap(source);
        profile.observeAbap(source.replace("lv_value", "lv_name"));
        profile.observeAbap(source.replace("lv_value", "lv_text"));
        String hints = profile.promptHints();
        check(hints.contains("explicit DATA"), "classic profile should prefer explicit declarations");
        check(hints.contains("READ TABLE"), "classic profile should preserve READ TABLE style");
    }

    private static void roundTripsPersistence() {
        ProjectStyleProfile original = new ProjectStyleProfile();
        String source = "METHOD run.\nDATA(value) = VALUE string( ).\nENDMETHOD.";
        original.observeAbap(source);
        original.observeAbap(source + "\nDATA(other) = VALUE string( ).");
        original.observeAbap(source + "\nDATA(third) = VALUE string( ).");
        Properties properties = new Properties();
        original.store(properties);
        ProjectStyleProfile restored = new ProjectStyleProfile();
        restored.load(properties);
        check(restored.observations() == original.observations(), "observation count should round-trip");
        check(restored.promptHints().equals(original.promptHints()), "learned hints should round-trip");
    }

    private static void feedbackStatsRoundTrip() {
        CompletionFeedbackStats original = new CompletionFeedbackStats();
        original.generated(); original.generated(); original.generated(); original.acceptedFull(); original.acceptedWord(); original.editedAfterAccept(); original.dismissed();
        Properties properties = new Properties(); original.store(properties, "feedback.");
        CompletionFeedbackStats restored = new CompletionFeedbackStats(); restored.load(properties, "feedback.");
        check(restored.generatedCount() == 3, "generated feedback should round-trip");
        check(restored.acceptedFullCount() == 1, "full accepts should round-trip");
        check(restored.acceptedWordCount() == 1, "word accepts should round-trip");
        check(restored.editedAfterAcceptCount() == 1, "post-accept edits should round-trip");
        check(restored.dismissedCount() == 1, "dismissals should round-trip");
    }

    private static void feedbackRatesAreStable() {
        CompletionFeedbackStats stats = new CompletionFeedbackStats();
        check(stats.acceptanceRate() == 0.0, "empty acceptance rate should be zero");
        stats.generated(); stats.generated(); stats.acceptedFull();
        check(Math.abs(stats.acceptanceRate() - 0.5) < 0.0001, "acceptance rate should use generated denominator");
        stats.editedAfterAccept();
        check(Math.abs(stats.editAfterAcceptRate() - 1.0) < 0.0001, "edit-after-accept rate should use explicit accepts");
    }

    private static void acceptedExampleRetrieval() {
        AcceptedExampleMemory memory = new AcceptedExampleMemory();
        memory.remember("ZCL_ORDER", "METHOD update", "MODIFY ENTITIES OF zi_order IN LOCAL MODE ENTITY Order UPDATE FIELDS ( status ) WITH VALUE #( ( %tky = key-%tky status = 'DONE' ) ).", "model-a");
        memory.remember("ZCL_MISC", "METHOD calc", "DATA(result) = REDUCE i( INIT x = 0 FOR row IN rows NEXT x += row-value ).", "model-b");
        var result = memory.retrieve(context("ZCL_ORDER", "METHOD update", "READ ENTITIES OF zi_order IN LOCAL MODE\n"), 2);
        check(!result.isEmpty() && result.get(0).snippet().contains("MODIFY ENTITIES"), "RAP accepted example should rank first");
        Properties p = new Properties(); memory.store(p); AcceptedExampleMemory restored = new AcceptedExampleMemory(); restored.load(p);
        check(restored.size() == 2, "accepted examples should persist");
    }

    private static void objectIndexRetrievalAndPersistence() {
        ObservedAbapObjectIndex index = new ObservedAbapObjectIndex();
        index.observe("ZCL_ORDER_HELPER", "CLASS zcl_order_helper DEFINITION. PUBLIC SECTION. METHODS get_order IMPORTING iv_id TYPE string. ENDCLASS.");
        index.observe("ZCL_OTHER", "CLASS zcl_other DEFINITION. PUBLIC SECTION. METHODS ping. ENDCLASS.");
        var found = index.retrieve(context("ZCL_CALLER", "METHOD run", "DATA(order) = zcl_order_helper=>"), 2);
        check(!found.isEmpty() && found.get(0).objectKey().contains("ORDER_HELPER"), "related object skeleton should be retrievable");
        Properties p = new Properties(); index.store(p); ObservedAbapObjectIndex restored = new ObservedAbapObjectIndex(); restored.load(p);
        check(restored.size() == 2, "object index should persist");
    }

    private static void contextClassifierFindsRapAndSql() {
        check("abap-rap".equals(CompletionContextClassifier.bucket(context("ZBP", "METHOD modify", "READ ENTITIES OF zi_order IN LOCAL MODE"))), "RAP context bucket");
        check("abap-sql".equals(CompletionContextClassifier.bucket(context("ZCL", "METHOD read", "SELECT * FROM mara WHERE"))), "SQL context bucket");
    }

    private static void adaptiveRouterLearnsBetterModel() {
        AdaptiveLearningStore store = AdaptiveLearningStore.get();
        store.reset();
        String bucket = "abap-method";
        for (int i = 0; i < 12; i++) {
            store.recordFeedback("ag/a-flash-low", bucket, FeedbackEvent.GENERATED);
            store.recordFeedback("ag/a-flash-low", bucket, FeedbackEvent.DISMISSED);
            store.recordFeedback("ag/b-flash-low", bucket, FeedbackEvent.GENERATED);
            store.recordFeedback("ag/b-flash-low", bucket, FeedbackEvent.ACCEPT_FULL);
        }
        List<ModelInfo> models = List.of(model("ag/a-flash-low"), model("ag/b-flash-low"));
        String selected = new AdaptiveModelRouter(new ModelResolver()).resolve(models, "", Set.of(), context("ZCL", "METHOD run", "DATA(x) = ")).orElseThrow();
        check("ag/b-flash-low".equals(selected), "adaptive router should prefer empirically accepted model");
        store.reset();
    }

    private static void rerankerPrefersLearnedStyle() {
        AdaptiveLearningStore store = AdaptiveLearningStore.get();
        store.reset();
        String modern = "METHOD run.\nDATA(value) = VALUE string( ).\nDATA(item) = items[ id = iv_id ].\nENDMETHOD.";
        store.observeDocument("ZCL", "ABAP", modern);
        store.observeDocument("ZCL2", "ABAP", modern + "\nDATA(x) = VALUE string( ).");
        store.observeDocument("ZCL3", "ABAP", modern + "\nDATA(y) = items[ id = 1 ].");
        CompletionReranker reranker = new CompletionReranker();
        var best = reranker.best(context("ZCL4", "METHOD run", ""), List.of(
            new CompletionReranker.Candidate("DATA lv_value TYPE string.", "m", 10),
            new CompletionReranker.Candidate("DATA(value) = VALUE string( ).", "m", 10)
        ));
        check(best != null && best.text().startsWith("DATA("), "reranker should prefer learned inline style");
        store.reset();
    }

    private static CodeContext context(String file, String structure, String before) {
        return new CodeContext("", file, "ABAP", "", "", structure, before, "", before.length(), 0L, "fp", CursorContextType.CODE, List.of());
    }
    private static ModelInfo model(String id) { return new ModelInfo(id, "test", Map.of()); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
