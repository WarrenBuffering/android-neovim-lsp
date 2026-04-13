vim.g.mapleader = " "
vim.g.loaded_netrw = 1
vim.g.loaded_netrwPlugin = 1

local install_root = vim.env.ANDROID_NEOVIM_LSP_INSTALL_ROOT
  or vim.fn.expand("~/.local/share/android-neovim-lsp")
local runtime_root = vim.fs.normalize(vim.fs.joinpath(install_root, "nvim"))

if vim.fn.isdirectory(runtime_root) ~= 1 then
  error(
    string.format(
      "android-neovim-lsp runtime not found at %s. Run packaging/install-local-dev.sh or the release installer first.",
      runtime_root
    )
  )
end

local lazy_root = vim.fs.normalize(vim.fs.joinpath(vim.fn.stdpath("data"), "lazy", "lazy.nvim"))
if vim.fn.isdirectory(lazy_root) ~= 1 then
  vim.fn.system({
    "git",
    "clone",
    "--filter=blob:none",
    "https://github.com/folke/lazy.nvim.git",
    lazy_root,
  })
end
vim.opt.runtimepath:prepend(lazy_root)

require("lazy").setup({
  {
    dir = runtime_root,
    name = "android-neovim-lsp",
    dependencies = { "neovim/nvim-lspconfig" },
    config = function()
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
    end,
  },
}, {
  change_detection = { notify = false },
  lockfile = vim.fs.normalize(vim.fs.joinpath(vim.fn.stdpath("state"), "android-neovim-lsp-lazy-lock.json")),
})

vim.diagnostic.config({
  underline = true,
  update_in_insert = false,
  severity_sort = true,
  float = { border = "rounded" },
})
