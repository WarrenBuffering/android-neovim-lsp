return {
  {
    "WarrenBuffering/android-neovim-lsp",
    main = "android_neovim_lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    opts = {
      on_attach = function(_, bufnr)
        vim.keymap.set("n", "<leader>co", function()
          vim.lsp.buf.code_action({
            context = { only = { "source.organizeImports" } },
            apply = true,
          })
        end, { buffer = bufnr, desc = "Organize Imports" })
      end,
    },
  },
}
