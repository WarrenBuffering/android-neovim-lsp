# Neovim Setup

## Minimal `init.lua`

```lua
vim.opt.runtimepath:append("/absolute/path/to/kotlin-neovim-lsp/nvim")
require("kotlin_neovim_lsp").setup()
```

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

`kotlin-neovim-lsp` is not published to Mason in this repository state. Point `cmd` at the built executable:

```lua
require("kotlin_neovim_lsp").setup({
  cmd = { "/absolute/path/to/kotlin-neovim-lsp/server/build/install/server/bin/kotlin-neovim-lsp" },
})
```
