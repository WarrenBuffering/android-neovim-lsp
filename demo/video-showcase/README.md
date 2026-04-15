# Video Showcase Demo

This is a small multi-module Kotlin/JVM project built to show off `android-neovim-lsp` on camera without needing a full Android toolchain.

## Quick Start

Open this folder as the project root:

```bash
nvim "$PWD/demo/video-showcase"
```

Optional build validation:

```bash
./gradlew -p demo/video-showcase build
```

## Autoplay Demo

If you want Neovim to drive the showcase for you while you record the real editor window:

```bash
./demo/video-showcase/scripts/play-demo.sh
```

That launcher:

- builds the local language server if needed
- warms the demo project so the showcase starts from a ready state
- opens a clean demo-focused Neovim session
- auto-runs the feature tour after a short countdown

Useful flags:

```bash
./demo/video-showcase/scripts/play-demo.sh --skip-build
./demo/video-showcase/scripts/play-demo.sh --no-autostart
./demo/video-showcase/scripts/play-demo.sh --countdown-ms=4000
```

If you already have the demo file open in your own Neovim session, you can start the autoplay flow in-place:

```vim
:luafile ./demo/video-showcase/scripts/start_showcase.lua
```

If you want a bash trigger for a live Neovim window you are already recording, open Neovim with `--listen` first:

```bash
nvim --listen /tmp/android-neovim-lsp-demo.sock \
  "$PWD/demo/video-showcase/app/src/main/kotlin/demo/video/app/AppEntry.kt"
```

Then, once recording is rolling, trigger the autoplay from another shell:

```bash
./demo/video-showcase/scripts/start-remote-showcase.sh /tmp/android-neovim-lsp-demo.sock
```

## Suggested Video Route

1. Start in `app/src/main/kotlin/demo/video/app/AppEntry.kt`.
   Hover on `VideoCard`, `bannerLine`, `PlaybackState`, and `UUID`.
   Jump to definition on `FakeVideoRepository`, `HighlightTone`, `LegacyHandleFormatter`, and `UUID`.
   Trigger signature help inside the `bannerLine(...)` call.
   Show inlay hints on `toneBuckets` and `summaryByTone`.
   Type after `tone.` inside `completionHotspot(...)` for member completion.

2. Move to `network/src/main/kotlin/demo/video/network/FakeVideoRepository.kt`.
   Jump to `VideoRepository`.
   Use references or implementation lookup on `featuredCards`.
   Hover the KDoc on `buildPresenterTag` and `pickSpotlight`.

3. Open `app/src/main/kotlin/demo/video/app/CodeActionPlayground.kt`.
   Trigger explicit type on `presenterTag` or `slug`.
   Trigger explicit return type on `explicitReturnDemo`.
   Delete the `UUID` import and use the missing-import code action.
   Shuffle imports, then run organize imports.

4. Open `app/src/main/kotlin/demo/video/app/FormattingPlayground.kt`.
   Run formatting or format-on-save to clean it up.

5. Show diagnostics in `AppEntry.kt` or `CodeActionPlayground.kt`.
   Change `tone.presenterLabel` to `tone.presenterLab`.
   Or change `UUID.fromString(...)` to `UUID.fromStri(...)`.
   Leave insert mode to show the deferred diagnostics flow.

6. Use workspace/document symbols.
   Search for `PlaybackState`, `Cue`, `buildPresenterTag`, `watchPartyUrl`, or `HighlightTone`.

## Feature Map

- Cross-module definition and references: `app` -> `network` -> `domain`
- Kotlin to Java source navigation: `LegacyHandleFormatter`
- Dependency and standard library hover/definition: `UUID`, `buildString`, `lowercase`
- Signature help and inlay hints: `bannerLine(...)`, local inferred values in `AppEntry.kt`
- Rename/code actions: `CodeActionPlayground.kt`
- Formatting/organize imports: `FormattingPlayground.kt`
- Symbols: `WorkspaceSymbolsPlayground.kt`, `PlaybackState`
