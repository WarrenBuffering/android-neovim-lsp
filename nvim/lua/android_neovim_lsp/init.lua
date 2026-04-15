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
}
local diagnostics_sync_group = vim.api.nvim_create_augroup("AndroidNeovimLspDiagnostics", { clear = false })
local progress_spinner = {
  active = {},
  order = {},
}
local format_request_timeout_ms = 30000
local format_request_quiet = true
local format_lsp_mode = "fallback"
local diagnostics_debounce_ms = 0
local diagnostics_debounce = {
  pending = {},
  timers = {},
}
local diagnostics_tracking = {
  buffers = {},
}
local flush_buffer_diagnostics
local schedule_buffer_diagnostics_flush
local complete_format
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
  local plugin_opts = {
    inlay_hints_enabled = opts and opts.inlay_hints,
    format_on_save_enabled = opts and opts.format_on_save == true,
    install = {
      enabled = opts and opts.install == true,
      version = nil,
      quiet = false,
      install_root = (opts and opts.install_root) or default_install_root(),
    },
  }
  plugin_opts.install.version = opts and opts.version or nil
  lsp_opts.inlay_hints = nil
  lsp_opts.format_on_save = nil
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

local function echo_bottom(message, highlight)
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

local function perform_format_buffer(bufnr, before_text)
  local function on_complete()
    complete_format(bufnr, before_text)
  end

  local ok, conform = pcall(require, "conform")
  if ok then
    start_format_status()
    local ok_format, attempted = pcall(conform.format, {
      bufnr = bufnr,
      async = false,
      quiet = format_request_quiet,
      lsp_format = format_lsp_mode,
      timeout_ms = format_request_timeout_ms,
    })
    on_complete()
    if not ok_format or attempted == false then
      stop_format_status()
    end
    return
  end

  start_format_status()
  local ok_format = pcall(vim.lsp.buf.format, {
    bufnr = bufnr,
    async = false,
    timeout_ms = format_request_timeout_ms,
  })
  on_complete()
  if not ok_format then
    stop_format_status()
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
  if delay <= 0 then
    return default_handler
  end

  return function(err, params, ctx, config)
    local client = ctx and ctx.client_id and vim.lsp.get_client_by_id(ctx.client_id) or nil
    if client == nil or client.name ~= "android_neovim_lsp" or type(params) ~= "table" or type(params.uri) ~= "string" then
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
  local ok, notification = pcall(vim.notify, "Formatting File...", vim.log.levels.INFO, {
    title = "android-neovim-lsp",
    timeout = 3000,
    hide_from_history = true,
  })
  if not ok then return end
end

stop_format_status = function()
  if format_spinner.active == 0 then
    return
  end

  format_spinner.active = format_spinner.active - 1
  if format_spinner.active > 0 then
    return
  end
end

local function ensure_progress_status_timer()
end

local function stop_progress_status_timer()
end

local function update_progress_status(token, value)
  if type(token) ~= "string" or type(value) ~= "table" then
    return
  end

  local kind = value.kind
  if kind == "begin" then
    local title = value.title or "Indexing"
    local detail = value.message
    local message = detail and detail ~= "" and string.format("%s: %s", title, detail) or string.format("%s...", title)
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
  elseif kind == "end" then
    progress_spinner.active[token] = nil
    stop_progress_status_timer()
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
    echo_bottom(message, "ModeMsg")
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

complete_format = function(bufnr, before_text)
  stop_format_status()
  flash_format_changes(bufnr, before_text)
  local state = diagnostics_tracking.buffers[bufnr]
  if state ~= nil then
    queue_full_buffer_diagnostics_range(bufnr, state)
    schedule_buffer_diagnostics_flush(bufnr, "save")
  end
  show_format_result(bufnr, before_text)
end

local function format_buffer(bufnr)
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
  perform_format_buffer(bufnr, before_text)
end

local function configure_buffer(client, bufnr, plugin_opts)
  ensure_diagnostics_tracking(client, bufnr, plugin_opts)

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
  if plugin_opts.format_on_save_enabled then
    vim.b[bufnr].autoformat = false
    vim.api.nvim_create_autocmd("BufWritePre", {
      group = format_on_save_group,
      buffer = bufnr,
      callback = function(args)
        format_buffer(args.buf)
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
  local default_publish_diagnostics_handler = user_handlers["textDocument/publishDiagnostics"]
    or vim.lsp.handlers["textDocument/publishDiagnostics"]
  local init_options = vim.deepcopy(lsp_opts.init_options or {})
  init_options.diagnostics = vim.tbl_deep_extend(
    "force",
    init_options.diagnostics or {},
    {
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
