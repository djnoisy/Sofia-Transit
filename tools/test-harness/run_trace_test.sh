#!/usr/bin/env bash
# JourneyTrace, the raw record of each journey (see trace/TraceTest.kt). The
# real JourneyTrace.kt is compiled with stubs for the few Android types it
# uses; the vehicle data classes and the distance function are copied out of
# the real sources.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
python3 - "$SRC" "$OUT" <<'PY'
import sys, re
src, out = sys.argv[1:]
rr = open(f"{src}/data/repository/RealtimeRepository.kt", encoding="utf-8").read()
def block(text, start):
    i = text.index(start)
    j = text.index("\n)\n", i) + 2
    return text[i:j]
open(f"{out}/vehicle_data.kt", "w", encoding="utf-8").write(
    "package bg.sofia.transit.data.repository\n\n" +
    block(rr, "data class VehicleInfo(") + "\n" + block(rr, "data class VehicleSnapshot(") + "\n")
lh = open(f"{src}/util/LocationHelper.kt", encoding="utf-8").read()
i = lh.index("    fun distanceMetres(")
j = lh.index("\n    }\n", i) + 6
open(f"{out}/location_helper.kt", "w", encoding="utf-8").write(
    "package bg.sofia.transit.util\nimport kotlin.math.*\nobject LocationHelper {\n" +
    "    private const val EARTH_RADIUS_M = 6_371_000.0\n" + lh[i:j] + "}\n")
PY
"$KOTLINC" "$HERE/logger/log_stub.kt" "$HERE/worker/stubs/android_content.kt" "$HERE/worker/stubs/net.kt" \
    "$HERE/trace/location_stub.kt" "$OUT/vehicle_data.kt" "$OUT/location_helper.kt" \
    "$SRC/util/JourneyTrace.kt" "$HERE/trace/TraceTest.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" TraceTestKt | grep -v "^PASS"
