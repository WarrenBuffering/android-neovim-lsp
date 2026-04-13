#!/usr/bin/env bash
set -euo pipefail

REPO="${ANDROID_NEOVIM_LSP_REPO:-WarrenBuffering/android-neovim-lsp}"
VERSION="${ANDROID_NEOVIM_LSP_VERSION:-latest}"
INSTALL_ROOT="${ANDROID_NEOVIM_LSP_INSTALL_ROOT:-$HOME/.local/share/android-neovim-lsp}"
BIN_DIR="${ANDROID_NEOVIM_LSP_BIN_DIR:-$HOME/.local/bin}"
LINK_BIN="${ANDROID_NEOVIM_LSP_LINK_BIN:-1}"
ASSET_NAME="android-neovim-lsp.tar.gz"
CHECKSUM_NAME="${ASSET_NAME}.sha256"

need_cmd() {
  command -v "$1" >/dev/null 2>&1 || {
    echo "Missing required command: $1" >&2
    exit 1
  }
}

need_cmd curl
need_cmd tar
need_cmd mktemp

api_url() {
  if [[ "$VERSION" == "latest" ]]; then
    printf 'https://api.github.com/repos/%s/releases/latest' "$REPO"
  else
    printf 'https://api.github.com/repos/%s/releases/tags/%s' "$REPO" "$VERSION"
  fi
}

download_url() {
  printf 'https://github.com/%s/releases/download/%s/%s' "$REPO" "$1" "$2"
}

detect_sha_tool() {
  if command -v shasum >/dev/null 2>&1; then
    echo "shasum -a 256"
    return
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    echo "sha256sum"
    return
  fi
  echo ""
}

TMP_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

RELEASE_JSON="$TMP_DIR/release.json"
curl -fsSL "$(api_url)" -o "$RELEASE_JSON"

TAG_NAME="$(python3 - "$RELEASE_JSON" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as fh:
    data = json.load(fh)
tag = data.get("tag_name")
if not tag:
    raise SystemExit("Could not determine release tag_name from GitHub API response")
print(tag)
PY
)"

ARCHIVE_PATH="$TMP_DIR/$ASSET_NAME"
CHECKSUM_PATH="$TMP_DIR/$CHECKSUM_NAME"

echo "Downloading $ASSET_NAME from $REPO@$TAG_NAME"
curl -fL "$(download_url "$TAG_NAME" "$ASSET_NAME")" -o "$ARCHIVE_PATH"

if curl -fsSL "$(download_url "$TAG_NAME" "$CHECKSUM_NAME")" -o "$CHECKSUM_PATH"; then
  SHA_TOOL="$(detect_sha_tool)"
  if [[ -n "$SHA_TOOL" ]]; then
    (
      cd "$TMP_DIR"
      $SHA_TOOL -c "$CHECKSUM_NAME"
    )
  else
    echo "No SHA-256 tool found; skipping checksum verification" >&2
  fi
else
  echo "No checksum asset found; skipping checksum verification" >&2
fi

rm -rf "$INSTALL_ROOT"
mkdir -p "$INSTALL_ROOT"
tar -xzf "$ARCHIVE_PATH" -C "$INSTALL_ROOT" --strip-components=1

BIN_PATH="$INSTALL_ROOT/android-neovim-lsp/bin/android-neovim-lsp"
if [[ ! -x "$BIN_PATH" ]]; then
  echo "Installed bundle did not contain expected launcher: $BIN_PATH" >&2
  exit 1
fi

if [[ "$LINK_BIN" == "1" || "$LINK_BIN" == "true" ]]; then
  mkdir -p "$BIN_DIR"
  ln -sf "$BIN_PATH" "$BIN_DIR/android-neovim-lsp"
  echo "Linked launcher to $BIN_DIR/android-neovim-lsp"
fi

cat <<EOF
Installed android-neovim-lsp into:
  $INSTALL_ROOT

Launcher:
  $BIN_PATH

Neovim runtime:
  $INSTALL_ROOT/nvim
EOF

