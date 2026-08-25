# Adaptive Memory Plan — Eclipse Extension 9Router

## Goal

Make inline completion improve with continued use without online fine-tuning. The assistant should learn coding conventions, successful completion patterns, relevant ABAP objects and model quality from local interaction data while keeping source code private.

## Principles

1. Keep model weights unchanged in early phases; adapt through context, retrieval and ranking.
2. Store only local data by default; never upload telemetry or source history automatically.
3. Prefer aggregate metrics over raw source persistence.
4. Every learned behavior must be resettable and bounded.
5. Learning must never block the Eclipse UI thread.
6. Suggestions still pass the existing sanitizer and validation pipeline; memory may guide generation but never bypass safety checks.

## Phase 1 — Workspace style learning (implemented on this branch)

### Objective

Create the first closed learning loop:

`real edited ABAP -> local style profile -> prompt hints -> future completions`

### Components

#### `learning/AdaptiveLearningController`

- Attaches to the active Eclipse text editor.
- Detects ABAP editors with the existing `RelatedFileCollector.isAbapEditor` heuristic.
- Debounces document changes for 900 ms so analysis happens only after typing settles.
- Copies the stable document on the UI thread and performs analysis on one daemon background executor.
- Observes the initial document after opening as well as later edits.
- Cancels stale scheduled observations when the user changes editor or keeps typing.

#### `learning/ProjectStyleProfile`

Learns these conventions with exponential moving averages:

- inline `DATA(...)` vs explicit `DATA name TYPE ...`;
- table expressions vs `READ TABLE`;
- modern expressions (`VALUE`, `CORRESPONDING`, `COND`, `REDUCE`, `NEW`, `line_exists`, `line_index`);
- uppercase vs lowercase ABAP keywords;
- common SAP naming prefixes (`lv_`, `lt_`, `ls_`, `lo_`, `iv_`, `it_`, etc.).

The profile starts influencing prompts only after at least three meaningful observations to avoid overfitting to one small file.

#### `learning/AdaptiveLearningStore`

- Singleton local memory service.
- Persists only aggregate metrics to Eclipse plugin state as `adaptive-learning.properties`.
- Does not persist source code, prompts or generated completions.
- Deduplicates identical document snapshots during a session.
- Provides `reset()` for future UI wiring.

#### `CompletionPromptBuilder`

For ABAP completions, inserts a `Learned coding preferences from this workspace` section only when the profile has enough evidence. Existing cursor context, scope, method signature and related-file context remain unchanged.

### Acceptance criteria

- No learned profile -> prompt behavior is unchanged.
- After repeated modern-style ABAP observations, prompt requests modern syntax and matching naming/case conventions.
- Learning analysis never performs network I/O.
- Stored state contains metrics only.
- State survives Eclipse restart.
- Test sources compile in the existing build and focused headless tests cover style inference and persistence round-trip.

## Phase 2 — Explicit completion feedback

### Goal

Distinguish "valid model output" from "code the developer actually wanted".

### Changes

1. Replace the minimal ghost record with suggestion metadata:
   - suggestion id;
   - model id;
   - source (`local`, `cache`, `model`);
   - object/file identity;
   - context fingerprint;
   - generation latency;
   - displayed timestamp.
2. Add `SuggestionFeedbackStore` events:
   - generated;
   - displayed;
   - accept-full;
   - accept-word;
   - accept-line;
   - typed-match;
   - dismissed;
   - superseded;
   - edited-after-accept.
3. Keep raw completion text ephemeral. Persist counters and compact feature hashes by default.
4. Detect edits to a recently accepted insertion region for a short time window so `accepted then heavily edited` is not counted as perfect success.

### Metrics

- display rate;
- full/partial acceptance rate;
- typed-match rate;
- post-accept edit rate;
- cancellation rate;
- latency P50/P95;
- sanitizer/validation rejection rate by model.

## Phase 3 — Accepted example memory

### Goal

Retrieve successful project-specific coding patterns without fine-tuning.

### Design

1. Keep a bounded local memory (for example 200-500 examples).
2. Store only examples that were fully accepted or had low post-accept edit distance.
3. Normalize literals and identifiers before indexing when possible.
4. Rank by:
   - language/object type;
   - enclosing method/section;
   - keyword overlap;
   - identifier overlap;
   - structure hint;
   - recency and historical success.
5. Inject at most 2-3 short examples into the prompt under a strict token budget.
6. Add TTL/decay and per-object quotas so one large class cannot dominate memory.

Start with lexical/BM25-style ranking. Add embeddings only if this proves insufficient.

## Phase 4 — Observed ABAP object index

### Goal

Remember useful object structure after an editor is closed.

### Indexed data

- object name;
- class/interface declarations;
- public/protected/private sections;
- methods and signatures;
- attributes, constants and types;
- CDS/entity names when visible in source;
- lightweight call/reference tokens.

### Constraints

- Store skeletons, not complete source by default.
- Re-index only when a document modification hash changes.
- Keep object count and total bytes bounded.
- Rank current method signature and current scope above remembered objects.

## Phase 5 — Adaptive model router

### Goal

Replace the current strong `lastKnownGood` bias with measured quality.

### Candidate score

`static capability + acceptance quality + context success - latency - rejection/error penalties`

Track separate buckets for contexts such as:

- simple ABAP statement;
- method body;
- Open SQL;
- RAP behavior implementation;
- CDS/text artifacts where supported;
- Java completion.

Use a minimum sample threshold and Bayesian/prior smoothing so a model is not promoted after one lucky completion.

## Phase 6 — Completion reranking

When the gateway can provide multiple candidates cheaply, rank them locally using:

1. existing structural validation;
2. scope/identifier compatibility;
3. learned coding-style match;
4. similarity to accepted examples;
5. historical model/context success;
6. length/latency penalties.

Do not require JSON output from the generation model; keep the current plain-text completion contract.

## Phase 7 — Controls and diagnostics

Add Preferences controls:

- Enable adaptive learning;
- Pause learning;
- Reset learned profile;
- Reset accepted examples;
- maximum local memory size;
- diagnostics view showing aggregate metrics only.

Diagnostics should show enough information to answer whether a model or feature actually improves acceptance rate without exposing source code.

## Data retention and privacy

Default retention policy:

- style profile: aggregate values only, persistent until reset;
- feedback counters: aggregate values, rolling window where useful;
- accepted examples: bounded and local-only, opt-out available before enabling raw snippets;
- observed object index: bounded skeletons;
- no telemetry upload;
- no API keys, prompts or authorization headers in learning storage.

## Recommended implementation order after Phase 1

1. Explicit feedback events and post-accept edit tracking.
2. Diagnostics counters to validate that learning helps.
3. Accepted-example retrieval.
4. Persistent ABAP object skeleton index.
5. Adaptive model router.
6. Multi-candidate reranking.
7. Optional DDIC/CDS metadata spike through ADT mechanisms only if text/object memory still leaves a measurable gap.
