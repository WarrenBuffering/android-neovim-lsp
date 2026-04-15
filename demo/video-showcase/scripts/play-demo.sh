#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SHOWCASE_ROOT="$ROOT/demo/video-showcase"
INIT_FILE="$SHOWCASE_ROOT/scripts/demo_init.lua"
ENTRY_FILE="$SHOWCASE_ROOT/app/src/main/kotlin/demo/video/app/AppEntry.kt"

COUNTDOWN_MS="${ANDROID_NEOVIM_SHOWCASE_COUNTDOWN_MS:-2500}"
AUTOSTART="${ANDROID_NEOVIM_SHOWCASE_AUTOSTART:-1}"
LEAVE_OPEN="${ANDROID_NEOVIM_SHOWCASE_LEAVE_OPEN:-1}"
SKIP_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --skip-build)
      SKIP_BUILD=1
      ;;
    --no-autostart)
      AUTOSTART=0
      ;;
    --quit)
      LEAVE_OPEN=0
      ;;
    --countdown-ms=*)
      COUNTDOWN_MS="${arg#*=}"
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--skip-build] [--no-autostart] [--quit] [--countdown-ms=2500]" >&2
      exit 1
      ;;
  esac
done

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "Building local language server..."
  "$ROOT/gradlew" -q :server:installDist
  echo "Warming demo project..."
  "$ROOT/gradlew" -q -p "$SHOWCASE_ROOT" build
fi

export NVIM_APPNAME="${NVIM_APPNAME:-android-neovim-lsp-demo}"
export ANDROID_NEOVIM_SHOWCASE_AUTOSTART="$AUTOSTART"
export ANDROID_NEOVIM_SHOWCASE_COUNTDOWN_MS="$COUNTDOWN_MS"
export ANDROID_NEOVIM_SHOWCASE_LEAVE_OPEN="$LEAVE_OPEN"

exec nvim -u "$INIT_FILE" "$ENTRY_FILE"
