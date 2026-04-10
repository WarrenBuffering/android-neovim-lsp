# Validation

## Build

```bash
./gradlew :server:installDist
```

Validated on this workspace.

To refresh pinned dependency lockfiles:

```bash
./gradlew --write-locks resolveAndLockAll
```

## Tests

```bash
./gradlew testAll
./gradlew :tests:runSmokeTests
```

Validated on April 8, 2026:

- Gradle fixture/protocol harness passed with `./gradlew :tests:runAllTests`.
- Installable server distribution built successfully with `./gradlew :server:installDist`.
- Vendored JetBrains source snapshots can be verified with `python3 scripts/vendor_jetbrains_sources.py --check`.
- Upstream JetBrains vendoring diffs can be summarized with `python3 scripts/report_jetbrains_update.py --new-ref <sha>`.

## Benchmarks

```bash
./gradlew benchmarkAll
```

Latest benchmark sample from `./gradlew :benchmarks:runBenchmarks`:

- Import: `36 ms`
- Analyze: `943 ms`
- Index: `8 ms`
- Completion: `1 ms`
- Indexed symbols: `17`
- Indexed references: `31`

## Packaging

```bash
./packaging/build-package.sh
```

Bridge-enabled package:

```bash
ENABLE_JETBRAINS_BRIDGE=1 ./packaging/build-package.sh
```

Validated on this workspace. The distributable bundle, tarball, and checksum files are created at `packaging/dist`.

## Manual Demo

```bash
./server/build/install/server/bin/kotlin-neovim-lsp
```

Point Neovim at the executable using the Lua files in `nvim/` or `lazyvim_example/`.
