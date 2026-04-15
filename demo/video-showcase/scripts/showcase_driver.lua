local M = {}

local uv = vim.uv or vim.loop

local function normalize(path)
  return vim.fs.normalize(path)
end

local function termcodes(keys)
  return vim.api.nvim_replace_termcodes(keys, true, false, true)
end

local function feed(keys)
  vim.api.nvim_feedkeys(termcodes(keys), "xt", false)
end

local function input(keys)
  vim.api.nvim_input(termcodes(keys))
end

local function redraw()
  pcall(vim.cmd, "redraw")
end

local function echo(message, hl)
  vim.api.nvim_echo({ { message, hl or "ModeMsg" } }, false, {})
  redraw()
end

local function count_float_windows()
  local total = 0
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    local config = vim.api.nvim_win_get_config(win)
    if type(config) == "table" and config.relative ~= "" then
      total = total + 1
    end
  end
  return total
end

local function close_float_windows()
  for _, win in ipairs(vim.api.nvim_list_wins()) do
    local config = vim.api.nvim_win_get_config(win)
    if type(config) == "table" and config.relative ~= "" then
      pcall(vim.api.nvim_win_close, win, true)
    end
  end
end

local function wait_until(predicate, opts, callback)
  opts = opts or {}
  local timeout_ms = tonumber(opts.timeout_ms) or 10000
  local interval_ms = tonumber(opts.interval_ms) or 100
  local start_ms = uv.now()

  local function poll()
    local ok, result = pcall(predicate)
    if ok and result then
      callback(true)
      return
    end

    if uv.now() - start_ms >= timeout_ms then
      callback(false)
      return
    end

    vim.defer_fn(poll, interval_ms)
  end

  poll()
end

local function current_path()
  return normalize(vim.api.nvim_buf_get_name(0))
end

local function edit_file(path)
  vim.cmd("edit " .. vim.fn.fnameescape(path))
end

local function center_on(pattern)
  vim.fn.search(pattern, "W")
  vim.cmd("normal! zz")
end

local function with_sequence(actions)
  local index = 1

  local function next_step(delay_ms)
    local delay = math.max(0, tonumber(delay_ms) or 0)
    vim.defer_fn(function()
      local action = actions[index]
      index = index + 1
      if action == nil then
        return
      end
      action(next_step)
    end, delay)
  end

  next_step(0)
end

local function lsp_client()
  for _, client in ipairs(vim.lsp.get_clients({ bufnr = 0 })) do
    if client.name == "android_neovim_lsp" then
      return client
    end
  end
  return nil
end

local function definition_ready()
  if lsp_client() == nil then
    return false
  end
  local params = vim.lsp.util.make_position_params(0)
  local result = vim.lsp.buf_request_sync(0, "textDocument/definition", params, 400)
  if type(result) ~= "table" then
    return false
  end
  for _, response in pairs(result) do
    local item = response and response.result or nil
    if type(item) == "table" and next(item) ~= nil then
      return true
    end
  end
  return false
end

local function line_text(filename, lnum)
  local lines = vim.fn.readfile(filename)
  local line = lines[lnum] or ""
  return vim.trim(line)
end

local function to_qf_items(locations, opts)
  opts = opts or {}
  local items = {}
  for _, location in ipairs(locations or {}) do
    local uri = location.uri or (location.targetUri)
    local range = location.range or location.targetSelectionRange or location.targetRange
    if type(uri) == "string" and type(range) == "table" and type(range.start) == "table" then
      local filename = vim.uri_to_fname(uri)
      local lnum = (range.start.line or 0) + 1
      table.insert(items, {
        filename = filename,
        lnum = lnum,
        col = (range.start.character or 0) + 1,
        text = (opts.prefix and opts.prefix .. "  " or "") .. line_text(filename, lnum),
      })
    end
  end
  return items
end

local function open_references_list(title)
  local params = vim.lsp.util.make_position_params(0)
  params.context = { includeDeclaration = true }
  local result = vim.lsp.buf_request_sync(0, "textDocument/references", params, 1200)
  local merged = {}
  for _, response in pairs(result or {}) do
    for _, location in ipairs(response.result or {}) do
      table.insert(merged, location)
    end
  end
  local items = to_qf_items(merged)
  if vim.tbl_isempty(items) then
    return false
  end
  vim.fn.setqflist({}, " ", {
    title = title,
    items = items,
  })
  vim.cmd("botright copen 8")
  return true
end

local function open_workspace_symbols(query)
  local result = vim.lsp.buf_request_sync(0, "workspace/symbol", { query = query }, 1200)
  local merged = {}
  for _, response in pairs(result or {}) do
    for _, symbol in ipairs(response.result or {}) do
      local location = symbol.location
      if type(location) == "table" then
        local clone = vim.deepcopy(location)
        clone._qf_prefix = symbol.name
        table.insert(merged, clone)
      end
    end
  end
  local items = {}
  for _, location in ipairs(merged) do
    local qf_items = to_qf_items({ location }, { prefix = location._qf_prefix })
    for _, item in ipairs(qf_items) do
      table.insert(items, item)
    end
  end
  if vim.tbl_isempty(items) then
    return false
  end
  vim.fn.setqflist({}, " ", {
    title = "Workspace Symbols: " .. query,
    items = items,
  })
  vim.cmd("botright copen 8")
  return true
end

local function type_text(text, callback)
  local chars = vim.split(text, "\\zs", { trimempty = false })
  local index = 1
  local cadence = { 38, 42, 36, 52, 34, 40 }

  local function step()
    local ch = chars[index]
    if ch == nil then
      callback()
      return
    end
    input(ch)
    index = index + 1
    vim.defer_fn(step, cadence[((index - 2) % #cadence) + 1])
  end

  step()
end

local function start_completion_demo(next_step)
  center_on("\\<val tone = card.tone\\>")
  feed("o")
  type_text("    val preview = tone.", function()
    vim.defer_fn(function()
      feed("<C-x><C-o>")
      next_step(1300)
    end, 120)
  end)
end

local function show_hover(next_step)
  close_float_windows()
  vim.lsp.buf.hover()
  wait_until(function()
    return count_float_windows() > 0
  end, {
    timeout_ms = 1500,
    interval_ms = 60,
  }, function()
    next_step(1400)
  end)
end

local function show_signature_help(next_step)
  close_float_windows()
  vim.lsp.buf.signature_help()
  wait_until(function()
    return count_float_windows() > 0
  end, {
    timeout_ms = 1500,
    interval_ms = 60,
  }, function()
    next_step(1300)
  end)
end

function M.start(opts)
  opts = opts or {}
  local showcase_root = opts.showcase_root or vim.g.demo_showcase_root
  local leave_open = opts.leave_open ~= false

  local app_entry = normalize(vim.fs.joinpath(showcase_root, "app", "src", "main", "kotlin", "demo", "video", "app", "AppEntry.kt"))
  local formatting_file = normalize(vim.fs.joinpath(showcase_root, "app", "src", "main", "kotlin", "demo", "video", "app", "FormattingPlayground.kt"))
  local symbols_file = normalize(vim.fs.joinpath(showcase_root, "app", "src", "main", "kotlin", "demo", "video", "app", "WorkspaceSymbolsPlayground.kt"))
  local java_file = normalize(vim.fs.joinpath(showcase_root, "interop", "src", "main", "java", "demo", "video", "interop", "LegacyHandleFormatter.java"))
  local repo_file = normalize(vim.fs.joinpath(showcase_root, "network", "src", "main", "kotlin", "demo", "video", "network", "FakeVideoRepository.kt"))

  edit_file(app_entry)
  center_on("\\<FakeVideoRepository\\>")
  echo("Showcase warmup...", "ModeMsg")

  wait_until(definition_ready, {
    timeout_ms = 30000,
    interval_ms = 250,
  }, function(ready)
    if ready then
      echo("android-neovim-lsp showcase", "Title")
    else
      echo("Showcase starting before the LSP fully warmed up", "WarningMsg")
    end

    with_sequence({
      function(next_step)
        center_on("\\<VideoCard\\>")
        next_step(450)
      end,
      function(next_step)
        show_hover(next_step)
      end,
      function(next_step)
        close_float_windows()
        center_on("\\<bannerLine\\>")
        next_step(350)
      end,
      function(next_step)
        show_signature_help(next_step)
      end,
      function(next_step)
        close_float_windows()
        center_on("\\<FakeVideoRepository\\>")
        next_step(350)
      end,
      function(next_step)
        vim.lsp.buf.definition()
        wait_until(function()
          return current_path() == repo_file
        end, {
          timeout_ms = 2500,
          interval_ms = 80,
        }, function()
          next_step(1200)
        end)
      end,
      function(next_step)
        center_on("\\<buildPresenterTag\\>")
        next_step(250)
      end,
      function(next_step)
        open_references_list("References: buildPresenterTag")
        next_step(1400)
      end,
      function(next_step)
        vim.cmd("cclose")
        center_on("\\<LegacyHandleFormatter\\>")
        next_step(250)
      end,
      function(next_step)
        vim.lsp.buf.definition()
        wait_until(function()
          return current_path() == java_file
        end, {
          timeout_ms = 2500,
          interval_ms = 80,
        }, function()
          next_step(1300)
        end)
      end,
      function(next_step)
        edit_file(symbols_file)
        center_on("\\<Cue\\>")
        next_step(450)
      end,
      function(next_step)
        open_workspace_symbols("PlaybackState")
        next_step(1300)
      end,
      function(next_step)
        vim.cmd("cclose")
        edit_file(app_entry)
        center_on("\\<completionHotspot\\>")
        next_step(350)
      end,
      function(next_step)
        start_completion_demo(next_step)
      end,
      function(next_step)
        vim.cmd("stopinsert")
        close_float_windows()
        feed("u")
        next_step(700)
      end,
      function(next_step)
        edit_file(formatting_file)
        center_on("\\<object FormattingPlayground\\>")
        next_step(900)
      end,
      function(next_step)
        vim.lsp.buf.format({ async = false })
        next_step(1200)
      end,
      function(next_step)
        edit_file(app_entry)
        center_on("\\<presenterLabel\\>")
        next_step(350)
      end,
      function(next_step)
        local baseline = #vim.diagnostic.get(0)
        feed("ea")
        type_text("x", function()
          vim.defer_fn(function()
            if #vim.diagnostic.get(0) > baseline then
              next_step(1000)
              return
            end
            vim.cmd("stopinsert")
            wait_until(function()
              return #vim.diagnostic.get(0) > baseline
            end, {
              timeout_ms = 2500,
              interval_ms = 80,
            }, function()
              next_step(1200)
            end)
          end, 700)
        end)
      end,
      function(next_step)
        feed("<Esc>")
        next_step(1800)
      end,
      function(next_step)
        feed("u")
        next_step(900)
      end,
      function()
        close_float_windows()
        echo("Showcase complete", "Title")
        if not leave_open then
          vim.defer_fn(function()
            vim.cmd("qa!")
          end, 1200)
        end
      end,
    })
  end)
end

return M
