local M = {}
M._runtime_config = nil

local format_on_save_group = vim.api.nvim_create_augroup("AndroidNeovimLspFormatOnSave", { clear = false })
local format_spinner_frames = { "-", "\\", "|", "/" }
local format_spinner = {
  active = 0,
  frame = 1,
  timer = nil,
}
local diagnostics_sync_group = vim.api.nvim_create_augroup("AndroidNeovimLspDiagnostics", { clear = false })
local progress_spinner = {
  active = {},
  order = {},
  frame = 1,
  timer = nil,
}
local diagnostics_debounce = {
  pending = {},
  timers = {},
}
local diagnostics_tracking = {
  buffers = {},
}
local function empty_map(value)
  if value == nil then
    return vim.empty_dict()
  end
  if type(value) == "table" and vim.tbl_isempty(value) then
    return vim.empty_dict()
  end
  return value
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
  local diagnostics_opts = opts and opts.diagnostics
  if diagnostics_opts == nil then
    diagnostics_opts = {}
  end
  local plugin_opts = {
    diagnostics = normalize_feature_opts(diagnostics_opts, {
      enabled = true,
      debounce_ms = 100,
      flush_on_insert_leave = true,
    }),
    inlay_hints = normalize_feature_opts(opts and opts.inlay_hints, { enabled = true }),
    format_on_save = normalize_feature_opts(opts and opts.format_on_save, {
      enabled = true,
      quiet = true,
      lsp_format = "fallback",
      timeout_ms = 5000,
    }),
    block_on_save = normalize_feature_opts(opts and opts.block_on_save, { enabled = false }),
  }
  lsp_opts.diagnostics = nil
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

local function diagnostics_timer_key(client_id, uri)
  return string.format("%s::%s", tostring(client_id or 0), uri or "")
end

local function diagnostics_state_for_uri(uri)
  for bufnr, state in pairs(diagnostics_tracking.buffers) do
    if state.uri == uri then
      return bufnr, state
    end
  end
  return nil, nil
end

local function diagnostics_namespace(client_id)
  local ok, lsp_diagnostic = pcall(function()
    return vim.lsp.diagnostic
  end)
  if not ok or type(lsp_diagnostic) ~= "table" or type(lsp_diagnostic.get_namespace) ~= "function" then
    return nil
  end
  return lsp_diagnostic.get_namespace(client_id, false)
end

local function stop_diagnostics_timer(key)
  local timer = diagnostics_debounce.timers[key]
  if timer then
    timer:stop()
    timer:close()
    diagnostics_debounce.timers[key] = nil
  end
end

local function clear_diagnostics_timers_for_client(client_id)
  local prefix = string.format("%s::", tostring(client_id))
  for key, _ in pairs(diagnostics_debounce.timers) do
    if vim.startswith(key, prefix) then
      diagnostics_debounce.pending[key] = nil
      stop_diagnostics_timer(key)
    end
  end
end

local function clear_diagnostics_tracking_for_client(client_id)
  for bufnr, state in pairs(diagnostics_tracking.buffers) do
    if state.client_id == client_id then
      state.suppressed = false
      state.pending_diagnostics = nil
      state.hidden_diagnostics = nil
      state.awaiting_flush_generation = nil
      local namespace = diagnostics_namespace(client_id)
      if namespace ~= nil then
        vim.diagnostic.show(namespace, bufnr)
      end
    end
  end
end

local function hide_buffer_diagnostics(bufnr, state)
  if state == nil or state.suppressed then
    return
  end

  local client = state.client_id and vim.lsp.get_client_by_id(state.client_id) or nil
  if client == nil or client.name ~= "android_neovim_lsp" then
    return
  end

  local key = diagnostics_timer_key(client.id, state.uri)
  diagnostics_debounce.pending[key] = nil
  stop_diagnostics_timer(key)

  local namespace = diagnostics_namespace(client.id)
  if namespace ~= nil then
    state.hidden_diagnostics = vim.deepcopy(vim.diagnostic.get(bufnr, { namespace = namespace }))
    vim.diagnostic.reset(namespace, bufnr)
  end

  state.pending_diagnostics = nil
  state.suppressed = true
end

local function restore_buffer_diagnostics(bufnr, state)
  if state == nil then
    return
  end

  local client = state.client_id and vim.lsp.get_client_by_id(state.client_id) or nil
  if client == nil or client.name ~= "android_neovim_lsp" then
    return
  end

  local namespace = diagnostics_namespace(client.id)
  if namespace ~= nil then
    if state.hidden_diagnostics ~= nil then
      vim.diagnostic.set(namespace, bufnr, state.hidden_diagnostics)
      state.hidden_diagnostics = nil
    end
    vim.diagnostic.show(namespace, bufnr)
  end
end

local function make_publish_diagnostics_handler(default_handler, debounce_ms)
  if type(default_handler) ~= "function" then
    return default_handler
  end

  local delay = tonumber(debounce_ms) or 0
  if delay <= 0 then
    return default_handler
  end

  return function(err, params, ctx, config)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client == nil or client.name ~= "android_neovim_lsp" or type(params) ~= "table" or type(params.uri) ~= "string" then
      return default_handler(err, params, ctx, config)
    end

    local bufnr, tracked_state = diagnostics_state_for_uri(params.uri)
    if bufnr ~= nil and tracked_state ~= nil and tracked_state.client_id == ctx.client_id and tracked_state.suppressed then
      tracked_state.pending_diagnostics = {
        err = err,
        params = params,
        ctx = ctx,
        config = config,
      }
      return
    end

    local uv = vim.uv or vim.loop
    if not uv or type(uv.new_timer) ~= "function" then
      return default_handler(err, params, ctx, config)
    end

    local key = diagnostics_timer_key(ctx.client_id, params.uri)
    diagnostics_debounce.pending[key] = {
      err = err,
      params = params,
      ctx = ctx,
      config = config,
    }

    local timer = diagnostics_debounce.timers[key]
    if timer then
      timer:stop()
    else
      timer = uv.new_timer()
      diagnostics_debounce.timers[key] = timer
    end

    timer:start(delay, 0, vim.schedule_wrap(function()
      local pending = diagnostics_debounce.pending[key]
      diagnostics_debounce.pending[key] = nil
      stop_diagnostics_timer(key)
      if pending == nil then
        return
      end

      local active_client = pending.ctx and pending.ctx.client_id and vim.lsp.get_client_by_id(pending.ctx.client_id) or nil
      if active_client == nil or active_client.name ~= "android_neovim_lsp" then
        return
      end

      default_handler(pending.err, pending.params, pending.ctx, pending.config)
    end))
  end
end

local function make_diagnostics_flushed_handler(default_handler)
  return function(_, params)
    if type(params) ~= "table" or type(params.textDocument) ~= "table" or type(params.textDocument.uri) ~= "string" then
      return
    end

    local bufnr, state = diagnostics_state_for_uri(params.textDocument.uri)
    if bufnr == nil or state == nil then
      return
    end

    local generation = tonumber(params.generation)
    if state.awaiting_flush_generation ~= nil and generation ~= nil and generation ~= state.awaiting_flush_generation then
      return
    end

    state.awaiting_flush_generation = nil
    state.suppressed = false

    local pending = state.pending_diagnostics
    state.pending_diagnostics = nil
    if pending ~= nil and type(default_handler) == "function" then
      default_handler(pending.err, pending.params, pending.ctx, pending.config)
      return
    end

    restore_buffer_diagnostics(bufnr, state)
  end
end

local function is_insert_like_mode()
  local mode = vim.api.nvim_get_mode().mode
  return type(mode) == "string" and mode:match("^[iR]") ~= nil
end

local function merge_changed_line_range(ranges, start_line, end_line)
  local next_start = math.max(0, tonumber(start_line) or 0)
  local next_end = math.max(next_start + 1, tonumber(end_line) or (next_start + 1))
  local merged = {}
  local inserted = false

  for _, range in ipairs(ranges) do
    if range.end_line < next_start then
      table.insert(merged, range)
    elseif next_end < range.start_line then
      if not inserted then
        table.insert(merged, {
          start_line = next_start,
          end_line = next_end,
        })
        inserted = true
      end
      table.insert(merged, range)
    else
      next_start = math.min(next_start, range.start_line)
      next_end = math.max(next_end, range.end_line)
    end
  end

  if not inserted then
    table.insert(merged, {
      start_line = next_start,
      end_line = next_end,
    })
  end

  for index = 1, #ranges do
    ranges[index] = nil
  end
  for index, range in ipairs(merged) do
    ranges[index] = range
  end
end

local function queue_changed_line_range(bufnr, firstline, lastline, new_lastline)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    return
  end

  hide_buffer_diagnostics(bufnr, state)
  merge_changed_line_range(
    state.changed_lines,
    firstline,
    math.max((tonumber(firstline) or 0) + 1, tonumber(lastline) or 0, tonumber(new_lastline) or 0)
  )
end

local function flush_client_changes(client, bufnr)
  local ok, changetracking = pcall(require, "vim.lsp._changetracking")
  if ok and type(changetracking.flush) == "function" then
    pcall(changetracking.flush, client, bufnr)
  end
end

local function flush_buffer_diagnostics(bufnr, reason)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    return
  end

  state.flush_scheduled = false
  if vim.tbl_isempty(state.changed_lines) then
    return
  end

  local client = state.client_id and vim.lsp.get_client_by_id(state.client_id) or nil
  if client == nil or client.name ~= "android_neovim_lsp" or client.is_stopped and client:is_stopped() then
    return
  end
  if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
    return
  end

  local uri = state.uri
  if uri == nil or uri == "" then
    local name = vim.api.nvim_buf_get_name(bufnr)
    if name == "" then
      return
    end
    uri = vim.uri_from_fname(name)
    state.uri = uri
  end

  state.next_flush_generation = (state.next_flush_generation or 0) + 1
  state.awaiting_flush_generation = state.next_flush_generation
  state.pending_diagnostics = nil

  flush_client_changes(client, bufnr)
  client:notify("$/android-neovim/flushDiagnostics", {
    textDocument = {
      uri = uri,
    },
    changed_lines = vim.deepcopy(state.changed_lines),
    generation = state.awaiting_flush_generation,
    reason = reason or "change",
  })
  state.changed_lines = {}
end

local function schedule_buffer_diagnostics_flush(bufnr, reason)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil or state.flush_scheduled then
    return
  end

  state.flush_scheduled = true
  vim.schedule(function()
    flush_buffer_diagnostics(bufnr, reason)
  end)
end

local function ensure_diagnostics_tracking(client, bufnr, plugin_opts)
  if plugin_opts.diagnostics == nil or plugin_opts.diagnostics.enabled == false then
    return
  end
  if plugin_opts.diagnostics.flush_on_insert_leave ~= true then
    vim.api.nvim_clear_autocmds({ group = diagnostics_sync_group, buffer = bufnr })
    diagnostics_tracking.buffers[bufnr] = nil
    return
  end

  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    state = {
      changed_lines = {},
      flush_scheduled = false,
      client_id = client.id,
      uri = vim.uri_from_bufnr(bufnr),
      attached = false,
      suppressed = false,
      pending_diagnostics = nil,
      hidden_diagnostics = nil,
      next_flush_generation = 0,
      awaiting_flush_generation = nil,
    }
    diagnostics_tracking.buffers[bufnr] = state
  else
    state.client_id = client.id
    state.uri = vim.uri_from_bufnr(bufnr)
  end

  vim.api.nvim_clear_autocmds({ group = diagnostics_sync_group, buffer = bufnr })
  vim.api.nvim_create_autocmd("InsertLeave", {
    group = diagnostics_sync_group,
    buffer = bufnr,
    callback = function(args)
      schedule_buffer_diagnostics_flush(args.buf, "insert_leave")
    end,
  })
  vim.api.nvim_create_autocmd({ "BufUnload", "BufWipeout" }, {
    group = diagnostics_sync_group,
    buffer = bufnr,
    callback = function(args)
      diagnostics_tracking.buffers[args.buf] = nil
    end,
  })

  if state.attached then
    return
  end

  state.attached = true
  vim.api.nvim_buf_attach(bufnr, false, {
    on_lines = function(_, buf, _, firstline, lastline, new_lastline)
      if diagnostics_tracking.buffers[buf] == nil then
        return
      end

      queue_changed_line_range(buf, firstline, lastline, new_lastline)
      if not is_insert_like_mode() then
        schedule_buffer_diagnostics_flush(buf, "change")
      end
    end,
    on_detach = function(_, buf)
      diagnostics_tracking.buffers[buf] = nil
    end,
  })
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
  ensure_diagnostics_tracking(client, bufnr, plugin_opts)

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
  local user_on_exit = lsp_opts.on_exit
  local user_handlers = vim.deepcopy(lsp_opts.handlers or {})
  local default_progress_handler = vim.lsp.handlers["$/progress"]
  local default_publish_diagnostics_handler = user_handlers["textDocument/publishDiagnostics"]
    or vim.lsp.handlers["textDocument/publishDiagnostics"]
  local init_options = vim.deepcopy(lsp_opts.init_options or {})
  if plugin_opts.diagnostics and plugin_opts.diagnostics.enabled ~= false then
    init_options.diagnostics = vim.tbl_deep_extend(
      "force",
      init_options.diagnostics or {},
      {
        fast_debounce_ms = tonumber(plugin_opts.diagnostics.debounce_ms) or 0,
        flush_on_insert_leave = plugin_opts.diagnostics.flush_on_insert_leave == true,
      }
    )
  end
  user_handlers["textDocument/publishDiagnostics"] = make_publish_diagnostics_handler(
    default_publish_diagnostics_handler,
    plugin_opts.diagnostics and plugin_opts.diagnostics.enabled ~= false and plugin_opts.diagnostics.debounce_ms or 0
  )
  user_handlers["$/android-neovim/diagnosticsFlushed"] = make_diagnostics_flushed_handler(
    default_publish_diagnostics_handler
  )
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
  lsp_opts.on_exit = function(code, signal, client_id)
    if client_id ~= nil then
      clear_diagnostics_timers_for_client(client_id)
      clear_diagnostics_tracking_for_client(client_id)
    end
    if type(user_on_exit) == "function" then
      user_on_exit(code, signal, client_id)
    end
  end
  return vim.tbl_deep_extend("force", lsp_opts, {
    cmd = lsp_opts.cmd or detect_cmd(),
    filetypes = { "kotlin" },
    root_dir = lsp_opts.root_dir or root_dir,
    single_file_support = true,
    flags = vim.tbl_deep_extend("keep", lsp_opts.flags or {}, {
      debounce_text_changes = 0,
    }),
    init_options = empty_map(init_options),
    settings = empty_map(lsp_opts.settings),
    capabilities = lsp_opts.capabilities or vim.lsp.protocol.make_client_capabilities(),
    handlers = user_handlers,
    on_attach = lsp_opts.on_attach,
    on_exit = lsp_opts.on_exit,
  })
end

function M.runtime_config()
  return M._runtime_config or M.default_config({})
end

function M.setup(opts)
  opts = opts or {}
  local config = M.default_config(opts)
  M._runtime_config = config
  local server_name = "android_neovim_lsp"
  local has_native_lsp_api = vim.lsp and type(vim.lsp.config) == "function" and type(vim.lsp.enable) == "function"
  local ok_lspconfig, lspconfig = pcall(require, "lspconfig")
  local ok_configs
  local configs

  if ok_lspconfig then
    ok_configs, configs = pcall(require, "lspconfig.configs")
    if ok_configs and configs then
      local docs = {
        default_config = config,
        docs = {
          description = [[
Standalone Android Neovim LSP focused on JetBrains-like Kotlin ergonomics for Neovim.
]],
        },
      }
      if not configs[server_name] then
        configs[server_name] = docs
      end
      if lspconfig[server_name] and type(lspconfig[server_name].setup) == "function" then
        lspconfig[server_name].setup(config)
      end
      return config
    end
  end

  if has_native_lsp_api then
    vim.lsp.config(server_name, config)
    vim.lsp.enable(server_name)
    return config
  end

  vim.notify("android-neovim-lsp requires nvim-lspconfig or Neovim's native vim.lsp.config API", vim.log.levels.ERROR)
end

return M
