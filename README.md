# Android Neovim LSP

`android-neovim-lsp` is a standalone Kotlin/Android language server project focused on Neovim and LazyVim.

User-facing names:

- executable: `android-neovim-lsp`
- Neovim module: `android_neovim_lsp`

## What You Get

- a standalone stdio language server executable
- Gradle-first Kotlin/Android project import
- local workspace and library indexing
- a plugin entrypoint that can be loaded with `require("android_neovim_lsp").setup()`

## Install

### Release Install

For macOS and Linux, the quickest install path is:

```bash
curl -fsSL https://raw.githubusercontent.com/WarrenBuffering/android-neovim-lsp/main/packaging/install-release.sh | bash
```

By default this:

- downloads the latest GitHub release tarball
- installs it into `~/.local/share/android-neovim-lsp`
- symlinks `android-neovim-lsp` into `~/.local/bin`

You can pin a release or change the install location:

```bash
ANDROID_NEOVIM_LSP_VERSION=v0.1.1 \
ANDROID_NEOVIM_LSP_INSTALL_ROOT=/some/path \
curl -fsSL https://raw.githubusercontent.com/WarrenBuffering/android-neovim-lsp/main/packaging/install-release.sh | bash
```

After that, the minimal Neovim setup is just:

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup()
```

If you installed with the release script, you do not need to set `cmd` manually.

### Local Dev Install

If you are working from a checkout and want the same layout as a consumer install:

```bash
./packaging/install-local-dev.sh
```

That:

- builds the local server
- installs the bundle into `~/.local/share/android-neovim-lsp`
- makes `android-neovim-lsp` available from `~/.local/bin`

For repo-local testing:

```bash
nvim -u "$PWD/nvim/init.lua"
```

That entrypoint intentionally exercises the same runtime path and setup flow a real user install sees.

## Requirements

- Java 21 available via `java` on `PATH` or `JAVA_HOME`
- for Android projects, an Android SDK available via `local.properties`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`
- Android Studio is optional, but recommended for bridge-backed semantic and formatting features

You can keep project-local machine paths in `local.properties` and keep them out of git. Start from [`local.properties.example`](local.properties.example).

Useful keys:

- `sdk.dir=/Users/yourname/Library/Android/sdk`
- `kotlinls.intellijHome=/Applications/Android Studio.app/Contents`

Core project import, indexing, and fast diagnostics run inside `android-neovim-lsp` itself. The JetBrains bridge is still used for some higher-parity semantic requests and JetBrains formatting when it is available.

## Neovim Setup

Minimal setup:

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup()
```

If you use `lazy.nvim` or LazyVim, the normal plugin shape is:

```lua
return {
  {
    "WarrenBuffering/android-neovim-lsp",
    main = "android_neovim_lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    opts = {},
  },
}
```

The intended consumer experience is that users only need the plugin declaration plus:

```lua
require("android_neovim_lsp").setup()
```

The plugin looks for the server in this order:

- `android-neovim-lsp` on `PATH`
- a sibling bundled binary at `../android-neovim-lsp/bin/android-neovim-lsp`
- a local source build at `../server/build/install/server/bin/android-neovim-lsp`

If you want to set `cmd` explicitly:

```lua
local repo_root = vim.fn.getcwd()
local android_lsp_cmd = repo_root .. "/server/build/install/server/bin/android-neovim-lsp"

vim.opt.runtimepath:append(repo_root .. "/nvim")

require("android_neovim_lsp").setup({
  cmd = { android_lsp_cmd },
})
```

The repo's [`nvim/init.lua`](nvim/init.lua) also bootstraps `lazy.nvim` and `nvim-lspconfig`, so `nvim -u "$PWD/nvim/init.lua"` exercises a consumer-style setup instead of depending on your existing editor config.

The built-in root detection looks for:

- `settings.gradle.kts`
- `settings.gradle`
- `build.gradle.kts`
- `build.gradle`
- `.git`

### LazyVim

Start from [`lazyvim_example/lua/plugins/android-neovim-lsp.lua`](lazyvim_example/lua/plugins/android-neovim-lsp.lua).

```lua
return {
  {
    dir = vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"),
    name = "android-neovim-lsp",
    main = "android_neovim_lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    opts = {},
  },
}
```

Use `require("android_neovim_lsp")`. The older `require("kotlin_neovim_lsp")` name is no longer valid.
When the plugin is loaded from the installed bundle, you can omit `cmd` and let it auto-detect the sibling launcher.

Useful plugin options:

- `inlay_hints = false`
- `format_on_save = true`
- `block_on_save = false`

### Format On Save

```lua
vim.api.nvim_create_autocmd("BufWritePre", {
  pattern = "*.kt",
  callback = function(args)
    vim.lsp.buf.format({ bufnr = args.buf, async = false })
  end,
})
```

### Mason

This repo is not currently published to Mason. Until it is, users need either:

- `android-neovim-lsp` on `PATH`
- or a bundled release/package layout that keeps `nvim/` next to the installed server directory

## Developer Notes

For local development, install the repo into the same on-disk layout a normal user would run:

```bash
./packaging/install-local-dev.sh
```

That builds the local server, installs the bundle into `~/.local/share/android-neovim-lsp`, and refreshes the `android-neovim-lsp` launcher in `~/.local/bin`.

For repo-local testing:

```bash
nvim -u "$PWD/nvim/init.lua"
```

That entrypoint is meant to exercise a consumer-style setup path rather than depend on your existing Neovim config.

## Project Layout

- `protocol`: JSON-RPC and LSP transport/types
- `workspace`: open-document state, line maps, and root detection
- `project-import`: Gradle-first project model reconstruction
- `analysis`: Kotlin compiler-backed PSI and semantic analysis
- `index`: declaration and reference indexing
- `diagnostics`: compiler and heuristic diagnostics
- `completion`, `hover`, `symbols`, `navigation`, `refactor`, `formatting`, `code-actions`: user-facing IDE capabilities
- `standalone-lsp`: reusable standalone Kotlin LSP library with stdio launcher helpers
- `server`: thin executable wrapper around `standalone-lsp`
- `tests`: deterministic test harnesses and fixture-driven suites
- `benchmarks`: latency and indexing microbenchmarks
- `nvim`, `lazyvim_example`: Neovim and LazyVim integration files
- `fixtures`: Kotlin workspace fixtures
- `docs`: parity strategy, capability matrix, architecture, and gap report

The primary delivery narrative lives in [`docs/DELIVERY.md`](docs/DELIVERY.md).

## Library Use

If you want to embed the standalone server yourself:

```kotlin
import dev.codex.kotlinls.standalone.runStdioKotlinLanguageServer

fun main() {
    runStdioKotlinLanguageServer()
}
```

## License

This repository is licensed under BSD Zero Clause License. See [`LICENSE`](LICENSE).
