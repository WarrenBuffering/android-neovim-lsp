# Diagnostics Routing Proposal

## Purpose

This document defines an explicit diagnostics-routing strategy for `android-neovim-lsp`.

It is written to be easy for an LLM or contributor to understand and implement.

The core goal is:

- make obvious diagnostics feel close to instantaneous
- avoid broad expensive work on every edit
- preserve deeper semantic diagnostics without letting them block or slow ordinary typing

This proposal is primarily about diagnostics and lint-style feedback while editing.

## Product Goal

Users should get very fast feedback for common mistakes such as:

- bad imports
- syntax errors
- obvious unresolved names
- obvious unresolved member access when the receiver is locally understood

The server should not bog down the whole program just to make those squiggles appear quickly.

The intended design is:

- cheap provisional diagnostics immediately
- deeper authoritative diagnostics asynchronously

## High-Level Model

Use three diagnostic tiers:

1. `EDIT_FAST`
2. `EDIT_SEMANTIC`
3. `SAVE_OR_IDLE_FULL`

These tiers must have strict rules about what work they are allowed to trigger.

## Tier 1: `EDIT_FAST`

### Purpose

Provide near-instant feedback during active typing.

### Target Latency

- ideal: under `50ms`
- acceptable: under `100ms`

### Debounce

- `25ms` to `75ms`

This should be short enough to feel immediate, but not literally every keystroke with no debounce.

### Allowed Inputs

This tier may only use:

- current document text
- current open-document version
- local lexical symbol information
- current combined index
- last known good semantic snapshot only if it is already available

### Allowed Work

This tier may:

- parse the current file cheaply
- compute syntax errors in the current file
- validate import lines against the current combined index
- detect duplicate imports
- detect obvious unresolved identifiers from local lexical + index lookup
- detect obvious unresolved member access when the receiver can be identified cheaply and confidently
- detect simple package/source-root mismatch issues

### Forbidden Work

This tier must not:

- trigger project reimport
- trigger full module semantic rebuild
- trigger broad dependency/support reindexing
- trigger broad semantic warmup
- wait on JetBrains bridge round-trips
- scan the workspace broadly
- re-run expensive compiler-backed analysis from scratch on every edit

### Diagnostic Quality Rules

This tier should emit only diagnostics with high confidence.

If local confidence is low:

- do not emit a strong error
- leave the question to `EDIT_SEMANTIC`

### Typical Diagnostics In This Tier

- Kotlin syntax errors
- malformed import statement
- unresolved import package segment
- unresolved import leaf symbol
- duplicate import
- unresolved local name when no local/index candidate exists
- unresolved simple receiver member when the receiver type is obvious and the member is absent from the indexed receiver symbols

## Tier 2: `EDIT_SEMANTIC`

### Purpose

Provide more accurate module-aware semantic diagnostics while the user is still editing, but without trying to keep up with every keystroke.

### Target Latency

- ideal: under `300ms`
- acceptable: under `700ms`

### Debounce

- `250ms` to `500ms`

### Allowed Inputs

This tier may use:

- current document text
- active/open documents
- focused module project model
- latest reusable semantic state
- semantic engine / compiler-backed analysis

### Allowed Work

This tier may:

- run focused semantic analysis for the current file or current module
- refine or replace provisional `EDIT_FAST` diagnostics
- produce type-aware unresolved symbol diagnostics
- produce type mismatch diagnostics
- produce overload ambiguity diagnostics
- produce smarter import suggestions
- produce more accurate member diagnostics in ambiguous contexts

### Forbidden Work

This tier should still avoid:

- project-wide reimport for ordinary source edits
- broad dependency/source reindexing
- broad all-modules semantic rebuild
- expensive work unrelated to the active file/module

### Scope Rule

Prefer:

- current file
- active module
- focused paths

Do not broaden scope unless the edit actually requires it.

## Tier 3: `SAVE_OR_IDLE_FULL`

### Purpose

Perform broader or more expensive correctness work when the user is idle long enough or when a save event justifies it.

### Trigger Conditions

- explicit save
- long idle window
- project model changes
- build file changes
- explicit reimport / reload command

### Allowed Work

This tier may:

- refresh semantic state more broadly
- validate cross-file/module issues more thoroughly
- do broader module analysis
- reconcile provisional vs semantic diagnostics
- update slower diagnostics that are too expensive for edit-time paths

### Forbidden Work

Even here, do not do unrelated work unless necessary.

If only a single file changed, do not always expand to a broad workspace rebuild.

## Core Design Rule

The server should never require broad semantic work to make obvious red squiggles appear.

Instead:

- emit cheap provisional diagnostics fast
- replace them later with authoritative semantic diagnostics

This is the central principle of the proposal.

## Import Diagnostics Spec

Bad imports are a first-class optimization target because users notice them immediately.

### Fast Import Validation

On every `EDIT_FAST` pass:

1. parse each `import ...` line in the current file
2. classify the import as:
   - malformed syntax
   - unresolved package segment
   - unresolved leaf symbol
   - ambiguous / uncertain
   - valid
3. validate using the current combined index

### Local Validation Rules

Treat the import as a strong local error if:

- a package segment does not exist in indexed packages
- the leaf symbol does not exist in indexed importable symbols
- the import syntax itself is malformed

Treat the import as uncertain if:

- the local index is known to be incomplete for the relevant scope
- the import depends on a semantic condition the local model may not reflect

### Semantic Follow-Up

`EDIT_SEMANTIC` may:

- confirm or clear the fast import error
- provide a better message
- suggest replacement imports

## Unresolved Name Diagnostics Spec

### Fast Local Name Validation

On every `EDIT_FAST` pass:

- resolve local lexical names from nearby indexed/local symbols
- resolve imported/global names from the current combined index

Emit an unresolved-name diagnostic only if:

- the name is not found locally
- the name is not found in imported/global indexed candidates
- the context is not obviously semantic/flow-sensitive

### Local Confidence Rule

If there is any reasonable chance that semantic analysis would supply the answer, do not emit a strong fast error.

Examples where fast unresolved diagnostics should usually be conservative:

- smart-cast-sensitive references
- expected-type-sensitive expressions
- ambiguous chained receivers
- extension-heavy resolution

## Receiver-Member Diagnostics Spec

### Fast Simple Member Validation

Allow `EDIT_FAST` to validate member access only when:

- the receiver type can be inferred cheaply and uniquely
- the receiver is not part of a deep ambiguous chain
- the current combined index has receiver-member symbols for that type

Emit a fast unresolved-member diagnostic only if:

- receiver inference is confident
- no plausible receiver member exists in the local/indexed candidate set

### Do Not Use Fast Diagnostics For

- ambiguous chained calls
- smart-cast-sensitive receiver types
- deep extension-resolution scenarios
- nullable/control-flow-sensitive receiver narrowing

Those cases belong to `EDIT_SEMANTIC`.

## Diagnostic Precedence Rules

Diagnostics from later tiers should be able to replace earlier-tier diagnostics.

### Precedence

- `SAVE_OR_IDLE_FULL` overrides `EDIT_SEMANTIC`
- `EDIT_SEMANTIC` overrides `EDIT_FAST`
- `EDIT_FAST` should be considered provisional

### Publishing Rule

The server should avoid flicker when replacing provisional diagnostics.

Good behavior:

- stable syntax/import/local unresolved squiggles appear quickly
- semantic layer either confirms them or replaces them
- low-confidence provisional diagnostics are avoided so false positives stay low

## State Model

Maintain diagnostics by source tier.

Suggested conceptual structure:

- `fastDiagnosticsByUri`
- `semanticDiagnosticsByUri`
- `fullDiagnosticsByUri`

The published diagnostics for a URI should be a merge according to precedence rules, not a flat overwrite by whichever tier finished last without context.

## Diagnostics-Driven Code Actions

Diagnostics are not the same thing as code actions, but fast diagnostics should be able to unlock fast code actions.

The intended model is:

- diagnostics identify a problem or opportunity
- code actions provide one or more possible fixes
- the server should not require the heaviest semantic path just to offer obvious local fixes

### Code Action Tiers

Use three parallel code-action tiers:

1. `FAST_CODE_ACTIONS`
2. `SEMANTIC_CODE_ACTIONS`
3. `FULL_CODE_ACTIONS`

These tiers should align with the diagnostic tiers.

### `FAST_CODE_ACTIONS`

This tier should be backed by:

- `EDIT_FAST` diagnostics
- current file text
- local lexical/index context
- current combined index

This tier should be cheap and immediate.

#### Good Candidates For `FAST_CODE_ACTIONS`

- add missing import
- choose from a small list of indexed import candidates
- remove invalid import
- remove duplicate import
- organize imports
- replace unresolved symbol with a nearby indexed symbol when confidence is high

#### Forbidden Work For `FAST_CODE_ACTIONS`

This tier must not:

- trigger broad semantic analysis
- trigger project reimport
- trigger support-index rebuild
- wait for a bridge round-trip just to populate the list

### `SEMANTIC_CODE_ACTIONS`

This tier should be backed by:

- `EDIT_SEMANTIC` diagnostics
- focused semantic state
- current file/module analysis

#### Good Candidates For `SEMANTIC_CODE_ACTIONS`

- better import disambiguation
- explicit type annotation
- explicit return type
- ambiguity fixes
- more precise replacement suggestions
- local semantic refactors that are current-file or current-module scoped

### `FULL_CODE_ACTIONS`

This tier is for:

- broader refactors
- cross-file maintenance edits
- expensive fix-all style actions
- operations that need broader validation

### Diagnostic-To-Action Mapping

The server should explicitly map common diagnostics to action families.

#### Fast Diagnostic -> Fast Code Action Examples

- malformed import
  - remove import
  - rewrite import if a clear indexed target exists

- unresolved import
  - suggest one or more import candidates from the local index
  - remove import

- duplicate import
  - remove duplicate
  - organize imports

- obvious unresolved symbol
  - add import candidate(s)
  - replace with nearby indexed/local symbol if confidence is high

- obvious unresolved top-level reference
  - suggest indexed symbol imports

#### Cases Where Fast Code Actions Should Stay Conservative

Do not offer strong immediate actions when the context is:

- smart-cast-sensitive
- control-flow-sensitive
- ambiguous chained member resolution
- extension-heavy with low confidence
- semantically ambiguous enough that the suggested edit could be misleading

In those cases, defer action generation to `SEMANTIC_CODE_ACTIONS`.

### Metadata Requirements

Fast diagnostics should carry enough metadata to support fast code actions without recomputing everything.

Useful metadata includes:

- unresolved symbol name
- diagnostic tier
- confidence
- candidate import fqNames
- nearby replacement candidates
- quick classification of the problem kind

## Neovim-Friendly Code Action UX

Android Studio often shows multiple options for a diagnostic or intention.

For Neovim, the UX should be:

- fast to open
- keyboard-first
- easy to focus
- easy to preview
- easy to accept one option

Do not assume a mouse or a large floating toolbox UI.

### UX Goal

Make code actions feel like:

- a lightweight focused picker
- opened near the cursor or diagnostic site
- navigable with simple directional keys
- confirmable with one accept key
- dismissible instantly

### Recommended Interaction Model

When one or more code actions exist for the current cursor/diagnostic:

1. open a small action picker
2. move focus into the picker explicitly
3. let the user navigate actions with up/down style movement
4. show a compact preview or summary for the currently highlighted action
5. allow immediate acceptance of the selected action
6. allow quick dismissal and return focus to the editor

### Focus Rules

The picker should have a clear focus model.

Recommended rules:

- opening the picker moves focus into the picker
- dismissing the picker returns focus to the original editor window and cursor position
- accepting an action also returns focus to the editor after edits apply
- opening the picker should not leave the user uncertain about whether movement keys affect the editor or the action list

### Suggested Default Key Model

Recommended default interaction, designed to feel intuitive in Neovim:

- open code actions: existing code-action keybinding, for example `ga`
- move selection down: `<Down>` or `<C-n>` or `j`
- move selection up: `<Up>` or `<C-p>` or `k`
- accept selected action: `<CR>` or `<C-y>`
- close picker: `<Esc>` or `q`
- preview toggle / expanded details: `<Tab>` or `K`

This allows:

- arrow-key users
- Ctrl-key completion-menu users
- normal-mode `j` / `k` users

All three can coexist.

### Recommended Primary Accept Key

Use:

- `<CR>` as the primary accept key

And optionally:

- `<C-y>` as an additional "accept" key for users who expect popup-menu semantics

This makes the action picker feel similar to completion acceptance without being identical to insert-mode completion internals.

### Recommended Window Shape

Use a small floating window with:

- one action per line
- a current selection highlight
- a tiny title such as `Code Actions`
- optional right-side or bottom preview area if the action includes a non-trivial edit summary

If a two-pane preview is too heavy initially, start with:

- single-pane list
- short inline detail for the selected action

### Recommended Preview Behavior

Preview should be cheap and helpful.

Preferred behavior:

- when the selection changes, show a short description of what the action will do
- if the action is simple, one-line description is enough
- if the action is complex, show touched file count or affected symbol summary

Do not require full diff rendering for the first implementation.

### Selection Rules For Multiple Import Candidates

Missing-import actions often produce multiple valid options.

For those cases:

- order candidates by local index confidence and likely relevance
- show the symbol plus fqName/package
- highlight the best candidate first
- allow immediate accept of the first candidate if the user just presses `<CR>`

Example list shape:

- `Import Column — androidx.compose.foundation.layout`
- `Import ColumnScope — androidx.compose.foundation.layout`
- `Remove unresolved reference`

### Cursor And Diagnostic Coupling

Code actions should be discoverable from:

- current cursor position
- current line diagnostic
- current visual selection if appropriate

The initial implementation should prioritize:

- cursor-position-based action lookup
- line/diagnostic-based action lookup

### Fallback Behavior

If no fast actions are ready yet:

- show semantic actions when they arrive
- if no actions exist, return a simple "No actions" result without noisy UI

Do not block opening the picker waiting for slower analysis if fast actions can already be shown.

### UX Implementation Guidance

The implementation should prefer:

- one simple, stable action-picker flow
- predictable focus transfer
- immediate keyboard navigation

Avoid:

- multiple competing windows
- unclear focus
- requiring users to know whether they are in insert-mode popup behavior vs normal-mode list behavior

## Recommended Combined Diagnostic + Action Behavior

The desired user experience is:

1. user types a bad import or unresolved symbol
2. fast diagnostic appears quickly
3. one or more obvious code actions are available quickly
4. richer semantic actions may appear slightly later
5. selecting an action is lightweight and keyboard-friendly

The server should not require a full semantic pass to provide this baseline experience.

## Debounce Recommendations

Recommended initial values:

- `EDIT_FAST`: `40ms`
- `EDIT_SEMANTIC`: `300ms`
- `SAVE_OR_IDLE_FULL`: `1000ms` idle or save-triggered

These are starting values, not permanent truths.

They should be measured and tuned.

## What Each Tier Is Allowed To Trigger

### `EDIT_FAST`

Allowed:

- current-file syntax parse
- current-file import validation
- current-file local/index lookup

Not allowed:

- bridge request
- project import
- support-index rebuild
- broad semantic analysis

### `EDIT_SEMANTIC`

Allowed:

- focused semantic analysis for active file/module
- targeted semantic refresh

Avoid:

- broad workspace refresh
- dependency reindex
- project reimport unless the edit actually changed project-model files

### `SAVE_OR_IDLE_FULL`

Allowed:

- broader semantic refresh
- module-wide validation
- save-triggered cache persistence

Still avoid:

- unrelated rebuilds
- broad dependency rescans unless actual dependency inputs changed

## Performance Guardrails

To keep fast diagnostics from becoming a hidden slow path:

- cap the amount of current-file scanning work
- do not walk the whole workspace
- do not re-scan support/dependency indexes inside `EDIT_FAST`
- prefer direct indexed lookups over generalized search
- skip low-confidence checks instead of forcing expensive validation

## Instrumentation Requirements

Add counters and timers for:

- `EDIT_FAST` latency
- `EDIT_SEMANTIC` latency
- published diagnostics count by tier
- number of provisional diagnostics later overturned
- bridge diagnostic requests, if any
- support-index rebuild count triggered from edit/save activity
- semantic refresh count and scope

This instrumentation is necessary to validate the design.

## Success Metrics

The proposal is successful if:

- bad imports usually show feedback nearly immediately
- syntax errors usually show feedback nearly immediately
- obvious unresolved local/global names show feedback nearly immediately
- ordinary typing does not trigger broad expensive work
- false positives from `EDIT_FAST` stay rare
- semantic diagnostics still catch deeper issues shortly after

## Non-Goals

This proposal does not require:

- full semantic parity on every keystroke
- project-wide correctness after every edit
- bridge-backed diagnostics on every edit
- removing deeper semantic diagnostics

The purpose is responsiveness, not maximal semantic work in the hot path.

## Recommended Implementation Steps

### 1. Introduce Tiered Diagnostic Scheduling

Create explicit scheduling for:

- `EDIT_FAST`
- `EDIT_SEMANTIC`
- `SAVE_OR_IDLE_FULL`

Do not let one generic diagnostics pass implicitly do all jobs.

### 2. Add Fast Import Validation

Implement current-file import validation against the combined index.

This is one of the highest-leverage changes.

### 3. Add Fast Obvious Unresolved Diagnostics

Implement high-confidence unresolved-name and unresolved-simple-member diagnostics using:

- local lexical symbols
- current combined index

### 4. Keep Fast Diagnostics Conservative

When confidence is low:

- do not guess
- defer to semantic diagnostics

### 5. Merge Diagnostics By Tier

Do not treat diagnostics as a single undifferentiated stream.

Track and publish them with tier precedence.

### 6. Add Instrumentation Before Tuning

Add timing and route counters before spending much effort tuning thresholds.

## Suggested Tests

Add or update tests for:

- malformed import is diagnosed by `EDIT_FAST`
- unresolved import package segment is diagnosed by `EDIT_FAST`
- unresolved import leaf symbol is diagnosed by `EDIT_FAST`
- duplicate import is diagnosed by `EDIT_FAST`
- obvious unresolved local name is diagnosed by `EDIT_FAST`
- obvious unresolved simple member is diagnosed by `EDIT_FAST`
- smart-cast-sensitive unresolved cases are deferred to `EDIT_SEMANTIC`
- semantic diagnostics replace or refine provisional diagnostics
- normal source edits do not trigger project reimport
- normal source edits do not trigger support-index rebuild
- fast diagnostics provide fast code actions for bad imports
- multiple import candidates are ordered and exposed predictably
- action picker focus enters the picker on open and returns to editor on close/apply
- action accept works with `<CR>` and optionally `<C-y>`
- no fast action is offered for low-confidence semantic contexts

Where possible, assert both:

- diagnostic output
- tier / scheduling behavior

## Final Recommendation

Adopt this rule:

- instant diagnostics should be cheap, local, and provisional
- deeper diagnostics should be focused, semantic, and asynchronous
- broad expensive work should be reserved for save or idle

In short:

- make obvious errors fast
- make deep errors correct
- never let every keystroke behave like a full-project correctness pass
