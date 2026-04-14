# Runtime Flow And Optimization Notes

## Purpose

This document captures the broader runtime behavior and optimization discussion around `android-neovim-lsp`.

It is intended to preserve recent design decisions in a format that is easy for an LLM or contributor to understand and act on.

This document is broader than `COMPLETION_ROUTING_PROPOSAL.md`.

Use this file for:

- startup flow
- editing flow
- hover / definition / completion / save flow
- bridge usage
- cache usage
- optimization opportunities
- non-goals and product constraints

## Key Product Constraints

These constraints are intentional and should be preserved unless explicitly changed later.

### 1. Eager JetBrains Bridge Startup Is Desired

The current preference is:

- the JetBrains bridge may flash a visible window during startup
- that cost should happen early and ideally only once
- we do not want to defer the visible bridge startup until a later interactive request

Therefore:

- do not optimize by removing eager bridge startup from prefetch/open/edit/save flows
- request-time routing optimizations are preferred over startup-time bridge suppression

### 2. The Main Goal Is To Reduce Unnecessary Work Around The Bridge

The main performance goal is not "remove the bridge".

The goal is:

- keep the bridge warm and available
- reduce unnecessary request-time bridge usage
- reduce unnecessary indexing and cache invalidation work
- make ordinary interactions cheaper when the local index is already good enough

## Persistent Cache Behavior

The server persists multiple caches to disk and reuses them across sessions.

### Persisted Caches

- project model cache
- lightweight workspace/source index
- semantic index cache
- support/dependency symbol cache

On macOS these live under:

- `~/Library/Caches/android-neovim-lsp/project-model`
- `~/Library/Caches/android-neovim-lsp/lightweight-index`
- `~/Library/Caches/android-neovim-lsp/semantic-index`
- `~/Library/Caches/android-neovim-lsp/support-index`

### What Reuses Across Sessions

On startup, the server attempts to preload:

- the imported project model
- the lightweight source index
- the support/dependency cache manifest
- persisted semantic states

This means the server does not always start from zero.

### External Source Jar Extraction

External source jars are also mirrored to disk when needed.

Important nuance:

- extracted external sources are materialized under a temp-dir-based location
- this is still disk-backed caching
- it is not as durable or intentionally managed as the main cache root

## Current Third-Party Library Reindexing Behavior

The current behavior for support/dependency indexing is not "reindex only when dependency version changes".

Instead, support-index reuse and external source extraction reuse are keyed primarily by artifact file metadata such as:

- normalized path
- file size
- file modified time

### Consequence

This is safe, but potentially too sensitive.

A Gradle refresh or reinstall can cause artifacts to be rewritten or retimestamped even when the semantic content is effectively unchanged.

That can cause:

- support-index invalidation
- unnecessary dependency/source reindexing
- unnecessary external-source re-extraction

### Design Conclusion

This is a legitimate optimization target.

We should consider making third-party library cache reuse less sensitive to timestamp churn.

Do not assume "same path means same content", but also do not keep the current metadata sensitivity forever if it causes repeated needless reindexing.

## JetBrains Bridge Usage Today

Outside formatting and linting, the JetBrains bridge is still used.

At minimum, the runtime bridge path still participates in:

- completion
- hover
- definition
- document formatting
- range formatting

This means the bridge is not just a formatter sidecar.

It still matters for request-time feature behavior.

## Current User-Facing Runtime Flows

This section summarizes the flows we traced.

### Initial Startup

During `initialize` / `initialized`, the server:

- reads config
- creates the semantic engine
- resolves the workspace root
- preloads persisted caches
- may schedule project reimport
- may schedule fast source index rebuild
- may schedule support/dependency index refresh
- may schedule semantic module warmup
- may prefetch bridge state for open documents

Important detail:

- startup already attempts cache reuse first
- but still may trigger substantial background work if caches are missing or stale

### After Debounced Typing In A File

On `didChange`, the server:

- updates in-memory document text
- updates the in-memory live source index
- invalidates semantic state for that file/version
- recalculates support packages for that file
- prefetches semantic/bridge state

The semantic engine then:

- may start the bridge if not already started
- queues project warmup and document sync work
- drains those queued bridge syncs after debounce/idle delay

Important detail:

- editing is not immediately doing a full semantic rebuild on every keystroke
- but bridge sync/prefetch behavior is still part of the edit loop

### When Hover Is Requested

For hover:

- memoized request result is checked first
- current combined index is consulted
- local semantic state is tried if available
- bridge-backed hover is used as fallback in some cases

Important detail:

- hover is already partially index-first
- but the bridge still remains in the path

### When Definition Is Requested

For definition:

- memoized request result is checked first
- current combined index is consulted
- local semantic state is tried if available
- bridge-backed definition is used as fallback in some cases

Important detail:

- definition is also partially index-first already
- but the bridge still remains part of the runtime flow

### While Typing For Autocomplete

For completion:

- request memoization is checked
- current indexed completion candidates are computed
- some semantic/local completion may be used
- bridge-backed completion may be called
- results may be merged

Important detail:

- the system already has a local indexed completion path
- but bridge-backed completion is still used too broadly for ordinary requests

### On Save

For source-file save:

- live source index is updated
- lightweight cache persistence is scheduled to disk
- semantic state is invalidated
- bridge prefetch may happen
- semantic refresh may be scheduled

For project-model file save:

- project reimport / fast index refresh may be scheduled

Important detail:

- save already persists useful local cache state
- save also participates in semantic/bridge work

## Completion-Specific Design Decision

Autocomplete should move toward:

- local-first request-time routing
- bridge fallback only when the local model is under-informed

But:

- eager bridge startup should remain

The detailed proposal for this lives in:

- `docs/COMPLETION_ROUTING_PROPOSAL.md`

## Recommended Optimization Priorities

These priorities reflect the product constraint that eager bridge startup stays.

### 1. Completion Routing

Highest-priority improvement:

- make ordinary autocomplete local-first
- reserve bridge-backed completion for hard semantic contexts

This should reduce:

- average completion latency
- bridge request volume
- request-time dependence on bridge responsiveness

### 2. Third-Party Support Index Reuse

Very important improvement:

- reduce unnecessary support-index invalidation for dependency jars and source jars

Why this matters:

- local-first completion depends on high-quality cached library symbols
- repeated dependency reindexing wastes time and undermines cache benefits

### 3. Startup Work Other Than Bridge Startup

Startup should still be examined, but the target is not the eager bridge itself.

Good startup optimizations include:

- reuse cached project/import/index state more aggressively
- avoid broad support-index rebuild when only cache validation is needed
- avoid broad semantic warmup when only one file/module is likely to be touched soon

### 4. Semantic Warmup Breadth

Current semantic warmup can still do broad background module work.

Optimization target:

- narrow semantic warmup scope
- prioritize only active or likely-needed modules first
- avoid broad background analysis when cached semantic/index state is already usable

### 5. Save-Path Work

The save path is already doing useful local persistence, which is good.

Potential improvements:

- avoid broader-than-needed semantic refresh
- keep save-time persistence fast and narrow
- avoid extra work on save that does not materially help the next interaction

## What Not To Optimize First

Do not prioritize these as the first wave of work:

- removing eager bridge startup
- removing bridge-backed hover/definition entirely
- rewriting the entire semantic architecture
- replacing all bridge usage with local logic in one step

These may be future topics, but they are not the best first moves given the current product goals.

## Recommended Strategy For Completion Routing

The current recommendation is:

- local-only for clearly simple contexts
- local-first with bridge fallback for ambiguous middle contexts
- bridge-first for a small set of known hard contexts

Important supporting fact:

the local index is now stronger than before because it includes:

- function parameters
- enum entries / enum values
- nearby/local symbols around each symbol
- richer contextual symbol information

That means the local index should now be trusted for a much larger share of autocomplete traffic.

## Hard Semantic Contexts

Contexts still expected to favor the bridge:

- smart-cast-sensitive code
- control-flow-sensitive narrowing
- ambiguous chained receiver completion
- expected-type-sensitive ranking
- ambiguous inferred receivers
- extension prioritization that depends on deeper semantic facts than the index carries

## Local-Friendly Contexts

Contexts expected to be handled well by the local indexed path:

- imports
- package segments
- top-level symbols
- library symbol lookup
- enum completions
- named arguments
- lambda parameters
- obvious receiver members
- ordinary lexical/local symbol completion
- many straightforward member completions inside function bodies

## Metrics To Add

Before or during optimization work, add instrumentation for:

- completion latency by route
- bridge completion request count
- local-only completion request count
- bridge fallback count
- support-index rebuild count
- semantic warmup duration and scope
- startup cache-hit vs cache-miss rates

Without this, it will be difficult to prove the proposed routing and cache changes are helping.

## Suggested Document Usage

Use this file when an LLM or contributor needs to answer:

- what happens today in runtime flows
- where the bridge is used
- what caches exist
- which optimizations are in scope
- which product constraints must be preserved

Use `COMPLETION_ROUTING_PROPOSAL.md` when implementing the narrower autocomplete-routing changes.

## Final Summary

The current direction is:

- keep eager bridge startup
- optimize request-time behavior instead of hiding the bridge
- rely more on the stronger local index for ordinary autocomplete
- preserve the bridge for hard semantic contexts
- reduce unnecessary dependency/support-index churn
- reduce unnecessary broad warmup/reanalysis work

This is the intended optimization strategy unless future product decisions explicitly change the bridge-startup preference.
