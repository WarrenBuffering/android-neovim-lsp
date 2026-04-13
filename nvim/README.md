# Neovim Setup

## Minimal `init.lua`

```lua
vim.opt.runtimepath:append(vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"))
require("kotlin_neovim_lsp").setup()
```

If you used the release installer, this is the default runtime path it creates.

The plugin looks for the server in this order:

- `android-neovim-lsp` on `PATH`
- a sibling bundled binary at `../android-neovim-lsp/bin/android-neovim-lsp`
- a local source build at `../server/build/install/server/bin/android-neovim-lsp`

## Release Installer

```bash
curl -fsSL https://raw.githubusercontent.com/WarrenBuffering/android-neovim-lsp/main/packaging/install-release.sh | bash
```

That installs the release bundle into `~/.local/share/android-neovim-lsp` and links `android-neovim-lsp` into `~/.local/bin`.

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
require("kotlin_neovim_lsp").setup({
  cmd = { "/absolute/path/to/android-neovim-lsp/server/build/install/server/bin/android-neovim-lsp" },
})
```
