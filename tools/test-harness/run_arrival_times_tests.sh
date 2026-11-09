#!/usr/bin/env bash
# How an arrival card shows its times (util/ArrivalTimes.kt). Compiles the REAL
# source file; needs kotlinc and a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$ROOT/app/src/main/java/bg/sofia/transit/util/ArrivalTimes.kt" "$HERE/arrivaltimes/ArrivalTimesTests.kt" \
    -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ArrivalTimesTestsKt
