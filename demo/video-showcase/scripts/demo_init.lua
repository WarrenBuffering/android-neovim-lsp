vim.g.mapleader = " "
vim.g.loaded_netrw = 1
vim.g.loaded_netrwPlugin = 1

local source = debug.getinfo(1, "S").source
local init_path = source and source:sub(1, 1) == "@" and source:sub(2) or nil
if init_path == nil then
  error("Unable to determine demo_init.lua path")
end

local repo_root = vim.fs.normalize(vim.fs.joinpath(init_path, "..", "..", "..", ".."))
local showcase_root = vim.fs.normalize(vim.fs.joinpath(repo_root, "demo", "video-showcase"))
local runtime_root = vim.fs.normalize(vim.fs.joinpath(repo_root, "nvim"))
local driver_path = vim.fs.normalize(vim.fs.joinpath(showcase_root, "scripts", "showcase_driver.lua"))
local local_server_cmd = vim.fs.normalize(vim.fs.joinpath(repo_root, "server", "build", "install", "server", "bin", "android-neovim-lsp"))

vim.opt.runtimepath:prepend(runtime_root)
vim.opt.number = true
vim.opt.relativenumber = false
vim.opt.cursorline = true
vim.opt.signcolumn = "yes"
vim.opt.termguicolors = true
vim.opt.showmode = false
vim.opt.laststatus = 3
vim.opt.cmdheight = 1
vim.opt.completeopt = { "menu", "menuone", "noselect" }
vim.opt.shortmess:append("I")
vim.opt.updatetime = 100
vim.opt.timeoutlen = 300

vim.g.demo_showcase_repo_root = repo_root
vim.g.demo_showcase_root = showcase_root

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

vim.diagnostic.config({
  underline = true,
  update_in_insert = false,
  severity_sort = true,
  virtual_text = {
    spacing = 2,
  },
  float = {
    border = "rounded",
  },
})

if vim.env.ANDROID_NEOVIM_SHOWCASE_AUTOSTART == "1" then
  local countdown_ms = tonumber(vim.env.ANDROID_NEOVIM_SHOWCASE_COUNTDOWN_MS) or 2500
  vim.defer_fn(function()
    local driver = dofile(driver_path)
    driver.start({
      repo_root = repo_root,
      showcase_root = showcase_root,
      leave_open = vim.env.ANDROID_NEOVIM_SHOWCASE_LEAVE_OPEN ~= "0",
    })
  end, countdown_ms)
end
