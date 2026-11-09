#!/usr/bin/env bash
# When the lines near the passenger are loaded, and for which position
# (util/NearbyLinesLoader.kt). Compiles the REAL source file; needs kotlinc and
# a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
CORO="$KLIB/kotlinx-coroutines-core-jvm.jar"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$ROOT/app/src/main/java/bg/sofia/transit/util/NearbyLinesLoader.kt" "$HERE/nearbylines/NearbyLinesTests.kt" \
    -cp "$CORO" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar:$CORO" NearbyLinesTestsKt
