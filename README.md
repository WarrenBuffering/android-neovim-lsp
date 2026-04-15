# Android Neovim LSP

`android-neovim-lsp` is a Kotlin and Android language server for Neovim.

## Quick Start

With `lazy.nvim` or LazyVim, this is enough:

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

If you are loading the installed runtime directly:

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup()
```

On first setup, the plugin looks for an existing `android-neovim-lsp` binary. If it cannot find one, it will:

- build and install the server from the checkout when the full repo is available
- otherwise fall back to the release installer path

If you do not want that behavior, set `install = false` in `opts` and provide `cmd` yourself.

## Overview

- standalone stdio server executable: `android-neovim-lsp`
- Neovim runtime module: `android_neovim_lsp`
- Gradle-aware Kotlin and Android project import
- local workspace and dependency indexing for fast completion, hover, and navigation
- Android Studio bridge support for diagnostics, formatting, and higher-parity semantic requests

## Requirements

- Java 21 on `PATH` or available through `JAVA_HOME`
- for Android projects, an Android SDK available through `local.properties`, `ANDROID_SDK_ROOT`, or `ANDROID_HOME`
- Android Studio if you want bridge-backed diagnostics, formatting, and semantic features

Machine-specific paths can live in `local.properties`. Start from [`local.properties.example`](local.properties.example).

Example:

```properties
sdk.dir=/Users/yourname/Library/Android/sdk
kotlinls.intellijHome=/Applications/Android Studio.app/Contents
```

## Neovim Setup

`android_neovim_lsp.setup()` looks for the server in this order:

- `android-neovim-lsp` on `PATH`
- a repo-local build at `server/build/install/server/bin/android-neovim-lsp`
- `~/.local/share/android-neovim-lsp/android-neovim-lsp/bin/android-neovim-lsp`
- an installed sibling binary when loaded from the bundled runtime

If you want to set the command explicitly:

```lua
require("android_neovim_lsp").setup({
  cmd = {
    vim.fn.expand("~/.local/share/android-neovim-lsp/android-neovim-lsp/bin/android-neovim-lsp"),
  },
})
```

## Common Options

```lua
require("android_neovim_lsp").setup({
  inlay_hints = false,
  format_on_save = true,
})
```

`format_on_save` can also be configured as a table:

```lua
require("android_neovim_lsp").setup({
  format_on_save = {
    enabled = true,
    quiet = true,
    lsp_format = "fallback",
    timeout_ms = 5000,
  },
})
```

You can control bootstrap behavior too:

```lua
require("android_neovim_lsp").setup({
  install = {
    enabled = true,
    method = "auto",
  },
})
```

Supported `install.method` values are `auto`, `build`, `release`, `build_or_release`, and `release_or_build`.

## Manual Install

This project can also be installed from a checkout.

```bash
git clone git@github.com:WarrenBuffering/android-neovim-lsp.git
cd android-neovim-lsp
./packaging/install-local-dev.sh
```

That installs:

- the server into `~/.local/share/android-neovim-lsp/android-neovim-lsp`
- the Neovim runtime into `~/.local/share/android-neovim-lsp/nvim`
- the launcher into `~/.local/bin/android-neovim-lsp`

If you want the Android Studio bridge included in the local bundle:

```bash
ENABLE_JETBRAINS_BRIDGE=1 ./packaging/install-local-dev.sh
```

## `lazy.nvim` / LazyVim

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

The example plugin file lives at [`lazyvim_example/lua/plugins/android-neovim-lsp.lua`](lazyvim_example/lua/plugins/android-neovim-lsp.lua).

## Local Testing

To test the repo with its bundled Neovim config:

```bash
nvim -u "$PWD/nvim/init.lua"
```

That starts a small consumer-style setup that loads the installed runtime and `nvim-lspconfig`.

## Project Layout

- `server`: executable wrapper
- `standalone-lsp`: reusable server implementation
- `protocol`: JSON-RPC and LSP transport/types
- `project-import`: Gradle project import and classpath reconstruction
- `analysis`: compiler-backed semantic analysis
- `index`: workspace and dependency indexing
- `diagnostics`, `completion`, `hover`, `navigation`, `symbols`, `formatting`, `code-actions`, `refactor`: editor features
- `nvim`, `lazyvim_example`: Neovim integration
- `tests`: fixture-driven test suites

## License

This repository is licensed under BSD Zero Clause License. See [`LICENSE`](LICENSE).
