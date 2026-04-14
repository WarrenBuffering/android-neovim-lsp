# Neovim Setup

## Minimal `init.lua`

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup({
  inlay_hints = false,
  format_on_save = true,
  block_on_save = false,
})
```

If you used the release installer, this is the default runtime path it creates.

The repo's [`nvim/init.lua`](/Users/andrew/dev/android-neovim-lsp/nvim/init.lua) intentionally uses this installed runtime too, so local testing follows the same loading path as a consumer install.

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
nvim -u /absolute/path/to/android-neovim-lsp/nvim/init.lua
```

That builds the local server, installs the bundle into the same layout as the release installer, and refreshes the `android-neovim-lsp` launcher symlink in `~/.local/bin`.

The repo's `nvim/init.lua` also bootstraps `lazy.nvim` and `nvim-lspconfig`, so `nvim -u .../nvim/init.lua` behaves like a small consumer config instead of depending on your existing Neovim setup.

## Plugin Options

`android_neovim_lsp.setup()` accepts normal `lspconfig` options plus three plugin-specific toggles:

```lua
require("android_neovim_lsp").setup({
  inlay_hints = false,
  format_on_save = true,
  block_on_save = false,
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
  block_on_save = {
    enabled = false,
  },
})
```

When `format_on_save` is enabled, the runtime disables LazyVim-style Kotlin autoformatting for that buffer and manages formatting itself. `block_on_save = false` formats asynchronously after the write, while `block_on_save = true` formats synchronously before the write and blocks until formatting finishes. If [`conform.nvim`](https://github.com/stevearc/conform.nvim) is installed, it is used automatically; otherwise the runtime falls back to `vim.lsp.buf.format()`.

## Mason

`android-neovim-lsp` is not published to Mason in this repository state. If auto-detection is not enough, point `cmd` at the built executable:

```lua
require("android_neovim_lsp").setup({
  cmd = { "/absolute/path/to/android-neovim-lsp/server/build/install/server/bin/android-neovim-lsp" },
})
```
