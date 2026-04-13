# Kotlin Neovim LSP

> 100% vibe-coded. It works on my machine, it will continue working on my machine breaking changes or not, and I cannot be held liable for anything this repo does or does not do.

`kotlin-neovim-lsp` is a standalone Kotlin/Android language server project aimed at Neovim and LazyVim.

The current executable and Lua module names are:

- binary: `kotlin-neovim-lsp`
- Lua module: `kotlin_neovim_lsp`

## What You Get

- a standalone stdio language server executable
- Gradle-first Kotlin/Android project import
- local workspace and library indexing
- Neovim integration files in [`nvim/`](nvim/)
- a LazyVim example in [`lazyvim_example/`](lazyvim_example/)

## Build

Build the installable server:

```bash
./gradlew :server:installDist
```

The executable will be created at:

```bash
./server/build/install/server/bin/kotlin-neovim-lsp
```

If you want the bridge-enabled package:

```bash
ENABLE_JETBRAINS_BRIDGE=1 ./packaging/build-package.sh
```

If you need to refresh dependency lockfiles:

```bash
./gradlew --write-locks resolveAndLockAll
```

## Neovim Setup

Minimal `init.lua` example:

```lua
local kotlin_lsp_cmd = "/absolute/path/to/kotlin-neovim-lsp/server/build/install/server/bin/kotlin-neovim-lsp"

vim.opt.runtimepath:append("/absolute/path/to/kotlin-neovim-lsp/nvim")

require("kotlin_neovim_lsp").setup({
  cmd = { kotlin_lsp_cmd },
})
```

The built-in root detection looks for:

- `settings.gradle.kts`
- `settings.gradle`
- `build.gradle.kts`
- `build.gradle`
- `.git`

## LazyVim Setup

If you are using LazyVim, start from [`lazyvim_example/lua/plugins/kotlin-neovim-lsp.lua`](lazyvim_example/lua/plugins/kotlin-neovim-lsp.lua).

Example:

```lua
return {
  {
    "neovim/nvim-lspconfig",
    opts = function(_, opts)
      local configs = require("lspconfig.configs")
      local lspconfig = require("lspconfig")

      if not configs.kotlin_neovim_lsp then
        configs.kotlin_neovim_lsp = {
          default_config = {
            cmd = { "/absolute/path/to/kotlin-neovim-lsp/server/build/install/server/bin/kotlin-neovim-lsp" },
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
      opts.servers.kotlin_neovim_lsp = opts.servers.kotlin_neovim_lsp or {}

      lspconfig.kotlin_neovim_lsp.setup(opts.servers.kotlin_neovim_lsp)
    end,
  },
}
```

## Formatting On Save

```lua
vim.api.nvim_create_autocmd("BufWritePre", {
  pattern = "*.kt",
  callback = function(args)
    vim.lsp.buf.format({ bufnr = args.buf, async = false })
  end,
})
```

## Mason

This repo is not currently published to Mason. Point Neovim directly at the built executable.

## Repo Layout

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
- `nvim`, `lazyvim_example`: exact Neovim and LazyVim integration files
- `fixtures`: real Kotlin workspace fixtures
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
