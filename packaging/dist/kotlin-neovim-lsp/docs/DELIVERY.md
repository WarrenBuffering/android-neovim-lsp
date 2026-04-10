# STEP 1 - PARITY RECONSTRUCTION STRATEGY

## Clone Target

Clone target: the Kotlin editing, navigation, diagnostics, completion, import management, and refactoring experience that a Kotlin JVM developer most visibly experiences in modern JetBrains tooling, projected through standard Language Server Protocol surfaces and Neovim/LazyVim ergonomics.

The project does **not** assume access to IntelliJ internals that are not public. Instead it reconstructs the missing parts from:

- Kotlin language semantics.
- Public Kotlin compiler behavior.
- Publicly observable JetBrains UX.
- Standard IDE affordances.
- LSP-compatible editor workflows.
- Project-model inference from build structure and compiler settings.

## Certainty Labels

- `EXACT`: grounded directly in public spec, compiler behavior, or deterministic file/project structure.
- `INFERRED`: reconstructed with high confidence from Kotlin semantics, public JetBrains behavior, or compiler-adjacent architecture.
- `EMULATED`: intentionally approximate behavior designed to preserve user experience when exact parity is not possible through public APIs or plain LSP.

## Exact-Source Intake

- When a higher-parity public JetBrains implementation exists, the preferred path is to vendor the exact source into `third_party/jetbrains`.
- Vendored snapshots are pinned to exact upstream commits and checked by metadata plus tests.
- Non-public JetBrains behavior still uses a runtime bridge or reconstructed implementation.

## Build and Release Strategy

- Published dependencies stay external in source control, but every version is pinned and committed through Gradle dependency lockfiles.
- Release artifacts are self-contained through the bundled `installDist` layout and package script.
- JetBrains bridge code is optional and excluded from normal builds unless explicitly enabled with `-Pkotlinls.enableJetBrainsBridge=true`.
- Runtime bridge activation is also opt-in so normal Neovim startup stays predictable and lighter.

## Exact vs Inferred vs Emulated

- `EXACT`: syntax parsing, PSI-derived structure, JSON-RPC framing, LSP capability negotiation, document synchronization, Kotlin compiler diagnostics, root detection from Gradle/settings files, semantic token encoding, workspace edits.
- `INFERRED`: completion ranking, quick-fix ranking, documentation formatting, Gradle workspace reimport policy, diagnostics scheduling, import suggestion ordering, organize-import heuristics, rename blast radius, hierarchy ranking.
- `EMULATED`: intention-style code actions that depend on IntelliJ UI metaphors, code formatting parity where formatter internals are unavailable, JetBrains-like move/change-signature workflows over plain LSP, some library/decompiled navigation, some inlay hints and code vision behaviors.

## Highest-Risk Parity Areas

- Compiler-backed semantic analysis for partially edited buffers.
- Multi-module Gradle project reconstruction without IntelliJ’s project model.
- Completion quality and ranking parity.
- Safe refactor coverage over pure LSP.
- Formatting parity when JetBrains formatter access is optional and fallback behavior still has to stand on public tooling.
- JVM dependency/source attachment parity from public inputs only.

# STEP 2 - FEATURE MATRIX

The detailed feature matrix is maintained in [docs/CAPABILITY_MATRIX.md](./CAPABILITY_MATRIX.md).

# STEP 3 - ARCHITECTURE

The detailed architecture is maintained in [docs/ARCHITECTURE.md](./ARCHITECTURE.md).

# STEP 4 - REPOSITORY TREE

The canonical repository tree is maintained in [docs/REPOSITORY_TREE.md](./REPOSITORY_TREE.md).

# STEP 5 - IMPLEMENTATION

Implementation is distributed across the source modules at the repository root. Each runtime capability is paired with a corresponding deterministic suite in `tests`.

# STEP 6 - NEOVIM/LAZYVIM INTEGRATION

Integration assets live under `nvim/` and `lazyvim_example/`.

# STEP 7 - VALIDATION

Validation and benchmark commands are documented in [docs/VALIDATION.md](./VALIDATION.md).

# STEP 8 - PARITY GAP REPORT

The current brutally honest parity report lives in [docs/PARITY_GAP_REPORT.md](./PARITY_GAP_REPORT.md).
