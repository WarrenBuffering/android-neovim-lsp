# Neovim Setup

## Minimal `init.lua`

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup({
  inlay_hints = false,
  format_on_save = true,
})
```

If you used the release installer, this is the default runtime path it creates.

The repo's [`nvim/init.lua`](init.lua) intentionally uses this installed runtime too, so local testing follows the same loading path as a consumer install.

## LazyVim / lazy.nvim

```lua
return {
  {
    "WarrenBuffering/android-neovim-lsp",
    main = "android_neovim_lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    opts = {
      inlay_hints = false,
      format_on_save = true,
    },
  },
}
```

That is the intended consumer setup. `main = "android_neovim_lsp"` lets `lazy.nvim` call `require("android_neovim_lsp").setup(opts)` automatically, so users do not need to write a manual `config` function.

The plugin looks for the server in this order:

- `android-neovim-lsp` on `PATH`
- a sibling bundled binary at `../android-neovim-lsp/bin/android-neovim-lsp`
- a local source build at `../server/build/install/server/bin/android-neovim-lsp`

## Release Installer

```bash
curl -fsSL https://raw.githubusercontent.com/WarrenBuffering/android-neovim-lsp/main/packaging/install-release.sh | bash
```

That installs the release bundle into `~/.local/share/android-neovim-lsp` and links `android-neovim-lsp` into `~/.local/bin`.

## Local Consumer-Style Testing

```bash
./packaging/install-local-dev.sh
nvim -u "$PWD/nvim/init.lua"
```

That builds the local server, installs the bundle into the same layout as the release installer, and refreshes the `android-neovim-lsp` launcher symlink in `~/.local/bin`.

The repo's `nvim/init.lua` also bootstraps `lazy.nvim` and `nvim-lspconfig`, so `nvim -u .../nvim/init.lua` behaves like a small consumer config instead of depending on your existing Neovim setup.

## Plugin Options

`android_neovim_lsp.setup()` accepts normal `lspconfig` options plus two plugin-specific toggles:

```lua
require("android_neovim_lsp").setup({
  inlay_hints = false,
  format_on_save = true,
})
```

You can also pass tables:

```lua
require("android_neovim_lsp").setup({
  inlay_hints = {
    enabled = false,
  },
  format_on_save = {
    enabled = true,
    quiet = true,
    lsp_format = "fallback",
    timeout_ms = 5000,
  },
})
```

When `format_on_save` is enabled, the runtime disables LazyVim-style Kotlin autoformatting for that buffer and manages formatting itself synchronously on `BufWritePre`, so the formatted buffer contents are what get written to disk. If [`conform.nvim`](https://github.com/stevearc/conform.nvim) is installed, it is used automatically; otherwise the runtime falls back to `vim.lsp.buf.format()`.

## Mason

`android-neovim-lsp` is not published to Mason in this repository state. If auto-detection is not enough, point `cmd` at the built executable:

```lua
local repo_root = vim.fn.getcwd()

require("android_neovim_lsp").setup({
  cmd = { repo_root .. "/server/build/install/server/bin/android-neovim-lsp" },
})
```
