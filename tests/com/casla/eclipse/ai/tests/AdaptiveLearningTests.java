package com.casla.eclipse.ai.tests;

import java.util.Properties;

import com.casla.eclipse.ai.learning.CompletionFeedbackStats;
import com.casla.eclipse.ai.learning.ProjectStyleProfile;

/** Focused headless checks for adaptive learning heuristics and feedback aggregates. */
public final class AdaptiveLearningTests {
    private AdaptiveLearningTests() {}

    public static void main(String[] args) {
        learnsModernInlineStyle();
        learnsClassicStyle();
        roundTripsPersistence();
        feedbackStatsRoundTrip();
        feedbackRatesAreStable();
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
        original.generated();
        original.generated();
        original.generated();
        original.acceptedFull();
        original.acceptedWord();
        original.editedAfterAccept();
        original.dismissed();

        Properties properties = new Properties();
        original.store(properties, "feedback.");
        CompletionFeedbackStats restored = new CompletionFeedbackStats();
        restored.load(properties, "feedback.");

        check(restored.generatedCount() == 3, "generated feedback should round-trip");
        check(restored.acceptedFullCount() == 1, "full accepts should round-trip");
        check(restored.acceptedWordCount() == 1, "word accepts should round-trip");
        check(restored.editedAfterAcceptCount() == 1, "post-accept edits should round-trip");
        check(restored.dismissedCount() == 1, "dismissals should round-trip");
    }

    private static void feedbackRatesAreStable() {
        CompletionFeedbackStats stats = new CompletionFeedbackStats();
        check(stats.acceptanceRate() == 0.0, "empty acceptance rate should be zero");
        stats.generated();
        stats.generated();
        stats.acceptedFull();
        check(Math.abs(stats.acceptanceRate() - 0.5) < 0.0001, "acceptance rate should use generated denominator");
        stats.editedAfterAccept();
        check(Math.abs(stats.editAfterAcceptRate() - 1.0) < 0.0001, "edit-after-accept rate should use explicit accepts");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
