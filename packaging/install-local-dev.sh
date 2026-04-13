#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENABLE_JETBRAINS_BRIDGE="${ENABLE_JETBRAINS_BRIDGE:-0}"
INSTALL_ROOT="${ANDROID_NEOVIM_LSP_INSTALL_ROOT:-$HOME/.local/share/android-neovim-lsp}"
BIN_DIR="${ANDROID_NEOVIM_LSP_BIN_DIR:-$HOME/.local/bin}"
LINK_BIN="${ANDROID_NEOVIM_LSP_LINK_BIN:-1}"

GRADLE_ARGS=(--no-configuration-cache :server:installDist)
PACKAGE_NAME="android-neovim-lsp"

if [[ "$ENABLE_JETBRAINS_BRIDGE" == "1" || "$ENABLE_JETBRAINS_BRIDGE" == "true" ]]; then
  GRADLE_ARGS=(-Pkotlinls.enableJetBrainsBridge=true "${GRADLE_ARGS[@]}")
  PACKAGE_NAME="${PACKAGE_NAME}-with-jetbrains-bridge"
fi

./gradlew "${GRADLE_ARGS[@]}"

rm -rf "$INSTALL_ROOT"
mkdir -p "$INSTALL_ROOT"

cp -R "$ROOT_DIR/server/build/install/server" "$INSTALL_ROOT/android-neovim-lsp"
cp -R "$ROOT_DIR/nvim" "$INSTALL_ROOT/nvim"
cp -R "$ROOT_DIR/lazyvim_example" "$INSTALL_ROOT/lazyvim_example"
cp "$ROOT_DIR/README.md" "$INSTALL_ROOT/README.md"
mkdir -p "$INSTALL_ROOT/docs"
cp "$ROOT_DIR/docs/DELIVERY.md" "$INSTALL_ROOT/docs/DELIVERY.md"
cp "$ROOT_DIR/docs/VALIDATION.md" "$INSTALL_ROOT/docs/VALIDATION.md"

BIN_PATH="$INSTALL_ROOT/android-neovim-lsp/bin/android-neovim-lsp"
if [[ ! -x "$BIN_PATH" ]]; then
  echo "Installed local bundle did not contain expected launcher: $BIN_PATH" >&2
  exit 1
fi

if [[ "$LINK_BIN" == "1" || "$LINK_BIN" == "true" ]]; then
  mkdir -p "$BIN_DIR"
  ln -sf "$BIN_PATH" "$BIN_DIR/android-neovim-lsp"
  echo "Linked launcher to $BIN_DIR/android-neovim-lsp"
fi

cat <<EOF
Installed local android-neovim-lsp bundle into:
  $INSTALL_ROOT

Launcher:
  $BIN_PATH

Neovim runtime:
  $INSTALL_ROOT/nvim
EOF
