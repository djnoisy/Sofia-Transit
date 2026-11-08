#!/usr/bin/env bash
# The colour of a line's badge (util/LineColors.kt): every line of the bundled
# feed (or GTFS_DIR=<unzipped feed>) gets the colour the data gives it (by id
# and number alone: but for the green X lines), and the text on it is 4.5:1
# or more. Compiles the REAL source
# file; needs kotlinc and a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
GTFS="${GTFS_DIR:-$ROOT/app/src/main/assets/gtfs}"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
"$KOTLINC" "$ROOT/app/src/main/java/bg/sofia/transit/util/LineColors.kt" "$HERE/linecolor/LineColorTests.kt" \
    -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" LineColorTestsKt "$GTFS/routes.txt"
