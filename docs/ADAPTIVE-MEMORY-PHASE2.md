# Adaptive Memory Phase 2 — Completion Feedback

Phase 2 turns completion usage into local quality signals. It does not fine-tune a model and does not persist source code or generated completion text.

## Signals

- `GENERATED`: sanitized and structurally validated ABAP completion became eligible for presentation.
- `ACCEPT_FULL`: the whole remaining completion was inserted in one edit.
- `ACCEPT_WORD`: the next completion word was inserted as one edit.
- `ACCEPT_LINE`: the next completion line was inserted as one edit.
- `TYPED_MATCH`: the developer manually typed text matching the pending completion.
- `DISMISSED`: editing diverged from the pending completion or the editor was left.
- `SUPERSEDED`: another validated completion replaced a still-pending candidate.
- `EDITED_AFTER_ACCEPT`: an explicitly accepted region was modified within the short post-accept observation window.

## Data flow

```text
AiRuntime
  -> sanitize + ValidationPipeline
  -> CompletionFeedbackTracker.generated(...)

AdaptiveLearningController
  -> DocumentEvent
  -> CompletionFeedbackTracker.documentChanged(...)
  -> classify acceptance/edit behavior
  -> AdaptiveLearningStore.recordFeedback(...)
  -> aggregate totals + per-model counters
```

## Privacy

Only counters and model IDs are persisted in `adaptive-learning.properties`. Pending completion text exists only in process memory for at most 15 seconds. Accepted-region metadata exists only in memory for at most 10 seconds. Source code, prompts, response bodies and API keys are never written by the feedback subsystem.

## Why this is useful

The existing `lastKnownGoodModel` answers only whether a model returned usable code. Phase 2 creates signals that can later answer whether developers actually use that code. Phase 5 can use these aggregates for adaptive model routing without changing model weights.

## Known inference limits

The feedback layer intentionally observes `DocumentEvent` rather than keyboard shortcuts. This keeps it independent of ghost rendering and also covers ADT popup insertion. A paste that exactly equals a completion can therefore look like an accept. This is acceptable for aggregate learning and avoids invasive coupling to SWT key handling.
