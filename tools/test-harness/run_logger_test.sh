#!/usr/bin/env bash
# FileLogger under heavy concurrent use: no exception reaches the caller and
# every line has a well-formed timestamp.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$HERE/logger/log_stub.kt" "$HERE/worker/stubs/android_content.kt" "$HERE/worker/stubs/net.kt" \
    "$ROOT/app/src/main/java/bg/sofia/transit/util/FileLogger.kt" "$HERE/logger/LogTest.kt" -d "$OUT" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT:$KLIB/kotlin-stdlib.jar" LogTestKt
