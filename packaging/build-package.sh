#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ENABLE_JETBRAINS_BRIDGE="${ENABLE_JETBRAINS_BRIDGE:-0}"
GRADLE_ARGS=(--no-configuration-cache :server:installDist)
PACKAGE_NAME="android-neovim-lsp"

if [[ "$ENABLE_JETBRAINS_BRIDGE" == "1" || "$ENABLE_JETBRAINS_BRIDGE" == "true" ]]; then
  GRADLE_ARGS=(-Pkotlinls.enableJetBrainsBridge=true "${GRADLE_ARGS[@]}")
  PACKAGE_NAME="${PACKAGE_NAME}-with-jetbrains-bridge"
fi

./gradlew "${GRADLE_ARGS[@]}"

PACKAGE_DIR="$ROOT_DIR/packaging/dist"
rm -rf "$PACKAGE_DIR"
mkdir -p "$PACKAGE_DIR"

BUNDLE_DIR="$PACKAGE_DIR/$PACKAGE_NAME"
mkdir -p "$BUNDLE_DIR"
cp -R "$ROOT_DIR/server/build/install/server" "$BUNDLE_DIR/$PACKAGE_NAME"
cp -R "$ROOT_DIR/nvim" "$BUNDLE_DIR/nvim"
cp -R "$ROOT_DIR/lazyvim_example" "$BUNDLE_DIR/lazyvim_example"
cp "$ROOT_DIR/README.md" "$BUNDLE_DIR/README.md"
mkdir -p "$BUNDLE_DIR/docs"
cp "$ROOT_DIR/docs/DELIVERY.md" "$BUNDLE_DIR/docs/DELIVERY.md"
cp "$ROOT_DIR/docs/VALIDATION.md" "$BUNDLE_DIR/docs/VALIDATION.md"

TARBALL="$PACKAGE_DIR/$PACKAGE_NAME.tar.gz"
tar \
  --uid 0 \
  --gid 0 \
  --uname root \
  --gname wheel \
  -C "$PACKAGE_DIR" \
  -czf "$TARBALL" \
  "$PACKAGE_NAME"
(
  cd "$PACKAGE_DIR"
  shasum -a 256 "$(basename "$TARBALL")" > "$(basename "$TARBALL").sha256"
)

echo "Package created at $BUNDLE_DIR"
echo "Tarball created at $TARBALL"
