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

local function plugin_root()
  local source = debug.getinfo(1, "S").source
  if not source or source:sub(1, 1) ~= "@" then
    return nil
  end

  local init_path = source:sub(2)
  return vim.fs.normalize(vim.fs.joinpath(init_path, "..", "..", ".."))
end

local function detect_cmd()
  if vim.fn.executable("android-neovim-lsp") == 1 then
    return { "android-neovim-lsp" }
  end

  local root = plugin_root()
  if not root then
    return { "android-neovim-lsp" }
  end

  local candidates = {
    vim.fs.joinpath(root, "..", "android-neovim-lsp", "bin", "android-neovim-lsp"),
    vim.fs.joinpath(root, "..", "server", "build", "install", "server", "bin", "android-neovim-lsp"),
  }

  for _, candidate in ipairs(candidates) do
    local normalized = vim.fs.normalize(candidate)
    if vim.fn.executable(normalized) == 1 then
      return { normalized }
    end
  end

  return { "android-neovim-lsp" }
end

function M.default_config(opts)
  opts = opts or {}
  return {
    cmd = opts.cmd or detect_cmd(),
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
    vim.notify("android-neovim-lsp requires nvim-lspconfig", vim.log.levels.ERROR)
    return
  end

  local configs = require("lspconfig.configs")
  if not configs.android_neovim_lsp then
    configs.android_neovim_lsp = {
      default_config = M.default_config(opts),
      docs = {
        description = [[
Standalone Android Neovim LSP focused on JetBrains-like Kotlin ergonomics for Neovim.
]],
      },
    }
  end

  lspconfig.android_neovim_lsp.setup(vim.tbl_deep_extend("force", M.default_config(opts), opts))
end

return M
