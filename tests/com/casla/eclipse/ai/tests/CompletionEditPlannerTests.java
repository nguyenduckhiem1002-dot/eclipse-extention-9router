package com.casla.eclipse.ai.tests;

import com.casla.eclipse.ai.completion.CompletionEditPlanner;

/** Regression checks for conservative mid-line completion replacement plans. */
public final class CompletionEditPlannerTests {
    private CompletionEditPlannerTests() {}

    public static void main(String[] args) {
        pureInsertionBeforePunctuation();
        replacesRepeatedIdentifier();
        preservesExistingWhitespace();
        avoidsIdentifierPrefixCollision();
        blankTailKeepsInlineGhost();
        System.out.println("Completion edit planner tests passed");
    }

    private static void pureInsertionBeforePunctuation() {
        var plan = CompletionEditPlanner.plan("iv_id = lv_id", " ).");
        check(plan.replaceLength() == 0, "method-argument completion must stay insertion-only");
        check(plan.suppressInline(), "nonblank same-line tail must use floating preview");
        check("iv_id = lv_id".equals(plan.text()), "insertion text must stay unchanged");
    }

    private static void replacesRepeatedIdentifier() {
        var plan = CompletionEditPlanner.plan("iv_value = lv_value", "iv_value ).");
        check(plan.replaceLength() == "iv_value".length(), "exact repeated identifier should be replaced");
        check("iv_value = lv_value".equals(plan.text()), "planned replacement must preserve model continuation");
        check(plan.suppressInline(), "replacement completion must never paint over real source");
    }

    private static void preservesExistingWhitespace() {
        var plan = CompletionEditPlanner.plan("iv_value = lv_value", "  iv_value ).");
        check(plan.replaceLength() == "  iv_value".length(), "source whitespace plus identifier should be replaced together");
        check(plan.text().startsWith("  iv_value ="), "source-side whitespace must be preserved exactly");
    }

    private static void avoidsIdentifierPrefixCollision() {
        var plan = CompletionEditPlanner.plan("iv_value2 = lv_value", "iv_value ).");
        check(plan.replaceLength() == 0, "similar identifier prefixes must not trigger replacement");
        check(plan.suppressInline(), "unsafe overlay remains suppressed even without replacement");
    }

    private static void blankTailKeepsInlineGhost() {
        var plan = CompletionEditPlanner.plan("lv_total = lv_net + lv_tax.", "   ");
        check(plan.replaceLength() == 0, "blank line tail should remain an insertion");
        check(!plan.suppressInline(), "blank line tail can use ordinary inline ghost paint");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
