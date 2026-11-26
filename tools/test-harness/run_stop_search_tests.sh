#!/usr/bin/env bash
# Finding a stop by what is typed (util/StopSearch.kt; Спирки → "Търсене"): made-up cases and the bundled feed's
# stops. Compiles the REAL source file; needs kotlinc and a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$ROOT/app/src/main/java/bg/sofia/transit/util/StopSearch.kt" "$HERE/stopsearch/StopSearchTests.kt" \
    -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" StopSearchTestsKt "$ROOT/app/src/main/assets/gtfs/stops.txt" | grep -v "^PASS"
