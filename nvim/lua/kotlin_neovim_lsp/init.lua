local M = {}

local function root_dir(fname)
  return vim.fs.root(fname, {
    "settings.gradle.kts",
    "settings.gradle",
    "build.gradle.kts",
    "build.gradle",
    ".git",
  })
end

function M.default_config(opts)
  opts = opts or {}
  return {
    cmd = opts.cmd or { "kotlin-neovim-lsp" },
    filetypes = { "kotlin" },
    root_dir = opts.root_dir or root_dir,
    single_file_support = true,
    init_options = opts.init_options or {},
    settings = opts.settings or {},
    capabilities = opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
  }
end

function M.setup(opts)
  opts = opts or {}
  local ok_lspconfig, lspconfig = pcall(require, "lspconfig")
  if not ok_lspconfig then
    vim.notify("kotlin-neovim-lsp requires nvim-lspconfig", vim.log.levels.ERROR)
    return
  end

  local configs = require("lspconfig.configs")
  if not configs.kotlin_neovim_lsp then
    configs.kotlin_neovim_lsp = {
      default_config = M.default_config(opts),
      docs = {
        description = [[
Standalone Kotlin LSP clone focused on JetBrains-like Kotlin ergonomics for Neovim.
]],
      },
    }
  end

  lspconfig.kotlin_neovim_lsp.setup(vim.tbl_deep_extend("force", M.default_config(opts), opts))
end

return M
