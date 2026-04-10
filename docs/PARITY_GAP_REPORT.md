# Parity Gap Report

## Exact Matches

- `EXACT` JSON-RPC framing over stdio with standard `Content-Length` transport.
- `EXACT` LSP initialize/shutdown/document-sync/hover/completion/definition/references/document-symbol/workspace-symbol/formatting/code-action request plumbing.
- `EXACT` Gradle root detection from `settings.gradle(.kts)` and `build.gradle(.kts)`.
- `EXACT` live-buffer text synchronization and position/offset mapping.
- `EXACT` compiler-backed diagnostics using Kotlin compiler embeddable plus mirrored live-buffer source snapshots.
- `EXACT` deterministic fixture suites covering import, analysis, cross-module completion/navigation, and protocol smoke traffic.

## Inferred Matches

- `INFERRED` Gradle project reconstruction: module discovery, project dependency edges, conventional source-set detection, compiler option scraping, and cache-backed dependency jar resolution.
- `INFERRED` completion ranking: direct JetBrains IDE ordering when the completion bridge is available, with local fallback based on prefix strength, locality, same-file bias, import cost, receiver-aware member selection, expected-type weighting, stdlib source injection, and JDK member fallback.
- `INFERRED` hover rendering: declaration signature plus structured KDoc markdown sections, link-to-code rendering, and type context.
- `INFERRED` semantic navigation: descriptor-based lookup with import/name fallback when compiler resolution is absent or incomplete.
- `INFERRED` signature help and inlay hints: resolved-call rendering with overload-sensitive parameter metadata plus inferred local-property type hints.
- `INFERRED` type definition and implementation search: compiler-type lookup for inferred expressions plus override-aware member implementation search.
- `INFERRED` organize-imports behavior and package/source-root mismatch warnings, including alias preservation plus IntelliJ-style star-import threshold seeding from `.idea/codeStyles`.
- `INFERRED` call/type hierarchy construction from indexed references and resolved supertypes.
- `INFERRED` mixed Kotlin/Java navigation in workspace sources through Java source indexing and compiler-backed descriptor lookup.
- `INFERRED` dependency-source navigation from local Gradle cache source jars, including extracted source-file definitions and source-backed hover docs when available.

## Emulated Matches

- `EMULATED` formatting: a direct JetBrains formatter bridge is used when a local JetBrains IDE bundle is available; otherwise formatting falls back to the built-in formatter path with project `.editorconfig` awareness, IntelliJ `.idea/codeStyles` defaults, PSI-scoped range edits derived from full-document formatting, and minimal-diff edits.
- `EMULATED` completion parity: a direct JetBrains IDE completion bridge is used when a compatible local JetBrains IDE bundle is available; otherwise the server falls back to its own Kotlin-aware completion engine.
- `EMULATED` change-signature/move workflows: command slots exist in the architecture, but the repository currently prioritizes rename and import-safe edits.
- `EMULATED` inlay hints: parameter-label hints and inferred local-property type hints are available, but broader JetBrains hint categories are still missing.
- `EMULATED` intention-style code actions: organize imports, missing imports, explicit type annotation, and explicit return type are implemented as LSP actions, but IntelliJ’s full intention catalog is much wider.
- `EMULATED` type definition: stronger for inferred locals and call expressions, but still weaker than JetBrains for smart casts, aliases, and some complex inferred types.

## Remaining Gaps

- K2/Analysis API parity is not implemented; the current semantic backend relies on K1 compiler embeddable APIs through reflective access.
- Mixed Kotlin/Java analysis is stronger now for source navigation and symbol lookup, but Java semantic parity is still shallow compared with IntelliJ for deep overrides, usages, and refactors.
- Completion can now route through a real local JetBrains IDE process for candidate generation and ordering, but this still depends on a compatible local IDE install and project import. The fallback engine remains weaker for deep smart-cast control-flow reasoning, richer extension prioritization, or chained receiver completion.
- Refactoring parity is still strongest for rename and organize-imports. Move/change-signature/package refactors need deeper edit synthesis and validation.
- Formatting is materially closer now, and can route through a real local JetBrains formatter process. The remaining gap is that this bridge depends on a compatible local JetBrains IDE installation, and full user-defined IntelliJ code style coverage still exceeds the subset currently handled in fallback mode.
- Hover and completion documentation are cleaner now because indexed KDoc is rendered into markdown sections, but IntelliJ’s richer HTML rendering, external Javadoc fusion, and link resolution are still deeper.
- Organize-imports is stronger now with unused-import pruning, alias preservation, and IntelliJ-style star-import thresholds, but it still lacks full IntelliJ conflict re-resolution and import-layout table parity.
- Library source attachment and source-backed hover are stronger now through Gradle-cache source-jar extraction, but decompiled fallback and richer external doc parity are still limited compared with IntelliJ.
- Performance is acceptable on the included fixtures, but background incremental scheduling and cache invalidation need more sophistication for large production repos.

## Highest-Leverage Next Work

- Replace reflective K1 analysis entry points with a more durable K2-oriented semantic layer or broaden the existing JetBrains sidecar so more semantic queries, not just completion, come from the real IDE backend.
- Add richer import/extension/expected-type ranking and completion de-duplication.
- Expand refactor workflows beyond rename into move/change-signature/package maintenance with confidence grading.
- Improve Java interop semantics and attached-source/library navigation.
- Push formatter parity farther by broadening `.idea/codeStyles` fallback coverage, caching or pooling the JetBrains bridge to reduce startup cost, and improving IDE-discovery coverage beyond the common local install paths.
