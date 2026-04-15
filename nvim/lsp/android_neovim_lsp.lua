local ok, android_neovim_lsp = pcall(require, "android_neovim_lsp")
if not ok then
  return {}
end

return android_neovim_lsp.runtime_config()
