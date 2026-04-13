local M = {}

function M.check()
  vim.health.start("android-neovim-lsp")

  if vim.fn.executable("android-neovim-lsp") == 1 then
    vim.health.ok("Found `android-neovim-lsp` on PATH")
  else
    vim.health.error("`android-neovim-lsp` is not on PATH", {
      "Build the server with `./gradlew :server:installDist`.",
      "Add `server/build/install/server/bin` to your PATH, or configure `cmd` explicitly.",
    })
  end

  local ok, _ = pcall(require, "lspconfig")
  if ok then
    vim.health.ok("nvim-lspconfig is available")
  else
    vim.health.error("nvim-lspconfig is not installed")
  end
end

return M
