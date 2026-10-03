#!/bin/bash
# Installs kotlinc for the standalone tests in tools/test-harness
# (see tools/test-harness/README.md). Cloud sessions only.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

KOTLIN_VERSION=1.9.24
KOTLIN_HOME="$HOME/.local/kotlinc-$KOTLIN_VERSION"

if [ ! -x "$KOTLIN_HOME/bin/kotlinc" ]; then
  tmp="$(mktemp -d)"
  curl -sSfL -o "$tmp/kotlinc.zip" \
    "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip"
  unzip -q "$tmp/kotlinc.zip" -d "$tmp"
  mkdir -p "$(dirname "$KOTLIN_HOME")"
  rm -rf "$KOTLIN_HOME"
  mv "$tmp/kotlinc" "$KOTLIN_HOME"
  rm -rf "$tmp"
fi

if [ -n "${CLAUDE_ENV_FILE:-}" ]; then
  echo "export PATH=\"$KOTLIN_HOME/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
  echo "export KOTLINC=\"$KOTLIN_HOME/bin/kotlinc\"" >> "$CLAUDE_ENV_FILE"
fi
