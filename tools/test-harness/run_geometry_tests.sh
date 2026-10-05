#!/usr/bin/env bash
# Road geometry: service/RouteGeometry.kt on the real shape of bus 213
# (geometry/GeometryTests.kt), the off-road watch on the roads of 213 and 305
# (geometry/OffRoadTests.kt), and the shapes.txt parser in
# data/parser/GtfsParser.kt (geometry/ParserTests.kt). Compiles the REAL
# source files; needs kotlinc and a JDK, no Android SDK.
#
# With GTFS_DIR set to an unpacked CGM static feed, also checks that the
# stops of every trip shape in it fit their road (geometry/NetworkFit.kt).
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
CORO="$KLIB/kotlinx-coroutines-core-jvm.jar"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
status=0

echo "== GeometryTests =="
"$KOTLINC" "$SRC/service/RouteGeometry.kt" "$HERE/geometry/Data213.kt" "$HERE/geometry/GeometryTests.kt" \
    -d "$OUT/g" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/g:$KLIB/kotlin-stdlib.jar" GeometryTestsKt | grep -v "^PASS" || status=1

echo "== OffRoadTests =="
"$KOTLINC" "$SRC/service/RouteGeometry.kt" "$HERE/geometry/Data213.kt" "$HERE/geometry/Data305.kt" \
    "$HERE/geometry/OffRoadTests.kt" -d "$OUT/o" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/o:$KLIB/kotlin-stdlib.jar" OffRoadTestsKt | grep -v "^PASS" || status=1

echo "== ParserTests =="
"$KOTLINC" "$SRC/data/db/entity/Entities.kt" "$SRC/data/parser/GtfsParser.kt" \
    "$HERE/geometry/stubs/room.kt" "$HERE/worker/stubs/android_content.kt" "$HERE/worker/stubs/net.kt" "$HERE/worker/stubs/logger_fake.kt" \
    "$HERE/geometry/ParserTests.kt" -cp "$CORO" -d "$OUT/p" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/p:$KLIB/kotlin-stdlib.jar:$CORO" ParserTestsKt | grep -v "^PASS" || status=1

if [ -n "${GTFS_DIR:-}" ]; then
    echo "== NetworkFit ($GTFS_DIR) =="
    "$KOTLINC" "$SRC/service/RouteGeometry.kt" "$HERE/geometry/NetworkFit.kt" -d "$OUT/n" 2>&1 | grep -A3 "error:" || true
    java -Xmx2g -cp "$OUT/n:$KLIB/kotlin-stdlib.jar" NetworkFitKt "$GTFS_DIR" || status=1
fi
exit $status
