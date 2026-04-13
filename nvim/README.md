# Neovim Setup

## Minimal `init.lua`

```lua
vim.opt.runtimepath:prepend(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("android_neovim_lsp").setup()
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

## Formatting on Save

```lua
vim.api.nvim_create_autocmd("BufWritePre", {
  pattern = "*.kt",
  callback = function(args)
    vim.lsp.buf.format({ bufnr = args.buf, async = false })
  end,
})
```

## Mason

`android-neovim-lsp` is not published to Mason in this repository state. If auto-detection is not enough, point `cmd` at the built executable:

```lua
require("android_neovim_lsp").setup({
  cmd = { "/absolute/path/to/android-neovim-lsp/server/build/install/server/bin/android-neovim-lsp" },
})
```
