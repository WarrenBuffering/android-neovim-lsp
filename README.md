# Android Neovim LSP

`android-neovim-lsp` is a standalone Kotlin/Android language server project focused on Neovim and LazyVim.

User-facing names:

- executable: `android-neovim-lsp`
- Neovim module: `android_neovim_lsp`

## What You Get

- a standalone stdio language server executable
- Gradle-first Kotlin/Android project import
- local workspace and library indexing
- Neovim runtime files in [`nvim/`](nvim/)
- a LazyVim example in [`lazyvim_example/`](lazyvim_example/)

## Install

### Release Installer

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

### Build From Source

Build the installable server:

```bash
./gradlew :server:installDist
```

The launcher is created at:

```bash
./server/build/install/server/bin/android-neovim-lsp
```

Build a local distributable bundle:

```bash
./packaging/build-package.sh
```

Build a bridge-enabled bundle:

```bash
ENABLE_JETBRAINS_BRIDGE=1 ./packaging/build-package.sh
```

Refresh dependency lockfiles:

```bash
./gradlew --write-locks resolveAndLockAll
```

## Neovim Setup

Minimal setup:

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup()
```

The plugin looks for the server in this order:

- `android-neovim-lsp` on `PATH`
- a sibling bundled binary at `../android-neovim-lsp/bin/android-neovim-lsp`
- a local source build at `../server/build/install/server/bin/android-neovim-lsp`

If you want to set `cmd` explicitly:

```lua
local android_lsp_cmd = "/absolute/path/to/android-neovim-lsp/server/build/install/server/bin/android-neovim-lsp"

vim.opt.runtimepath:append("/absolute/path/to/android-neovim-lsp/nvim")

require("android_neovim_lsp").setup({
  cmd = { android_lsp_cmd },
})
```

For local testing that matches the consumer install layout, run:

```bash
./packaging/install-local-dev.sh
nvim -u /absolute/path/to/android-neovim-lsp/nvim/init.lua
```

That refreshes `~/.local/share/android-neovim-lsp` from your current checkout and keeps `nvim/init.lua` loading the installed runtime instead of the repo copy.

That test entrypoint also bootstraps `lazy.nvim` and `nvim-lspconfig`, so it exercises a consumer-style plugin setup instead of depending on your existing editor config.

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
    "neovim/nvim-lspconfig",
    opts = function(_, opts)
      local configs = require("lspconfig.configs")
      local lspconfig = require("lspconfig")

      if not configs.android_neovim_lsp then
        configs.android_neovim_lsp = {
          default_config = {
            cmd = { "android-neovim-lsp" },
            filetypes = { "kotlin" },
            root_dir = function(fname)
              return vim.fs.root(fname, {
                "settings.gradle.kts",
                "settings.gradle",
                "build.gradle.kts",
                "build.gradle",
                ".git",
              })
            end,
            single_file_support = true,
          },
        }
      end

      opts.servers = opts.servers or {}
      opts.servers.android_neovim_lsp = opts.servers.android_neovim_lsp or {}

      lspconfig.android_neovim_lsp.setup(opts.servers.android_neovim_lsp)
    end,
  },
}
```

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
