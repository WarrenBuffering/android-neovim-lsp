local source = debug.getinfo(1, "S").source
if not source or source:sub(1, 1) ~= "@" then
  return {}
end

local config_path = source:sub(2)
local repo_root = vim.fs.normalize(vim.fs.joinpath(config_path, "..", ".."))

return dofile(vim.fs.joinpath(repo_root, "nvim", "lsp", "android_neovim_lsp.lua"))
