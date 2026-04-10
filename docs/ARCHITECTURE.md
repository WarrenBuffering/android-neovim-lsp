# Architecture

## Layering

1. `protocol`
   Handles JSON-RPC transport, request routing, cancellation, typed LSP models, and serialization.
2. `workspace`
   Owns open-document state, versions, offsets, line maps, root detection, and file watching snapshots.
3. `project-import`
   Reconstructs a Gradle-first project model from `settings.gradle(.kts)`, `build.gradle(.kts)`, directory layout, compiler options, and observable module structure.
4. `analysis`
   Builds Kotlin compiler environments, PSI trees, semantic snapshots, and descriptor lookups.
5. `index`
   Builds fast declaration/reference indices and hierarchy edges from analysis snapshots, Java source scans, and extracted external source jars from the local Gradle cache.
6. Feature modules
   `diagnostics`, `completion`, `hover`, `symbols`, `navigation`, `refactor`, `formatting`, `code-actions`.
7. `server`
   Wires LSP methods to workspace + analysis + feature services.
8. `tests` and `benchmarks`
   Exercise the stack end-to-end over fixture workspaces.
9. `third_party/jetbrains`
   Stores exact-source snapshots vendored from public JetBrains repositories for parity work and upstream diffing.

## Data Flow

- Client opens/changes a document.
- `workspace` applies the text edits and versions the buffer.
- `project-import` maps the file into a logical module and classpath context.
- `analysis` builds or refreshes a module analysis snapshot.
- `index` refreshes declarations and usage edges when the snapshot changes.
- `index` also mirrors and scans dependency source jars when they are locally available so library navigation can return real source files.
- Feature modules answer LSP requests against the latest stable snapshot.

## Caching Strategy

- Workspace documents are versioned and immutable by snapshot.
- Module-level project models are cached by root path and Gradle file fingerprints.
- Analysis snapshots are cached by module and invalidated by source/build-file changes.
- Indices are cached per analysis snapshot.
- Completion and hover requests reuse the latest stable module snapshot and avoid triggering full recomputation where possible.

## Incremental Update Model

- Document changes are applied incrementally in-memory.
- Diagnostics are debounced and recomputed per module.
- Expensive project-model refreshes only trigger on build-file changes or explicit workspace reload.
- Cross-file indices are updated from the latest consistent snapshot, not every keystroke.

## Cancellation Strategy

- Each request is tagged with a cancellation token.
- Long-running work periodically checks cancellation and aborts cleanly.
- Analysis work is snapshot-based so stale requests can drop their results without corrupting newer state.

## Concurrency

- Transport and request dispatch run on a lightweight coroutine scope.
- Module analysis is serialized per module to avoid compiler-environment races.
- Read-mostly feature handlers can serve from the latest snapshot concurrently.

## Workspace Import Lifecycle

1. Detect root.
2. Parse Gradle/settings structure.
3. Infer modules, source sets, Kotlin/Java roots, and compiler options.
4. Materialize a workspace model.
5. Reimport on settings/build changes or explicit command.

## Diagnostics Lifecycle

1. Buffer change arrives.
2. Debounce per module.
3. Build compiler-backed analysis snapshot.
4. Emit compiler diagnostics.
5. Augment with index-based inferred diagnostics such as import repair suggestions.
6. Publish stable diagnostics after cancellation filtering.

## Completion Lifecycle

1. Determine context and prefix from the active buffer.
2. Gather visible locals, receiver members, imported declarations, package members, and indexed symbols.
3. Score and deduplicate candidates.
4. Return lean completion items.
5. Resolve documentation/detail lazily on `completionItem/resolve`.

## Refactor/Edit Model

- Pure rename and import operations use direct `WorkspaceEdit`.
- More complex workflows such as move/change-signature are exposed as code actions plus commands that generate deterministic workspace edits.
- All refactors are preview-friendly and reject low-confidence unsafe edits.

## Failure Recovery

- Failed project import falls back to source-only/dumb mode.
- Failed semantic analysis falls back to syntax-only PSI features.
- Server keeps the last good snapshot available for read-only features when a rebuild fails.

## Logging and Debugging

- Structured server logs with request IDs, latency, invalidation reasons, and project import summaries.
- Trace toggles for transport, analysis, and completion ranking.
- Explicit certainty labels in docs and design notes for reconstructed behaviors.

## Upstream Intake

- Prefer vendoring exact public JetBrains source before reconstructing the same behavior locally.
- Keep vendored snapshots pinned by commit under `third_party/jetbrains/upstream`.
- Validate vendored snapshots with `scripts/vendor_jetbrains_sources.py --check` and the `vendored-sources` test suite.
- Use runtime bridges or reconstruction instead of decompilation for non-public JetBrains behavior.
