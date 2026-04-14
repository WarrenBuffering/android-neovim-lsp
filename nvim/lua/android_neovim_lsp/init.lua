local M = {}

local format_on_save_group = vim.api.nvim_create_augroup("AndroidNeovimLspFormatOnSave", { clear = false })
local format_spinner_frames = { "-", "\\", "|", "/" }
local format_spinner = {
  active = 0,
  frame = 1,
  timer = nil,
}
local progress_spinner = {
  active = {},
  order = {},
  frame = 1,
  timer = nil,
}

local function empty_map(value)
  if value ~= nil then
    return value
  end
  return vim.empty_dict()
end

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

local function normalize_feature_opts(value, defaults)
  if value == nil then
    return nil
  end
  if type(value) == "boolean" then
    return vim.tbl_deep_extend("force", defaults, { enabled = value })
  end
  if type(value) == "table" then
    return vim.tbl_deep_extend("force", defaults, value)
  end
  return vim.deepcopy(defaults)
end

local function split_setup_opts(opts)
  local lsp_opts = vim.deepcopy(opts or {})
  local plugin_opts = {
    inlay_hints = normalize_feature_opts(opts and opts.inlay_hints, { enabled = true }),
    format_on_save = normalize_feature_opts(opts and opts.format_on_save, {
      enabled = true,
      quiet = true,
      lsp_format = "fallback",
      timeout_ms = 5000,
    }),
    block_on_save = normalize_feature_opts(opts and opts.block_on_save, { enabled = false }),
  }
  lsp_opts.inlay_hints = nil
  lsp_opts.format_on_save = nil
  lsp_opts.block_on_save = nil
  return lsp_opts, plugin_opts
end

local function set_inlay_hints_enabled(bufnr, enabled)
  if not vim.lsp.inlay_hint or type(vim.lsp.inlay_hint.enable) ~= "function" then
    return
  end
  pcall(vim.lsp.inlay_hint.enable, enabled, { bufnr = bufnr })
end

local function buffer_text(bufnr)
  return table.concat(vim.api.nvim_buf_get_lines(bufnr, 0, -1, true), "\n")
end

local function redraw()
  pcall(vim.cmd, "redraw")
end

local function latest_progress_token()
  for index = #progress_spinner.order, 1, -1 do
    local token = progress_spinner.order[index]
    if progress_spinner.active[token] ~= nil then
      return token
    end
    table.remove(progress_spinner.order, index)
  end
  return nil
end

local function render_status()
  local message = nil
  if format_spinner.active > 0 then
    message = string.format("%s Formatting File...", format_spinner_frames[format_spinner.frame])
  else
    local token = latest_progress_token()
    local progress = token and progress_spinner.active[token] or nil
    if progress then
      local title = progress.title or "Indexing"
      local detail = progress.message
      if detail and detail ~= "" then
        message = string.format("%s %s: %s", format_spinner_frames[progress_spinner.frame], title, detail)
      else
        message = string.format("%s %s...", format_spinner_frames[progress_spinner.frame], title)
      end
    end
  end

  vim.api.nvim_echo({ { message or "", "ModeMsg" } }, false, {})
  redraw()
end

local function start_format_status()
  format_spinner.active = format_spinner.active + 1
  if format_spinner.active > 1 then
    return
  end

  format_spinner.frame = 1
  render_status()

  local uv = vim.uv or vim.loop
  if not uv or type(uv.new_timer) ~= "function" then
    return
  end

  format_spinner.timer = uv.new_timer()
  format_spinner.timer:start(120, 120, vim.schedule_wrap(function()
    if format_spinner.active == 0 then
      return
    end
    format_spinner.frame = (format_spinner.frame % #format_spinner_frames) + 1
    render_status()
  end))
end

local function stop_format_status()
  if format_spinner.active == 0 then
    return
  end

  format_spinner.active = format_spinner.active - 1
  if format_spinner.active > 0 then
    return
  end

  if format_spinner.timer then
    format_spinner.timer:stop()
    format_spinner.timer:close()
    format_spinner.timer = nil
  end

  vim.schedule(function()
    render_status()
  end)
end

local function ensure_progress_status_timer()
  if progress_spinner.timer then
    return
  end

  local uv = vim.uv or vim.loop
  if not uv or type(uv.new_timer) ~= "function" then
    return
  end

  progress_spinner.frame = 1
  progress_spinner.timer = uv.new_timer()
  progress_spinner.timer:start(120, 120, vim.schedule_wrap(function()
    if next(progress_spinner.active) == nil or format_spinner.active > 0 then
      return
    end
    progress_spinner.frame = (progress_spinner.frame % #format_spinner_frames) + 1
    render_status()
  end))
end

local function stop_progress_status_timer()
  if next(progress_spinner.active) ~= nil then
    return
  end
  if progress_spinner.timer then
    progress_spinner.timer:stop()
    progress_spinner.timer:close()
    progress_spinner.timer = nil
  end
end

local function update_progress_status(token, value)
  if type(token) ~= "string" or type(value) ~= "table" then
    return
  end

  local kind = value.kind
  if kind == "begin" or kind == "report" then
    if progress_spinner.active[token] == nil then
      table.insert(progress_spinner.order, token)
    end
    progress_spinner.active[token] = {
      title = value.title,
      message = value.message,
      percentage = value.percentage,
    }
    ensure_progress_status_timer()
  elseif kind == "end" then
    progress_spinner.active[token] = nil
    stop_progress_status_timer()
  end

  vim.schedule(render_status)
end

local function count_changed_lines(before_text, after_text)
  if before_text == after_text then
    return 0
  end

  local hunks = vim.diff(before_text, after_text, {
    result_type = "indices",
    algorithm = "histogram",
  })

  if type(hunks) ~= "table" then
    return 0
  end

  local changed_lines = 0
  for _, hunk in ipairs(hunks) do
    changed_lines = changed_lines + math.max(hunk[2] or 0, hunk[4] or 0, 1)
  end
  return changed_lines
end

local function show_format_result(bufnr, before_text)
  if before_text == nil then
    return
  end
  if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
    return
  end

  local changed_lines = count_changed_lines(before_text, buffer_text(bufnr))
  local message
  if changed_lines == 0 then
    message = "No changes made"
  elseif changed_lines == 1 then
    message = "Formatting complete: 1 line changed"
  else
    message = string.format("Formatting complete: %d lines changed", changed_lines)
  end

  vim.schedule(function()
    vim.api.nvim_echo({ { message, "ModeMsg" } }, false, {})
    redraw()
  end)
end

local function complete_format(bufnr, before_text)
  stop_format_status()
  show_format_result(bufnr, before_text)
end

local function format_buffer(bufnr, format_opts)
  if vim.g.autoformat == false then
    return
  end
  if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
    return
  end
  if not vim.bo[bufnr].modifiable or vim.bo[bufnr].readonly then
    return
  end
  local before_text = buffer_text(bufnr)

  local ok, conform = pcall(require, "conform")
  if ok then
    start_format_status()
    local attempted = conform.format({
      bufnr = bufnr,
      async = format_opts.async,
      quiet = format_opts.quiet,
      lsp_format = format_opts.lsp_format,
      timeout_ms = format_opts.timeout_ms,
    }, function()
      complete_format(bufnr, before_text)
    end)
    if attempted == false then
      stop_format_status()
    end
    return
  end

  start_format_status()
  local ok_format = pcall(vim.lsp.buf.format, {
    bufnr = bufnr,
    async = format_opts.async,
    timeout_ms = format_opts.timeout_ms,
  })
  if format_opts.async then
    vim.defer_fn(function()
      complete_format(bufnr, before_text)
    end, format_opts.timeout_ms or 1000)
  else
    complete_format(bufnr, before_text)
  end
  if not ok_format then
    stop_format_status()
  end
end

local function resolve_format_mode(plugin_opts)
  local block_on_save = plugin_opts.block_on_save and plugin_opts.block_on_save.enabled
  return {
    event = block_on_save and "BufWritePre" or "BufWritePost",
    async = not block_on_save,
  }
end

local function configure_buffer(client, bufnr, plugin_opts)
  if plugin_opts.inlay_hints ~= nil then
    local supports_inlay_hints = client.supports_method and client:supports_method("textDocument/inlayHint")
    local inlay_hints_enabled = plugin_opts.inlay_hints.enabled and supports_inlay_hints
    set_inlay_hints_enabled(bufnr, inlay_hints_enabled)
    if not inlay_hints_enabled then
      vim.schedule(function()
        if vim.api.nvim_buf_is_valid(bufnr) then
          set_inlay_hints_enabled(bufnr, false)
        end
      end)
    end
  end

  vim.api.nvim_clear_autocmds({ group = format_on_save_group, buffer = bufnr })
  if plugin_opts.format_on_save and plugin_opts.format_on_save.enabled then
    local format_mode = resolve_format_mode(plugin_opts)
    local format_opts = vim.tbl_deep_extend("force", {}, plugin_opts.format_on_save, {
      async = format_mode.async,
    })
    vim.b[bufnr].autoformat = false
    vim.api.nvim_create_autocmd(format_mode.event, {
      group = format_on_save_group,
      buffer = bufnr,
      callback = function(args)
        format_buffer(args.buf, format_opts)
      end,
    })
  end
end

function M.default_config(opts)
  opts = opts or {}
  local lsp_opts, plugin_opts = split_setup_opts(opts)
  local user_on_attach = lsp_opts.on_attach
  local user_handlers = vim.deepcopy(lsp_opts.handlers or {})
  local default_progress_handler = vim.lsp.handlers["$/progress"]
  user_handlers["$/progress"] = function(err, result, ctx, config)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client and client.name == "android_neovim_lsp" and type(result) == "table" then
      update_progress_status(result.token, result.value)
    end
    if type(default_progress_handler) == "function" then
      return default_progress_handler(err, result, ctx, config)
    end
  end
  lsp_opts.on_attach = function(client, bufnr)
    configure_buffer(client, bufnr, plugin_opts)
    if type(user_on_attach) == "function" then
      user_on_attach(client, bufnr)
    end
  end
  return vim.tbl_deep_extend("force", lsp_opts, {
    cmd = lsp_opts.cmd or detect_cmd(),
    filetypes = { "kotlin" },
    root_dir = lsp_opts.root_dir or root_dir,
    single_file_support = true,
    init_options = empty_map(lsp_opts.init_options),
    settings = empty_map(lsp_opts.settings),
    capabilities = lsp_opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
    handlers = user_handlers,
    on_attach = lsp_opts.on_attach,
  })
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

  lspconfig.android_neovim_lsp.setup(M.default_config(opts))
end

return M
