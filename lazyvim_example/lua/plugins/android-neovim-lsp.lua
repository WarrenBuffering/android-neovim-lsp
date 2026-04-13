return {
  {
    dir = vim.fn.expand("~/.local/share/android-neovim-lsp/nvim"),
    name = "android-neovim-lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    config = function()
      require("android_neovim_lsp").setup({
        on_attach = function(_, bufnr)
          vim.keymap.set("n", "<leader>co", function()
            vim.lsp.buf.code_action({
              context = { only = { "source.organizeImports" } },
              apply = true,
            })
          end, { buffer = bufnr, desc = "Organize Imports" })
        end,
      })
    end,
  },
}
