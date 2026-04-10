100% vibe coded. It works on my machine. If it stops working on my machine I'm going to keep pushing breaking commits until it works on my machine again

# Kotlin Neovim LSP

`kotlin-neovim-lsp` is a standalone Kotlin Language Server Protocol project focused on Neovim and LazyVim. It packages the Kotlin IDE backend as a reusable library plus a thin executable wrapper so editor integrations can depend on a stable stdio server entrypoint.

This repository is organized as a parity-first monorepo:

- `protocol`: JSON-RPC and LSP transport/types.
- `workspace`: open-document state, line maps, and root detection.
- `project-import`: Gradle-first project model reconstruction.
- `analysis`: Kotlin compiler-backed PSI and semantic analysis.
- `index`: declaration and reference indexing.
- `diagnostics`: compiler and heuristic diagnostics.
- `completion`, `hover`, `symbols`, `navigation`, `refactor`, `formatting`, `code-actions`: user-facing IDE capabilities.
- `standalone-lsp`: reusable standalone Kotlin LSP library with stdio launcher helpers.
- `server`: thin executable wrapper around `standalone-lsp`.
- `tests`: custom deterministic test harnesses and fixture-driven suites.
- `benchmarks`: latency and indexing microbenchmarks.
- `nvim`, `lazyvim_example`: exact integration files for Neovim and LazyVim.
- `fixtures`: real Kotlin workspace fixtures.
- `docs`: parity strategy, capability matrix, architecture, and gap report.

The primary delivery narrative lives in [docs/DELIVERY.md](docs/DELIVERY.md).

## Build Model

Normal builds keep published dependencies pinned and locked, then ship a fully bundled distribution from `:server:installDist`.

- Default build: standalone LSP only.
- JetBrains bridge: opt-in at build time with `-Pkotlinls.enableJetBrainsBridge=true`.
- Runtime bridge detection: off by default unless `KOTLINLS_ENABLE_INTELLIJ_BRIDGE=true` or `-Dkotlinls.intellijHome=...` is set.

Refresh dependency lockfiles with:

```bash
./gradlew --write-locks resolveAndLockAll
```

Build the default bundled release with:

```bash
./packaging/build-package.sh
```

Build a bridge-enabled release with:

```bash
ENABLE_JETBRAINS_BRIDGE=1 ./packaging/build-package.sh
```

## Library Use

Use the reusable library entrypoint from `standalone-lsp`:

```kotlin
import dev.codex.kotlinls.standalone.runStdioKotlinLanguageServer

fun main() {
    runStdioKotlinLanguageServer()
}
```
