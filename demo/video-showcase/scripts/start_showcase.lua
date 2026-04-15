local source = debug.getinfo(1, "S").source
local script_path = source and source:sub(1, 1) == "@" and source:sub(2) or nil
if script_path == nil then
  error("Unable to determine start_showcase.lua path")
end

local repo_root = vim.fs.normalize(vim.fs.joinpath(script_path, "..", "..", "..", ".."))
local showcase_root = vim.fs.normalize(vim.fs.joinpath(repo_root, "demo", "video-showcase"))
local runtime_root = vim.fs.normalize(vim.fs.joinpath(repo_root, "nvim"))
local local_server_cmd = vim.fs.normalize(vim.fs.joinpath(repo_root, "server", "build", "install", "server", "bin", "android-neovim-lsp"))

vim.opt.runtimepath:prepend(runtime_root)

local markers = {
  "settings.gradle.kts",
  "settings.gradle",
  "build.gradle.kts",
  "build.gradle",
  ".git",
}

local plugin_opts = {
  cmd = { local_server_cmd },
  diagnostics = {
    enabled = true,
    debounce_ms = 100,
    flush_on_insert_leave = true,
  },
  inlay_hints = true,
  format_on_save = false,
  block_on_save = false,
  on_attach = function(_, bufnr)
    vim.bo[bufnr].omnifunc = "v:lua.vim.lsp.omnifunc"
  end,
}

local function uses_showcase_server(client)
  if client.name ~= "android_neovim_lsp" then
    return false
  end
  local cmd = client.config and client.config.cmd or nil
  if type(cmd) ~= "table" or type(cmd[1]) ~= "string" then
    return false
  end
  return vim.fs.normalize(cmd[1]) == local_server_cmd
end

local function has_showcase_client(bufnr)
  for _, client in ipairs(vim.lsp.get_clients({ bufnr = bufnr })) do
    if uses_showcase_server(client) then
      return true
    end
  end
  return false
end

local function stop_existing_android_clients()
  if type(vim.lsp.enable) == "function" then
    pcall(vim.lsp.enable, "android_neovim_lsp", false)
  end
  for _, client in ipairs(vim.lsp.get_clients()) do
    if client.name == "android_neovim_lsp" and not uses_showcase_server(client) then
      pcall(client.stop, client, true)
    end
  end
end

if not has_showcase_client(0) then
  stop_existing_android_clients()
  local android_neovim_lsp = require("android_neovim_lsp")

  if type(vim.lsp.config) == "table" and type(vim.lsp.enable) == "function" then
    local config = android_neovim_lsp.default_config(plugin_opts)
    config.root_dir = function(bufnr, on_dir)
      on_dir(vim.fs.root(bufnr, markers) or vim.fs.dirname(vim.api.nvim_buf_get_name(bufnr)))
    end
    vim.lsp.config.android_neovim_lsp = config
    vim.lsp.enable("android_neovim_lsp")
  else
    android_neovim_lsp.setup(plugin_opts)
  end
end

vim.diagnostic.config({
  update_in_insert = false,
})

local driver = dofile(vim.fs.joinpath(showcase_root, "scripts", "showcase_driver.lua"))
driver.start({
  repo_root = repo_root,
  showcase_root = showcase_root,
  leave_open = true,
})
