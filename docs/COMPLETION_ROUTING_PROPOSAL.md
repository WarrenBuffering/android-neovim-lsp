# Completion Routing Proposal

## Purpose

This document defines the intended completion-routing strategy for `android-neovim-lsp`.

It is written to be easy for an LLM or implementation agent to follow.

The core goal is:

- make ordinary autocomplete fast and local
- keep JetBrains-bridge startup eager so the IDE window flash happens early and ideally only once
- use the JetBrains bridge for completion quality only when local indexed completion is likely to be weak

This proposal is specifically about autocomplete routing. It is not a proposal to remove the JetBrains bridge entirely.

## Current Constraints

- The server already has a local index-based completion path in `CompletionService.completeFromIndex(...)`.
- The server already has JetBrains-bridge-backed semantic completion through `semanticEngine.complete(...)`.
- The JetBrains bridge is intentionally started eagerly during editing/prefetch because the current product preference is to pay the visible bridge-window cost once, early, instead of later during an interactive request.
- The codebase now indexes more than top-level declarations. Assume the local index includes:
  - function parameters
  - enum entries / enum values
  - nearby/local symbols around each symbol
  - enough surrounding symbol context to improve lexical and locality ranking

## High-Level Product Decision

Do not make bridge-backed completion the default for most requests.

Instead:

- keep bridge startup eager
- keep the bridge warm in the background
- route most completion requests through the local index first
- only use bridge-backed completion when the completion context is semantically hard or when the local result is weak

This means:

- startup behavior may still initialize the bridge eagerly
- request-time completion behavior should become more local-first

Those are different decisions and should not be conflated.

## Why This Direction

The local indexed path is expected to be materially faster than bridge-backed completion because it avoids:

- JetBrains IDE RPC for the actual request
- foreground bridge document synchronization
- bridge request scheduling and timeout sensitivity
- cross-process candidate generation and ranking

The JetBrains bridge remains useful because it is still stronger in contexts where completion depends on semantic facts the index does not represent well.

## Desired Routing Model

Use a three-tier routing strategy:

1. local-only for clearly simple contexts
2. local-first with bridge fallback for ambiguous middle cases
3. bridge-first for a small set of known hard contexts

Do not use a binary "everything local" vs "everything bridge" model.

## Local-Only Contexts

The completion request should use local indexed completion only, with no bridge request, for these contexts:

- import completions
- package segment completions
- top-level symbol completions
- library symbol lookup where indexed library symbols are available
- enum entry completions
- named argument suggestions when parameter names are already indexed
- lambda parameter/name suggestions when local symbol context is already indexed
- straightforward member completion where receiver type can be identified cheaply and uniquely from indexed/local context
- completion inside ordinary function bodies where nearby lexical/local declarations provide a strong result set
- completion requests where the local path already returns a strong candidate set with obvious top matches

## Local-First Then Bridge-Fallback Contexts

The completion request should try local indexed completion first, then escalate to the bridge only if the local result is weak, for these contexts:

- ordinary member access where receiver inference succeeds but confidence is not perfect
- extension-heavy contexts where local inference produces plausible but not obviously complete candidates
- DSL / builder-style code where nearby context is strong but overload ranking may still matter
- cases where local completion returns some plausible results, but the ranking may be questionable

Use bridge fallback only if one or more of the escalation rules below trigger.

## Bridge-First Contexts

The completion request should skip local-only completion and go directly to bridge-backed completion for these contexts:

- smart-cast-sensitive completion
- control-flow-sensitive narrowing
- expected-type-sensitive completion where the expected type strongly affects ranking
- ambiguous chained-call receivers
- ambiguous inferred receiver situations
- contexts where extension prioritization depends on deeper semantic facts than the index carries
- cases where overload ranking depends on semantic analysis rather than lexical/index facts

Examples:

- `if (x is Foo) x.<complete>`
- `value?.child?.leaf.<complete>` when intermediate receiver types are not cheap to infer from the index
- assignment / return / call-argument positions where expected type is the main ranking signal

## Escalation Rules

When using the local-first tier, escalate to the bridge if any of the following are true:

- local receiver inference failed
- local receiver inference produced multiple competing receiver types
- local completion returned zero candidates in a member-access context
- local completion returned too few plausible candidates for the context
- local top results are obviously the wrong shape for the context
- local results are dominated by globals/importables when the user is clearly completing receiver members
- the completion site is inside a known flow-sensitive scope
- the expression chain is deep enough that local inference is likely unreliable

The routing code should prefer cheap heuristics. Do not add an expensive pre-analysis step just to decide whether the bridge is needed.

## Confidence Model

Use the following mental model:

- if the server can cheaply identify the receiver and cheaply produce a strong ranked local result, stay local
- if the answer depends on semantic state that the current index model does not represent well, use the bridge
- if the local path returns a thin or suspicious result, escalate

The key principle is:

Only pay for bridge-backed completion when the answer depends on semantic facts the current indexed model does not represent confidently.

## Non-Goals

This proposal does not require:

- removing eager bridge startup
- removing bridge-backed hover or definition
- removing bridge-backed formatting
- removing local/semantic completion merging entirely

This proposal is only about making autocomplete routing more local-first.

## Recommended Implementation Changes

### 1. Change The Default Completion Bias

Today the main completion route in `KotlinLanguageServer` still consults bridge-backed semantic completion broadly.

Change the behavior so that:

- local indexed completion is the default request-time path
- bridge-backed completion is a targeted fallback
- bridge-backed completion is no longer the general answer for ordinary completion requests

### 2. Replace "Member Access => Semantic" With "Hard Context => Semantic"

Do not use "member access" by itself as the main signal for bridge escalation.

Instead, use a richer routing classifier based on:

- import context
- package context
- member access shape
- receiver inference confidence
- chain depth
- flow-sensitive scope detection
- expected-type-sensitive positions
- local result strength

### 3. Add A Small Explicit Routing Classifier

Add a small classifier that returns one of:

- `LOCAL_ONLY`
- `LOCAL_THEN_BRIDGE`
- `BRIDGE_FIRST`

This classifier should be cheap and deterministic.

Suggested placement:

- near `CompletionService`
- or as a small helper used by `KotlinLanguageServer` before calling `semanticEngine.complete(...)`

### 4. Add A Local Result Strength Check

For `LOCAL_THEN_BRIDGE` cases, evaluate the local result before escalating.

Useful cheap signals:

- candidate count
- exact-prefix matches near the top
- whether top results match the expected completion shape
- whether receiver-member candidates are present in a receiver-member context
- whether the best results are local/receiver-scoped vs importable globals

### 5. Keep Bridge Startup Eager

Do not remove current eager bridge startup / prefetch just to optimize completion latency.

Keep:

- eager startup
- warm bridge behavior
- background sync/warmup

The optimization target is request-time completion routing, not bridge availability.

### 6. Instrument The Routing Decisions

Add lightweight logging / counters for:

- completion route chosen
- whether bridge escalation occurred
- candidate counts from local completion
- request latency by route
- bridge request count

This is necessary so we can validate whether the routing split actually improves latency without causing unacceptable completion regressions.

## Recommended Heuristics

Start with simple heuristics instead of ambitious inference.

### Prefer Local Immediately When

- cursor is inside an import
- cursor is completing a package segment
- local index returns strong enum-entry matches
- local index returns parameter-name / named-arg matches
- receiver inference produces one clear receiver type
- local top results are clearly receiver members for the inferred receiver

### Prefer Bridge Immediately When

- syntax indicates likely smart-cast or flow-sensitive narrowing
- receiver inference is ambiguous
- chained receiver inference goes beyond a shallow threshold
- expected type is likely the main discriminator

### Try Local First And Escalate If Needed When

- member access is present but receiver inference is not fully certain
- the code is extension-heavy or DSL-like
- local results exist but may be incomplete or badly ranked

## Expected Benefits

If implemented well, this proposal should:

- reduce average autocomplete latency
- reduce bridge request volume
- preserve eager bridge startup behavior
- preserve strong bridge-backed completions for hard semantic cases
- make ordinary autocomplete more stable and cheaper

## Expected Risks

The main risk is not correctness in the strict sense. The main risk is user-perceived completion quality.

Potential regressions:

- worse ordering for extension functions
- weaker ranking in expected-type-heavy positions
- weaker chained-call completion
- more cases where useful semantic candidates are missing unless fallback triggers

This is why the local-first routing must include:

- bridge fallback for ambiguous contexts
- instrumentation
- targeted tests for known weak areas

## Test Plan

Add or update tests for:

- import completion stays local and fast
- package completion stays local
- enum completion stays local
- named-argument completion stays local
- ordinary receiver-member completion stays local when receiver inference is confident
- ambiguous receiver completion escalates
- smart-cast-sensitive completion goes bridge-first
- expected-type-sensitive completion goes bridge-first
- weak local result triggers bridge fallback
- bridge does not get called for clearly simple contexts

Where possible, assert route selection in addition to result shape.

## Suggested Rollout

Implement in phases.

### Phase 1

- add routing classifier
- add route instrumentation
- keep existing result merging behavior mostly intact
- move import/package/enum/named-arg/simple-member completion to local-first

### Phase 2

- add local-result-strength fallback rules
- reduce unnecessary bridge calls in ordinary member completion

### Phase 3

- tune hard-context detection
- refine ranking and fallback thresholds using real latency and quality data

## Related Follow-Up Work

These items are adjacent and should be tracked separately, but they materially affect the success of this proposal:

- reduce unnecessary third-party support-index invalidation
- improve cache reuse for dependency/source indexes
- narrow semantic warmup / refresh breadth
- add more completion-performance instrumentation and benchmarks

## Final Recommendation

Adopt this policy:

- eager bridge startup stays
- request-time autocomplete becomes local-first
- bridge-backed completion becomes a precision tool for hard semantic contexts

In short:

- use the local index for most autocomplete
- use the bridge when the local model is likely under-informed
- do not pay bridge-request cost for completion contexts the index already handles well
