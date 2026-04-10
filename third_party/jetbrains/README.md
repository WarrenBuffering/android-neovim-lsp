# JetBrains Vendored Sources

This directory holds exact-source snapshots copied from public JetBrains repositories.

Rules:

- Vendor only from public upstream source repositories with license terms that permit redistribution in this repository.
- Keep the upstream repository, pinned commit, upstream path, and local SHA-256 recorded in metadata.
- Prefer exact vendoring over reimplementation when the source exists publicly and the dependency surface is containable.
- Do not decompile or copy from proprietary JetBrains plugin binaries into this directory.
- For proprietary or non-public behavior, use a runtime bridge or a reconstructed implementation instead.

Current workflow:

1. Edit [vendor-manifest.json](./vendor-manifest.json).
2. Run `python3 scripts/vendor_jetbrains_sources.py`.
3. Commit the updated `upstream/` snapshot and [VENDORED_SOURCES.json](./VENDORED_SOURCES.json).
4. Before bumping refs, generate a diff report with `python3 scripts/report_jetbrains_update.py --new-ref <sha>`.
5. Validate with `python3 scripts/vendor_jetbrains_sources.py --check` or `./gradlew :tests:runAllTests`.
