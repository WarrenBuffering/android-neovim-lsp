local source = debug.getinfo(1, "S").source
if not source or source:sub(1, 1) ~= "@" then
  error("android-neovim-lsp: unable to locate runtime module")
end

local module_path = source:sub(2)
local repo_root = vim.fs.normalize(vim.fs.joinpath(module_path, "..", "..", ".."))

return dofile(vim.fs.joinpath(repo_root, "nvim", "lua", "android_neovim_lsp", "init.lua"))
