local M = {}
M._runtime_config = nil
local bootstrap_state = {
  attempted = false,
  success = false,
  last_error = nil,
}

local format_on_save_group = vim.api.nvim_create_augroup("AndroidNeovimLspFormatOnSave", { clear = false })
local format_flash_namespace = vim.api.nvim_create_namespace("AndroidNeovimLspFormatFlash")
local format_spinner = {
  active = 0,
  notification = nil,
  generation = 0,
}
local diagnostics_sync_group = vim.api.nvim_create_augroup("AndroidNeovimLspDiagnostics", { clear = false })
local progress_spinner = {
  active = {},
  order = {},
}
local default_format_request_timeout_ms = 30000
local format_request_timeout_ms = default_format_request_timeout_ms
local diagnostics_debounce_ms = 0
local default_hover_bottom_padding_lines = 2
local active_format_buffers = {}
local next_format_request_id = 0
local diagnostics_debounce = {
  pending = {},
  timers = {},
}
local diagnostics_tracking = {
  buffers = {},
}
local user_commands_registered = false
local initialization_announced_clients = {}
local flush_buffer_diagnostics
local schedule_buffer_diagnostics_flush
local complete_format
local format_buffer
local merge_changed_line_range
local start_format_status
local stop_format_status
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

local function repo_root()
  local root = plugin_root()
  if not root then
    return nil
  end
  return vim.fs.normalize(vim.fs.joinpath(root, ".."))
end

local function default_install_root()
  local configured = vim.env.ANDROID_NEOVIM_LSP_INSTALL_ROOT
  if type(configured) == "string" and configured ~= "" then
    return vim.fs.normalize(configured)
  end
  return vim.fs.normalize(vim.fs.joinpath(vim.fn.expand("~"), ".local", "share", "android-neovim-lsp"))
end

local function read_file(path)
  if path == nil or vim.fn.filereadable(path) ~= 1 then
    return nil
  end
  local lines = vim.fn.readfile(path)
  if type(lines) ~= "table" or vim.tbl_isempty(lines) then
    return nil
  end
  return table.concat(lines, "\n")
end

local function write_file(path, contents)
  local parent = vim.fs.dirname(path)
  if parent ~= nil and parent ~= "" then
    vim.fn.mkdir(parent, "p")
  end
  vim.fn.writefile(vim.split(contents, "\n", { plain = true }), path)
end

local function run_command(args, opts)
  opts = opts or {}
  if type(vim.system) ~= "function" then
    return false, "Neovim auto-install requires vim.system support", -1
  end

  local result = vim.system(args, {
    cwd = opts.cwd,
    env = opts.env,
    text = true,
  }):wait()
  local output = table.concat(vim.tbl_filter(function(part)
    return type(part) == "string" and part ~= ""
  end, {
    result.stdout,
    result.stderr,
  }), "\n")
  return result.code == 0, output, result.code
end

local function detect_existing_cmd(install_root)
  if vim.fn.executable("android-neovim-lsp") == 1 then
    return { "android-neovim-lsp" }
  end

  install_root = install_root or default_install_root()
  local candidates = {}
  local seen = {}
  local runtime_root = plugin_root()
  local repo = repo_root()

  local function add_candidate(path)
    if type(path) ~= "string" or path == "" then
      return
    end
    local normalized = vim.fs.normalize(path)
    if seen[normalized] then
      return
    end
    seen[normalized] = true
    table.insert(candidates, normalized)
  end

  if repo ~= nil then
    add_candidate(vim.fs.joinpath(repo, "server", "build", "install", "server", "bin", "android-neovim-lsp"))
  end
  add_candidate(vim.fs.joinpath(install_root, "android-neovim-lsp", "bin", "android-neovim-lsp"))
  if runtime_root ~= nil then
    add_candidate(vim.fs.joinpath(runtime_root, "..", "android-neovim-lsp", "bin", "android-neovim-lsp"))
  end

  for _, candidate in ipairs(candidates) do
    if vim.fn.executable(candidate) == 1 then
      return { candidate }
    end
  end

  return nil
end

local function detect_cmd()
  local existing = detect_existing_cmd()
  if existing ~= nil then
    return existing
  end
  return { "android-neovim-lsp" }
end

local function managed_install_cmd_path(install_root)
  return vim.fs.normalize(vim.fs.joinpath(install_root, "android-neovim-lsp", "bin", "android-neovim-lsp"))
end

local function should_bootstrap_existing(existing, install_root)
  if existing == nil then
    return true
  end
  local command = existing[1]
  if type(command) ~= "string" or command == "" then
    return false
  end
  if command == "android-neovim-lsp" then
    return false
  end
  return vim.fs.normalize(command) == managed_install_cmd_path(install_root)
end

local function repo_revision_marker_path(install_root)
  return vim.fs.joinpath(install_root, ".plugin-source-revision")
end

local function release_version_marker_path(install_root)
  return vim.fs.joinpath(install_root, ".installed-release-version")
end

local function current_repo_revision(root)
  if root == nil then
    return nil
  end
  local git_dir = vim.fs.joinpath(root, ".git")
  if vim.fn.isdirectory(git_dir) ~= 1 and vim.fn.filereadable(git_dir) ~= 1 then
    return nil
  end
  local ok, output = run_command({ "git", "rev-parse", "HEAD" }, { cwd = root })
  if not ok then
    return nil
  end
  local revision = vim.trim(output or "")
  if revision == "" then
    return nil
  end
  return revision
end

local function installer_scripts(root)
  if root == nil then
    return nil, nil
  end
  local build_script = vim.fs.joinpath(root, "packaging", "install-local-dev.sh")
  local release_script = vim.fs.joinpath(root, "packaging", "install-release.sh")
  if vim.fn.filereadable(build_script) ~= 1 then
    build_script = nil
  end
  if vim.fn.filereadable(release_script) ~= 1 then
    release_script = nil
  end
  return build_script, release_script
end

local function bootstrap_methods(requested_version, build_script, release_script)
  local methods
  if type(requested_version) == "string" and requested_version ~= "" then
    methods = { "release" }
  elseif build_script ~= nil then
    methods = { "build", "release" }
  else
    methods = { "release" }
  end

  return vim.tbl_filter(function(candidate)
    return (candidate == "build" and build_script ~= nil) or (candidate == "release" and release_script ~= nil)
  end, methods)
end

local function should_refresh_repo_build(root, install_root)
  local revision = current_repo_revision(root)
  if revision == nil then
    return false, nil
  end
  local installed = read_file(repo_revision_marker_path(install_root))
  return vim.trim(installed or "") ~= revision, revision
end

local function should_refresh_release_install(install_root, requested_version)
  if type(requested_version) ~= "string" or requested_version == "" or requested_version == "latest" then
    return false
  end
  local installed = read_file(release_version_marker_path(install_root))
  return vim.trim(installed or "") ~= requested_version
end

local function maybe_notify_install(message, level, install_opts)
  if install_opts and install_opts.quiet then
    return
  end
  vim.notify(message, level or vim.log.levels.INFO, { title = "android-neovim-lsp" })
end

local function bootstrap_cmd(install_opts)
  local install_root = (install_opts and install_opts.install_root) or default_install_root()
  local repo = repo_root()
  local build_script, release_script = installer_scripts(repo)
  local existing = detect_existing_cmd(install_root)
  local needs_refresh = false
  local revision = nil
  local requested_version = install_opts and install_opts.version or nil

  if build_script ~= nil and should_bootstrap_existing(existing, install_root) then
    needs_refresh, revision = should_refresh_repo_build(repo, install_root)
  end

  if not needs_refresh and should_bootstrap_existing(existing, install_root) then
    needs_refresh = should_refresh_release_install(install_root, requested_version)
  end

  if existing ~= nil and not needs_refresh then
    return existing
  end

  if install_opts == nil or install_opts.enabled == false then
    return existing or detect_cmd()
  end

  local methods = bootstrap_methods(requested_version, build_script, release_script)
  if vim.tbl_isempty(methods) then
    return existing or detect_cmd()
  end

  if bootstrap_state.attempted and not needs_refresh and bootstrap_state.success then
    return detect_existing_cmd(install_root) or existing or detect_cmd()
  end

  bootstrap_state.attempted = true
  bootstrap_state.success = false
  bootstrap_state.last_error = nil

  local failures = {}
  local env = vim.tbl_extend("force", vim.fn.environ(), {
    ANDROID_NEOVIM_LSP_INSTALL_ROOT = install_root,
  })
  if type(requested_version) == "string" and requested_version ~= "" then
    env.ANDROID_NEOVIM_LSP_VERSION = requested_version
  end

  for _, method in ipairs(methods) do
    local ok
    local output
    local script

    if method == "build" then
      script = build_script
      maybe_notify_install("android-neovim-lsp: building local server bundle...", vim.log.levels.INFO, install_opts)
      ok, output = run_command({ script }, { cwd = repo, env = env })
    elseif method == "release" then
      script = release_script
      maybe_notify_install("android-neovim-lsp: downloading server bundle...", vim.log.levels.INFO, install_opts)
      ok, output = run_command({ script }, { cwd = repo, env = env })
    end

    if ok then
      local refreshed = detect_existing_cmd(install_root)
      if refreshed ~= nil then
        if method == "build" and revision ~= nil then
          write_file(repo_revision_marker_path(install_root), revision)
        end
        bootstrap_state.success = true
        bootstrap_state.last_error = nil
        maybe_notify_install("android-neovim-lsp: server ready", vim.log.levels.INFO, install_opts)
        return refreshed
      end
      table.insert(failures, string.format("%s installer completed but no launcher was found", method))
    else
      local trimmed = vim.trim(output or "")
      if trimmed == "" then
        trimmed = "unknown failure"
      end
      table.insert(failures, string.format("%s failed: %s", method, trimmed))
    end
  end

  bootstrap_state.last_error = table.concat(failures, "\n")
  maybe_notify_install(bootstrap_state.last_error, vim.log.levels.ERROR, install_opts)
  return detect_existing_cmd(install_root) or existing or detect_cmd()
end

local function split_setup_opts(opts)
  local lsp_opts = vim.deepcopy(opts or {})
  local format_timeout_ms = tonumber(opts and opts.format_timeout_ms) or default_format_request_timeout_ms
  local hover_bottom_padding_lines = tonumber(opts and opts.hover_bottom_padding_lines)
    or default_hover_bottom_padding_lines
  local plugin_opts = {
    inlay_hints_enabled = opts and opts.inlay_hints,
    bridge_diagnostics_enabled = opts == nil or opts.bridge_diagnostics ~= false,
    format_on_save_enabled = opts and opts.format_on_save == true,
    format_timeout_ms = format_timeout_ms,
    hover_bottom_padding_lines = math.max(hover_bottom_padding_lines, 0),
    install = {
      enabled = opts and opts.install == true,
      version = nil,
      quiet = false,
      install_root = (opts and opts.install_root) or default_install_root(),
    },
  }
  plugin_opts.install.version = opts and opts.version or nil
  lsp_opts.inlay_hints = nil
  lsp_opts.bridge_diagnostics = nil
  lsp_opts.format_on_save = nil
  lsp_opts.format_timeout_ms = nil
  lsp_opts.hover_bottom_padding_lines = nil
  lsp_opts.install = nil
  lsp_opts.install_root = nil
  lsp_opts.version = nil
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

local function close_notification(notification)
  if type(notification) == "table" and type(notification.close) == "function" then
    pcall(notification.close, notification)
  end
end

local function echo_bottom(message, highlight, opts)
  message = type(message) == "string" and message or ""
  if message == "" then
    return
  end
  local level = vim.log.levels.INFO
  if highlight == "ErrorMsg" then
    level = vim.log.levels.ERROR
  elseif highlight == "WarningMsg" then
    level = vim.log.levels.WARN
  end
  local ok = pcall(vim.notify, message, level, vim.tbl_extend("force", {
    title = "android-neovim-lsp",
  }, opts or {}))
  if not ok then return end
  redraw()
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

local function diagnostics_publish_is_stale(params)
  if type(params) ~= "table" or type(params.uri) ~= "string" then
    return false
  end

  local published_version = tonumber(params.version)
  if published_version == nil then
    return false
  end

  local bufnr = diagnostics_state_for_uri(params.uri)
  if bufnr == nil or not vim.api.nvim_buf_is_valid(bufnr) then
    return false
  end

  local versions = vim.lsp.util and vim.lsp.util.buf_versions or nil
  local current_version = versions and tonumber(versions[bufnr]) or nil
  return current_version ~= nil and published_version < current_version
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
      state.changed_lines = {}
      state.flush_scheduled = false
      state.awaiting_flush_generation = nil
      state.flush_in_flight = false
      state.queued_flush_reason = nil
    end
  end
end

local function queue_next_buffer_diagnostics_flush(state, reason)
  if state == nil then
    return
  end

  state.queued_flush_reason = reason or "change"
end

local function android_format_client(bufnr)
  local clients
  if vim.lsp.get_clients then
    clients = vim.lsp.get_clients({
      bufnr = bufnr,
      name = "android_neovim_lsp",
      method = "textDocument/formatting",
    })
  else
    clients = vim.lsp.get_active_clients({
      bufnr = bufnr,
      name = "android_neovim_lsp",
    })
  end
  return clients and clients[1] or nil
end

local function buffer_changedtick(bufnr)
  if vim.api.nvim_buf_get_changedtick then
    local ok, changedtick = pcall(vim.api.nvim_buf_get_changedtick, bufnr)
    if ok then
      return changedtick
    end
  end
  return vim.b[bufnr].changedtick
end

local function lsp_error_message(err)
  if type(err) == "table" then
    return tostring(err.message or err.data or vim.inspect(err))
  end
  return tostring(err)
end

local function cancel_format_request(state)
  if state == nil or state.client == nil or state.request_id == nil then
    return
  end
  if type(state.client.cancel_request) == "function" then
    pcall(state.client.cancel_request, state.client, state.request_id)
  end
end

local function finish_format_request(bufnr, request_id, ok, message)
  local state = active_format_buffers[bufnr]
  if state == nil or state.id ~= request_id or state.done then
    return false
  end

  state.done = true
  active_format_buffers[bufnr] = nil
  local notification = stop_format_status()
  if ok then
    complete_format(bufnr, state.before_text, notification)
  else
    local formatted = message or "Formatting failed"
    vim.schedule(function()
      echo_bottom(formatted, "ErrorMsg", {
        replace = notification,
        timeout = 3000,
      })
    end)
  end
  return true
end

local function request_android_format(bufnr, timeout_ms, before_text)
  local client = android_format_client(bufnr)
  if client == nil then
    return nil, "android-neovim-lsp formatter is not attached"
  end

  local params
  vim.api.nvim_buf_call(bufnr, function()
    params = vim.lsp.util.make_formatting_params({})
  end)

  next_format_request_id = next_format_request_id + 1
  local request_id = next_format_request_id
  local state = {
    id = request_id,
    done = false,
    client = client,
    request_id = nil,
    before_text = before_text,
    changedtick = buffer_changedtick(bufnr),
  }
  active_format_buffers[bufnr] = state

  local function complete_on_main(fn)
    if vim.in_fast_event and vim.in_fast_event() then
      vim.schedule(fn)
    else
      fn()
    end
  end

  local request_ok, lsp_request_id = client:request("textDocument/formatting", params, function(err, result)
    complete_on_main(function()
      local current = active_format_buffers[bufnr]
      if current == nil or current.id ~= request_id or current.done then
        return
      end
      if err then
        finish_format_request(bufnr, request_id, false, "Formatting failed: " .. lsp_error_message(err))
        return
      end
      if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
        finish_format_request(bufnr, request_id, false, "Formatting failed: buffer is no longer loaded")
        return
      end
      if buffer_changedtick(bufnr) ~= current.changedtick then
        finish_format_request(bufnr, request_id, false, "Formatting skipped: buffer changed while formatting")
        return
      end

      local ok_apply, apply_error = pcall(
        vim.lsp.util.apply_text_edits,
        result or {},
        bufnr,
        client.offset_encoding
      )
      if not ok_apply then
        finish_format_request(bufnr, request_id, false, "Formatting failed: " .. tostring(apply_error))
        return
      end
      finish_format_request(bufnr, request_id, true, nil)
    end)
  end, bufnr)

  if request_ok == false or request_ok == nil then
    active_format_buffers[bufnr] = nil
    return nil, "format request could not be sent"
  end
  state.request_id = lsp_request_id or request_ok

  vim.defer_fn(function()
    local current = active_format_buffers[bufnr]
    if current == nil or current.id ~= request_id or current.done then
      return
    end
    cancel_format_request(current)
    finish_format_request(
      bufnr,
      request_id,
      false,
      string.format("Formatting timed out after %dms", timeout_ms)
    )
  end, timeout_ms)

  return state, nil
end

local function perform_format_buffer(bufnr, before_text, opts)
  opts = opts or {}
  local timeout_ms = tonumber(vim.b[bufnr].android_neovim_lsp_format_timeout_ms) or format_request_timeout_ms
  local function on_failure(message)
    local notification = stop_format_status()
    vim.schedule(function()
      echo_bottom(message, "ErrorMsg", {
        replace = notification,
        timeout = 3000,
      })
    end)
  end

  start_format_status()
  local ok_call, state, format_error = pcall(request_android_format, bufnr, timeout_ms, before_text)
  if not ok_call then
    active_format_buffers[bufnr] = nil
    on_failure("Formatting failed: " .. tostring(state))
    return
  end
  if state == nil then
    active_format_buffers[bufnr] = nil
    on_failure("Formatting failed: " .. tostring(format_error))
    return
  end

  if opts.wait == true then
    local waited = vim.wait(timeout_ms + 250, function()
      return state.done == true
    end, 10)
    if not waited and state.done ~= true then
      cancel_format_request(state)
      finish_format_request(
        bufnr,
        state.id,
        false,
        string.format("Formatting timed out after %dms", timeout_ms)
      )
    end
  end
end

local function finish_buffer_diagnostics_flush(bufnr, state)
  if state == nil then
    return
  end

  state.flush_in_flight = false
end

local function make_publish_diagnostics_handler(default_handler, debounce_ms)
  if type(default_handler) ~= "function" then
    return default_handler
  end

  local delay = tonumber(debounce_ms) or 0

  return function(err, params, ctx, config)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client == nil or client.name ~= "android_neovim_lsp" or type(params) ~= "table" or type(params.uri) ~= "string" then
      return default_handler(err, params, ctx, config)
    end

    if diagnostics_publish_is_stale(params) then
      return
    end

    if delay <= 0 then
      return default_handler(err, params, ctx, config)
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

      if diagnostics_publish_is_stale(pending.params) then
        return
      end

      default_handler(pending.err, pending.params, pending.ctx, pending.config)
    end))
  end
end

local function make_diagnostics_flushed_handler()
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
    finish_buffer_diagnostics_flush(bufnr, state)
  end
end

local function is_insert_like_mode(mode)
  if mode == nil then
    mode = vim.api.nvim_get_mode().mode
  end
  return type(mode) == "string" and mode:match("^[iR]") ~= nil
end

local function is_normal_like_mode(mode)
  return type(mode) == "string" and mode:match("^n") ~= nil
end

merge_changed_line_range = function(ranges, start_line, end_line)
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

local function queue_full_buffer_diagnostics_range(bufnr, state)
  if state == nil then
    return
  end

  local line_count = 1
  if vim.api.nvim_buf_is_valid(bufnr) and vim.api.nvim_buf_is_loaded(bufnr) then
    line_count = math.max(vim.api.nvim_buf_line_count(bufnr), 1)
  end
  merge_changed_line_range(state.changed_lines, 0, line_count)
end

local function queue_changed_line_range(bufnr, firstline, lastline, new_lastline)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    return
  end

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

flush_buffer_diagnostics = function(bufnr, reason)
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
  state.flush_in_flight = true
  state.queued_flush_reason = nil

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

schedule_buffer_diagnostics_flush = function(bufnr, reason)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    return
  end
  queue_next_buffer_diagnostics_flush(state, reason)
  if state.flush_scheduled then
    return
  end

  state.flush_scheduled = true
  vim.schedule(function()
    local next_reason = state.queued_flush_reason or reason
    flush_buffer_diagnostics(bufnr, next_reason)
  end)
end

local function ensure_diagnostics_tracking(client, bufnr, plugin_opts)
  local state = diagnostics_tracking.buffers[bufnr]
  if state == nil then
    state = {
      changed_lines = {},
      flush_scheduled = false,
      client_id = client.id,
      uri = vim.uri_from_bufnr(bufnr),
      attached = false,
      next_flush_generation = 0,
      awaiting_flush_generation = nil,
      flush_in_flight = false,
      queued_flush_reason = nil,
    }
    diagnostics_tracking.buffers[bufnr] = state
  else
    state.client_id = client.id
    state.uri = vim.uri_from_bufnr(bufnr)
  end

  vim.api.nvim_clear_autocmds({ group = diagnostics_sync_group, buffer = bufnr })
  vim.api.nvim_create_autocmd("ModeChanged", {
    group = diagnostics_sync_group,
    buffer = bufnr,
    callback = function(args)
      local event = vim.v.event or {}
      local old_mode = event.old_mode
      local new_mode = event.new_mode
      if is_insert_like_mode(old_mode) and is_normal_like_mode(new_mode) then
        schedule_buffer_diagnostics_flush(args.buf, "insert_leave")
      end
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
end

start_format_status = function()
  format_spinner.active = format_spinner.active + 1
  if format_spinner.active > 1 then
    return
  end
  format_spinner.generation = format_spinner.generation + 1
  local generation = format_spinner.generation
  vim.defer_fn(function()
    if format_spinner.active == 0 or format_spinner.generation ~= generation then
      return
    end
    local ok, notification = pcall(vim.notify, "Formatting File...", vim.log.levels.INFO, {
      title = "android-neovim-lsp",
      timeout = 1200,
      hide_from_history = true,
    })
    if not ok then return end
    format_spinner.notification = notification
  end, 150)
end

stop_format_status = function()
  if format_spinner.active == 0 then
    return nil
  end

  format_spinner.active = format_spinner.active - 1
  if format_spinner.active > 0 then
    return nil
  end
  format_spinner.generation = format_spinner.generation + 1
  local notification = format_spinner.notification
  format_spinner.notification = nil
  close_notification(notification)
  return notification
end

local function ensure_progress_status_timer()
end

local function stop_progress_status_timer()
end

local function progress_message(value)
  local title = value.title or "Indexing"
  local detail = value.message
  local percentage = tonumber(value.percentage)
  local prefix = percentage and string.format("%s %d%%", title, percentage) or title
  if detail and detail ~= "" then
    return string.format("%s: %s", prefix, detail)
  end
  return string.format("%s...", prefix)
end

local function update_progress_status(token, value)
  if type(token) ~= "string" or type(value) ~= "table" then
    return
  end

  local kind = value.kind
  if kind == "begin" then
    local message = progress_message(value)
    local ok, notification = pcall(vim.notify, message, vim.log.levels.INFO, {
      title = "android-neovim-lsp",
      timeout = 3000,
      hide_from_history = true,
    })
    if ok then
      if progress_spinner.active[token] == nil then
        table.insert(progress_spinner.order, token)
      end
      progress_spinner.active[token] = notification
    end
    ensure_progress_status_timer()
  elseif kind == "report" then
    vim.schedule(function()
      local ok, notification = pcall(vim.notify, progress_message(value), vim.log.levels.INFO, {
        title = "android-neovim-lsp",
        timeout = 1500,
        hide_from_history = true,
        replace = progress_spinner.active[token],
      })
      if ok and notification then
        progress_spinner.active[token] = notification
      end
    end)
  elseif kind == "end" then
    vim.schedule(function()
      local ok, notification = pcall(vim.notify, progress_message(value), vim.log.levels.INFO, {
        title = "android-neovim-lsp",
        timeout = 1800,
        hide_from_history = true,
        replace = progress_spinner.active[token],
      })
      if ok and notification then
        progress_spinner.active[token] = notification
      end
      progress_spinner.active[token] = nil
      stop_progress_status_timer()
    end)
  end
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

local function flash_format_changes(bufnr, before_text)
  if before_text == nil then
    return
  end
  if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
    return
  end

  local after_text = buffer_text(bufnr)
  if before_text == after_text then
    return
  end

  local hunks = vim.diff(before_text, after_text, {
    result_type = "indices",
    algorithm = "histogram",
  })
  if type(hunks) ~= "table" or vim.tbl_isempty(hunks) then
    return
  end

  vim.api.nvim_buf_clear_namespace(bufnr, format_flash_namespace, 0, -1)

  local line_count = math.max(vim.api.nvim_buf_line_count(bufnr), 1)
  for _, hunk in ipairs(hunks) do
    local start_line = math.max((hunk[3] or 1) - 1, 0)
    local changed_count = math.max(hunk[4] or 0, 1)
    local end_line = math.min(start_line + changed_count, line_count)
    if start_line >= line_count then
      start_line = line_count - 1
      end_line = line_count
    end
    if start_line >= 0 and end_line > start_line then
      vim.api.nvim_buf_set_extmark(bufnr, format_flash_namespace, start_line, 0, {
        end_row = end_line,
        hl_group = "IncSearch",
        hl_eol = true,
        priority = 250,
      })
    end
  end

  vim.defer_fn(function()
    if vim.api.nvim_buf_is_valid(bufnr) then
      vim.api.nvim_buf_clear_namespace(bufnr, format_flash_namespace, 0, -1)
    end
  end, 180)
end

local function show_format_result(bufnr, before_text, notification)
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
    echo_bottom(message, "ModeMsg", {
      replace = notification,
      timeout = 2000,
    })
  end)
end

local function message_highlight_for_type(message_type)
  if message_type == 1 then
    return "ErrorMsg"
  end
  if message_type == 2 then
    return "WarningMsg"
  end
  return "ModeMsg"
end

local function make_bottom_message_handler(default_handler)
  return function(err, result, ctx, config)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client and client.name == "android_neovim_lsp" and type(result) == "table" then
      local message = result.message
      if type(message) == "string" and message ~= "" then
        vim.schedule(function()
          echo_bottom(message, message_highlight_for_type(result.type))
        end)
        return
      end
    end
    if type(default_handler) == "function" then
      return default_handler(err, result, ctx, config)
    end
  end
end

local function make_hover_handler(default_handler, bottom_padding_lines)
  return function(err, result, ctx, config)
    if err or not result or not result.contents or bottom_padding_lines <= 0 then
      if type(default_handler) == "function" then
        return default_handler(err, result, ctx, config)
      end
      return
    end

    local lines = vim.lsp.util.convert_input_to_markdown_lines(result.contents) or {}
    lines = vim.lsp.util.trim_empty_lines(lines)
    if vim.tbl_isempty(lines) then
      return
    end

    for _ = 1, bottom_padding_lines do
      table.insert(lines, "")
    end
    table.insert(lines, "###")

    config = vim.tbl_extend("force", config or {}, {
      focus_id = ctx and ctx.method or "textDocument/hover",
    })
    return vim.lsp.util.open_floating_preview(lines, "markdown", config)
  end
end

local function active_android_client(bufnr)
  bufnr = bufnr or 0
  local clients
  if vim.lsp.get_clients then
    clients = vim.lsp.get_clients({ bufnr = bufnr, name = "android_neovim_lsp" })
    if not clients or vim.tbl_isempty(clients) then
      clients = vim.lsp.get_clients({ name = "android_neovim_lsp" })
    end
  else
    clients = vim.lsp.get_active_clients({ bufnr = bufnr, name = "android_neovim_lsp" })
    if not clients or vim.tbl_isempty(clients) then
      clients = vim.lsp.get_active_clients({ name = "android_neovim_lsp" })
    end
  end
  return clients and clients[1] or nil
end

local function as_number(value, default)
  local number = tonumber(value)
  if number == nil then
    return default or 0
  end
  return number
end

local function yes_no(value)
  if value == nil then
    return "unknown"
  end
  return value and "yes" or "no"
end

local function percent_text(value)
  return string.format("%.1f%%", as_number(value, 0))
end

local function list_text(value)
  if type(value) ~= "table" or vim.tbl_isempty(value) then
    return "none"
  end
  local items = {}
  for _, item in ipairs(value) do
    table.insert(items, tostring(item))
  end
  return table.concat(items, ", ")
end

local function append_index_overview(lines, report)
  if not report.projectLoaded then
    table.insert(lines, "Project: not loaded")
    if report.message then
      table.insert(lines, "Message: " .. report.message)
    end
    return
  end

  local fast = report.fastIndex or {}
  local support = report.supportCache or {}
  local semantic = report.semanticCache or {}
  table.insert(lines, "Root: " .. tostring(report.root or "unknown"))
  table.insert(lines, "Project generation: " .. tostring(report.projectGeneration or "unknown"))
  table.insert(lines, string.format(
    "Project source index: %d / %d files (%s), %d symbols, ready: %s",
    as_number(fast.filesIndexed),
    as_number(fast.filesTotal),
    percent_text(fast.percentage),
    as_number(fast.symbols),
    yes_no(fast.ready)
  ))
  table.insert(lines, string.format(
    "Open file cache: %d persisted files, %d open documents",
    as_number(fast.cachedFiles),
    as_number(report.openDocuments)
  ))
  table.insert(lines, string.format(
    "Dependency cache: manifest: %s, fully loaded: %s, %d symbols, %d packages",
    yes_no(support.manifestAvailable),
    yes_no(support.fullyLoaded),
    as_number(support.symbols),
    as_number(support.packagesLoaded)
  ))
  table.insert(lines, string.format(
    "Compiler analysis cache: enabled: %s, loaded: %d / %d modules, current: %d, symbol-ready: %d",
    yes_no(semantic.enabled),
    as_number(semantic.modulesLoaded),
    as_number(semantic.modulesTotal),
    as_number(semantic.modulesCurrent),
    as_number(semantic.modulesFullyIndexed)
  ))
end

local function append_index_files(lines, report)
  local files = report.files or {}
  table.insert(lines, "")
  table.insert(lines, "Files")
  if #files == 0 then
    table.insert(lines, "  No file details returned.")
    return
  end
  for _, file in ipairs(files) do
    local mark = file.indexed and "[x]" or "[ ]"
    local flags = {}
    if file.open then
      table.insert(flags, "open")
    end
    if file.cache then
      table.insert(flags, file.cache)
    end
    local suffix = ""
    if #flags > 0 then
      suffix = " (" .. table.concat(flags, ", ") .. ")"
    end
    table.insert(lines, string.format(
      "  %s %s [%s, %s, %d symbols]%s",
      mark,
      tostring(file.relativePath or file.path or "unknown"),
      tostring(file.module or "unknown"),
      tostring(file.rootKind or "source"),
      as_number(file.symbols),
      suffix
    ))
  end
end

local function append_cache_details(lines, report)
  local semantic = report.semanticCache or {}
  local support = report.supportCache or {}
  table.insert(lines, "")
  table.insert(lines, "Compiler Analysis Modules")
  local modules = semantic.modules or {}
  if #modules == 0 then
    table.insert(lines, "  No compiler analysis modules loaded or configured.")
  else
    for _, module in ipairs(modules) do
      local state = "missing"
      if module.validating then
        state = "validating-symbols"
      elseif module.fullyIndexed then
        state = module.compilerReady and "compiler+symbols" or "saved-symbols"
      elseif module.loaded then
        state = module.current and "loaded" or "stale"
      end
      table.insert(lines, string.format(
        "  %s %s: %s, validated: %s, %d symbols, %d files",
        tostring(module.module or "unknown"),
        tostring(module.moduleName or ""),
        state,
        yes_no(module.validated),
        as_number(module.symbols),
        as_number(module.files)
      ))
    end
  end
  table.insert(lines, "")
  table.insert(lines, "Dependency Packages")
  local packages = support.packages or {}
  if #packages == 0 then
    table.insert(lines, "  No dependency packages loaded yet.")
  else
    for _, package_name in ipairs(packages) do
      table.insert(lines, "  " .. tostring(package_name))
    end
  end
end

local function render_index_status(report, view)
  local lines = {
    "Android Neovim LSP Index Status",
    string.rep("=", 32),
    "",
  }
  append_index_overview(lines, report or {})
  if view == "files" or view == "all" then
    append_index_files(lines, report or {})
  end
  if view == "cache" or view == "all" then
    append_cache_details(lines, report or {})
  end
  return lines
end

local function append_request_details(lines, label, request)
  if type(request) ~= "table" then
    table.insert(lines, "    " .. label .. ": none")
    return
  end
  table.insert(lines, string.format(
    "    %s: #%s %s, %sms, success: %s",
    label,
    tostring(request.id or "?"),
    tostring(request.method or "unknown"),
    tostring(request.durationMillis or "?"),
    yes_no(request.success)
  ))
  if request.filePath and request.filePath ~= "" then
    table.insert(lines, "      file: " .. tostring(request.filePath))
  end
  if request.message and request.message ~= "" then
    table.insert(lines, "      message: " .. tostring(request.message))
  end
  if request.error and request.error ~= "" then
    table.insert(lines, "      error: " .. tostring(request.error))
  end
  if request.diagnosticCount ~= nil then
    table.insert(lines, string.format(
      "      results: %d diagnostics, %d completions, %d locations",
      as_number(request.diagnosticCount),
      as_number(request.itemCount),
      as_number(request.locationCount)
    ))
  end
  local diagnostics = request.diagnostics or {}
  if type(diagnostics) == "table" and #diagnostics > 0 then
    table.insert(lines, "      diagnostics:")
    for index, diagnostic in ipairs(diagnostics) do
      if index > 10 then
        table.insert(lines, string.format("        ... %d more", #diagnostics - 10))
        break
      end
      table.insert(lines, string.format(
        "        %s:%s [%s/%s] %s",
        tostring((diagnostic.line or 0) + 1),
        tostring((diagnostic.character or 0) + 1),
        tostring(diagnostic.severity or "?"),
        tostring(diagnostic.code or diagnostic.source or "diagnostic"),
        tostring(diagnostic.message or "")
      ))
    end
  end
end

local function render_bridge_status(report)
  local lines = {
    "Android Neovim LSP Bridge Status",
    string.rep("=", 33),
    "",
  }
  table.insert(lines, "Project: " .. (report.projectLoaded and tostring(report.root or "loaded") or "not loaded"))
  table.insert(lines, "Backend: " .. tostring(report.backend or "unknown"))
  local diagnostics = report.diagnostics or {}
  table.insert(lines, string.format(
    "Diagnostics bridge: requested: %s, enabled: %s, timeout: %sms",
    yes_no(diagnostics.bridgeRequested),
    yes_no(diagnostics.bridgeEnabled),
    tostring(diagnostics.bridgeTimeoutMillis or "?")
  ))

  local scheduler = report.diagnosticScheduler or {}
  table.insert(lines, "")
  table.insert(lines, "Diagnostics Scheduler")
  table.insert(lines, string.format(
    "  running: %s, generation: %s, pending full refresh: %s",
    yes_no(scheduler.running),
    tostring(scheduler.generation or "?"),
    yes_no(scheduler.pendingFullRefresh)
  ))
  table.insert(lines, "  pending uris: " .. list_text(scheduler.pendingUris))
  table.insert(lines, "  forced uris: " .. list_text(scheduler.forceUris))
  table.insert(lines, "  deferred uris: " .. list_text(scheduler.deferredUris))
  if scheduler.publishDueInMillis ~= nil then
    table.insert(lines, "  next publish due in: " .. tostring(scheduler.publishDueInMillis) .. "ms")
  end

  table.insert(lines, "")
  table.insert(lines, "Bridges")
  local bridges = report.bridges or {}
  if #bridges == 0 then
    table.insert(lines, "  No bridges are configured or visible to the server.")
    return lines
  end
  for _, bridge in ipairs(bridges) do
    local runtime = bridge.runtime or {}
    local sync = bridge.sync or {}
    local requests = runtime.requests or {}
    table.insert(lines, string.format(
      "  %s: %s",
      tostring(bridge.name or bridge.type or "bridge"),
      tostring(bridge.state or runtime.state or "unknown")
    ))
    if bridge.startupError then
      table.insert(lines, "    startup error: " .. tostring(bridge.startupError))
    end
    table.insert(lines, "    features: " .. list_text(bridge.features))
    table.insert(lines, string.format(
      "    pid: %s, alive: %s, socket: %s",
      tostring(runtime.pid or "none"),
      yes_no(runtime.alive),
      yes_no(runtime.socketOpen)
    ))
    if runtime.ideaHome then
      table.insert(lines, "    idea home: " .. tostring(runtime.ideaHome))
    end
    if type(runtime.plugins) == "table" and #runtime.plugins > 0 then
      table.insert(lines, "    loaded plugins: " .. list_text(runtime.plugins))
    end
    table.insert(lines, string.format(
      "    requests: %d total, %d succeeded, %d failed",
      as_number(requests.total),
      as_number(requests.succeeded),
      as_number(requests.failed)
    ))
    append_request_details(lines, "current", runtime.currentRequest)
    append_request_details(lines, "last", runtime.lastRequest)
    table.insert(lines, string.format(
      "    foreground requests: %d, in-flight server requests: %d",
      as_number(bridge.foregroundRequests),
      type(bridge.inFlightRequests) == "table" and #bridge.inFlightRequests or 0
    ))
    table.insert(lines, string.format(
      "    sync: startup scheduled: %s, drain scheduled: %s, pending warmups: %d, pending documents: %d",
      yes_no(sync.startupScheduled),
      yes_no(sync.syncDrainScheduled),
      type(sync.pendingProjectWarmups) == "table" and #sync.pendingProjectWarmups or 0,
      type(sync.pendingDocumentSyncs) == "table" and #sync.pendingDocumentSyncs or 0
    ))
  end
  return lines
end

local function open_status_buffer(title, lines)
  local buf = vim.api.nvim_create_buf(false, true)
  local uv = vim.uv or vim.loop
  pcall(vim.api.nvim_buf_set_name, buf, string.format("%s/%d", title, uv.hrtime()))
  vim.bo[buf].buftype = "nofile"
  vim.bo[buf].bufhidden = "wipe"
  vim.bo[buf].swapfile = false
  vim.bo[buf].filetype = "android-neovim-lsp-status"
  local normalized_lines = {}
  for _, line in ipairs(lines or {}) do
    local text = tostring(line or ""):gsub("\r\n", "\n"):gsub("\r", "\n")
    local start = 1
    while true do
      local newline = text:find("\n", start, true)
      if not newline then
        table.insert(normalized_lines, text:sub(start))
        break
      end
      table.insert(normalized_lines, text:sub(start, newline - 1))
      start = newline + 1
    end
  end
  vim.api.nvim_buf_set_lines(buf, 0, -1, false, normalized_lines)
  vim.bo[buf].modifiable = false
  vim.cmd("botright split")
  vim.api.nvim_win_set_buf(0, buf)
  vim.keymap.set("n", "q", function()
    if vim.api.nvim_buf_is_valid(buf) then
      vim.api.nvim_buf_delete(buf, { force = true })
    end
  end, { buffer = buf, silent = true, desc = "Close status view" })
end

local function request_index_status(view)
  local client = active_android_client(0)
  if not client then
    echo_bottom("android-neovim-lsp is not attached to this buffer", "WarningMsg")
    return
  end
  local command = "kotlinls.indexStatus"
  if view == "files" then
    command = "kotlinls.indexedFiles"
  elseif view == "cache" then
    command = "kotlinls.cacheStatus"
  elseif view == "bridges" then
    command = "kotlinls.bridgeStatus"
  end
  client.request("workspace/executeCommand", { command = command }, function(err, result)
    if err then
      vim.schedule(function()
        echo_bottom("Status request failed: " .. tostring(err.message or err), "ErrorMsg")
      end)
      return
    end
    vim.schedule(function()
      local lines = view == "bridges" and render_bridge_status(result or {}) or render_index_status(result or {}, view)
      open_status_buffer("android-neovim-lsp://" .. view, lines)
    end)
  end, 0)
end

local function open_help()
  open_status_buffer("android-neovim-lsp://help", {
    "Android Neovim LSP Commands",
    string.rep("=", 28),
    "",
    ":AndroidNeovimLspHelp",
    "  Show this command reference.",
    "",
    ":AndroidNeovimLspIndexStatus",
    "  Show project source index coverage, open file cache state, dependency cache state, and compiler analysis state.",
    "",
    ":AndroidNeovimLspIndexedFiles",
    "  Show all discovered source files with indexed/missing state, module, root kind, symbol count, and cache source.",
    "",
    ":AndroidNeovimLspCacheStatus",
    "  Show compiler analysis modules and dependency package cache state.",
    "",
    ":AndroidNeovimLspBridgeStatus",
    "  Show JetBrains bridge lifecycle state, active request, last request, diagnostics queue, and pending document syncs.",
    "",
    "Status views are scratch buffers. Press q to close them.",
  })
end

local function register_user_commands()
  if user_commands_registered or not vim.api.nvim_create_user_command then
    return
  end
  user_commands_registered = true
  vim.api.nvim_create_user_command("AndroidNeovimLspHelp", function()
    open_help()
  end, { desc = "Show android-neovim-lsp command help", force = true })
  vim.api.nvim_create_user_command("AndroidNeovimLspIndexStatus", function()
    request_index_status("all")
  end, { desc = "Show android-neovim-lsp index and cache status", force = true })
  vim.api.nvim_create_user_command("AndroidNeovimLspIndexedFiles", function()
    request_index_status("files")
  end, { desc = "Show android-neovim-lsp indexed source files", force = true })
  vim.api.nvim_create_user_command("AndroidNeovimLspCacheStatus", function()
    request_index_status("cache")
  end, { desc = "Show android-neovim-lsp cache status", force = true })
  vim.api.nvim_create_user_command("AndroidNeovimLspBridgeStatus", function()
    request_index_status("bridges")
  end, { desc = "Show android-neovim-lsp bridge status", force = true })
  vim.api.nvim_create_user_command("AndroidNeovimLspFormat", function()
    M.format({ bufnr = vim.api.nvim_get_current_buf(), manual = true })
  end, { desc = "Format current buffer with android-neovim-lsp", force = true })
end

complete_format = function(bufnr, before_text, notification)
  flash_format_changes(bufnr, before_text)
  local state = diagnostics_tracking.buffers[bufnr]
  if state ~= nil then
    queue_full_buffer_diagnostics_range(bufnr, state)
    schedule_buffer_diagnostics_flush(bufnr, "save")
  end
  show_format_result(bufnr, before_text, notification)
end

format_buffer = function(bufnr, opts)
  opts = opts or {}
  if opts.manual ~= true and vim.g.autoformat == false then
    return
  end
  if not vim.api.nvim_buf_is_valid(bufnr) or not vim.api.nvim_buf_is_loaded(bufnr) then
    return
  end
  if not vim.bo[bufnr].modifiable or vim.bo[bufnr].readonly then
    return
  end
  if active_format_buffers[bufnr] then
    if opts.manual == true then
      echo_bottom("Formatting already in progress", "WarningMsg")
    end
    return
  end
  active_format_buffers[bufnr] = true
  local before_text = buffer_text(bufnr)
  perform_format_buffer(bufnr, before_text, {
    wait = opts.manual ~= true,
  })
end

function M.format(opts)
  opts = opts or {}
  format_buffer(opts.bufnr or vim.api.nvim_get_current_buf(), {
    manual = opts.manual ~= false,
  })
end

local function announce_initializing(client)
  local key = client and (client.id or client.name) or "default"
  if initialization_announced_clients[key] then
    return
  end
  initialization_announced_clients[key] = true
  echo_bottom("Android Neovim LSP Initializing", "ModeMsg")
end

local function configure_buffer(client, bufnr, plugin_opts)
  announce_initializing(client)
  ensure_diagnostics_tracking(client, bufnr, plugin_opts)
  vim.b[bufnr].android_neovim_lsp_format_timeout_ms = plugin_opts.format_timeout_ms
  vim.keymap.set("n", "<leader>cf", function()
    M.format({ bufnr = bufnr, manual = true })
  end, { buffer = bufnr, desc = "Format with android-neovim-lsp" })

  if plugin_opts.inlay_hints_enabled ~= nil then
    local supports_inlay_hints = client.supports_method and client:supports_method("textDocument/inlayHint")
    local inlay_hints_enabled = plugin_opts.inlay_hints_enabled and supports_inlay_hints
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
  vim.b[bufnr].autoformat = false
  if plugin_opts.format_on_save_enabled then
    vim.api.nvim_create_autocmd("BufWritePre", {
      group = format_on_save_group,
      buffer = bufnr,
      callback = function(args)
        format_buffer(args.buf, { manual = false })
      end,
    })
  end
end

function M.default_config(opts)
  opts = opts or {}
  local lsp_opts, plugin_opts = split_setup_opts(opts)
  local cmd = lsp_opts.cmd or bootstrap_cmd(plugin_opts.install)
  local user_on_attach = lsp_opts.on_attach
  local user_on_exit = lsp_opts.on_exit
  local user_handlers = vim.deepcopy(lsp_opts.handlers or {})
  local default_show_message_handler = user_handlers["window/showMessage"] or vim.lsp.handlers["window/showMessage"]
  local default_log_message_handler = user_handlers["window/logMessage"] or vim.lsp.handlers["window/logMessage"]
  local default_hover_handler = user_handlers["textDocument/hover"] or vim.lsp.handlers["textDocument/hover"]
  local default_publish_diagnostics_handler = user_handlers["textDocument/publishDiagnostics"]
    or vim.lsp.handlers["textDocument/publishDiagnostics"]
  local init_options = vim.deepcopy(lsp_opts.init_options or {})
  init_options.semantic = vim.tbl_deep_extend(
    "force",
    init_options.semantic or {},
    {
      format_timeout_ms = plugin_opts.format_timeout_ms,
    }
  )
  init_options.diagnostics = vim.tbl_deep_extend(
    "force",
    init_options.diagnostics or {},
    {
      bridge = plugin_opts.bridge_diagnostics_enabled,
      fast_debounce_ms = diagnostics_debounce_ms,
    }
  )
  user_handlers["textDocument/publishDiagnostics"] = make_publish_diagnostics_handler(
    default_publish_diagnostics_handler,
    diagnostics_debounce_ms
  )
  user_handlers["$/android-neovim/diagnosticsFlushed"] = make_diagnostics_flushed_handler()
  user_handlers["$/android-neovim/progress"] = function(_, result, ctx)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client and client.name == "android_neovim_lsp" and type(result) == "table" then
      update_progress_status(result.token, result.value)
    end
  end
  user_handlers["window/showMessage"] = make_bottom_message_handler(default_show_message_handler)
  user_handlers["window/logMessage"] = make_bottom_message_handler(default_log_message_handler)
  user_handlers["textDocument/hover"] = make_hover_handler(
    default_hover_handler,
    plugin_opts.hover_bottom_padding_lines
  )
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
    cmd = cmd,
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
  register_user_commands()
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
