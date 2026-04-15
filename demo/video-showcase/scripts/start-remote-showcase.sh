#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
START_SCRIPT="$ROOT/demo/video-showcase/scripts/start_showcase.lua"
SERVER_ADDR=""
SKIP_BUILD=0

for arg in "$@"; do
  case "$arg" in
    --skip-build)
      SKIP_BUILD=1
      ;;
    --help)
      echo "Usage: $0 [server-address] [--skip-build]" >&2
      exit 0
      ;;
    *)
      if [[ -z "$SERVER_ADDR" ]]; then
        SERVER_ADDR="$arg"
      else
        echo "Unexpected argument: $arg" >&2
        exit 1
      fi
      ;;
  esac
done

SERVER_ADDR="${SERVER_ADDR:-${NVIM:-${NVIM_SHOWCASE_SERVER:-/tmp/android-neovim-lsp-demo.sock}}}"

if [[ "$SKIP_BUILD" -eq 0 ]]; then
  echo "Building local language server..."
  "$ROOT/gradlew" -q :server:installDist
  echo "Warming demo project..."
  "$ROOT/gradlew" -q -p "$ROOT/demo/video-showcase" build
fi

if [[ -z "$SERVER_ADDR" ]]; then
  echo "No Neovim server address found. Pass one explicitly or set NVIM_SHOWCASE_SERVER." >&2
  exit 1
fi

echo "Triggering showcase in $SERVER_ADDR..."
nvim --server "$SERVER_ADDR" --remote-send "<C-\\><C-N>:lua dofile([[$START_SCRIPT]])<CR>"
