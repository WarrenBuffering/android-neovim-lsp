return {
  {
    "neovim/nvim-lspconfig",
    opts = function(_, opts)
      local configs = require("lspconfig.configs")
      local lspconfig = require("lspconfig")

      if not configs.android_neovim_lsp then
        configs.android_neovim_lsp = {
          default_config = {
            cmd = { "android-neovim-lsp" },
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
      opts.servers.android_neovim_lsp = {
        on_attach = function(_, bufnr)
          vim.keymap.set("n", "<leader>co", function()
            vim.lsp.buf.code_action({
              context = { only = { "source.organizeImports" } },
              apply = true,
            })
          end, { buffer = bufnr, desc = "Organize Imports" })
        end,
      }

      lspconfig.android_neovim_lsp.setup(opts.servers.android_neovim_lsp)
    end,
  },
}
