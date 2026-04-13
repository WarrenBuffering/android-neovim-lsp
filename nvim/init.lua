vim.g.mapleader = " "

require("android_neovim_lsp").setup({
  cmd = { "android-neovim-lsp" },
  on_attach = function(_, bufnr)
    local map = function(mode, lhs, rhs)
      vim.keymap.set(mode, lhs, rhs, { buffer = bufnr, silent = true })
    end

    map("n", "gd", vim.lsp.buf.definition)
    map("n", "gr", vim.lsp.buf.references)
    map("n", "K", vim.lsp.buf.hover)
    map("n", "<leader>cr", vim.lsp.buf.rename)
    map("n", "<leader>ca", vim.lsp.buf.code_action)
    map("n", "<leader>cf", function()
      vim.lsp.buf.format({ async = false })
    end)
  end,
})

vim.diagnostic.config({
  underline = true,
  update_in_insert = false,
  severity_sort = true,
  float = { border = "rounded" },
})
