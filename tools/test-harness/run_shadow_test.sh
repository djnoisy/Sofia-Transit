#!/usr/bin/env bash
# Stage 2 from end to end (shadow/ShadowE2E.kt): journeys written through the
# REAL util/JourneyTrace.kt and given to the REAL service/RideShadow.kt as
# JourneyService does, then the written trace replayed through the model
# (model/Replay.kt) — the replay must give the app's "Model @" lines. The
# Android types JourneyTrace uses are stubbed as in run_trace_test.sh; the
# roads are the real ones, as in run_model_tests.sh. Needs kotlinc and a JDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
GTFS="$ROOT/app/src/main/assets/gtfs"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT

# The vehicle data classes and the distance function, copied out of the real sources.
python3 - "$SRC" "$OUT" <<'PY'
import sys
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

# The road of 213 (A4508) and the stop the journeys start at.
python3 - "$GTFS" "$OUT" <<'PY'
import sys, csv
g, out = sys.argv[1:]
rows = [r for r in csv.DictReader(open(f"{g}/shapes.txt", encoding="utf-8")) if r["shape_id"] == "A4508"]
rows.sort(key=lambda r: int(r["shape_pt_sequence"]))
with open(f"{out}/shapes.csv", "w") as f:
    for r in rows: f.write(f'{r["shape_id"]},{r["shape_pt_lat"]},{r["shape_pt_lon"]}\n')
with open(f"{out}/stops.csv", "w") as f:
    for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8")):
        if r["stop_id"] == "A1196": f.write(f'{r["stop_id"]},{r["stop_lat"]},{r["stop_lon"]}\n')
PY

"$KOTLINC" "$HERE/logger/log_stub.kt" "$HERE/worker/stubs/android_content.kt" "$HERE/worker/stubs/net.kt" \
    "$HERE/trace/location_stub.kt" "$OUT/vehicle_data.kt" "$OUT/location_helper.kt" \
    "$SRC/util/JourneyTrace.kt" "$SRC/service/RideModel.kt" "$SRC/service/RideShadow.kt" \
    "$HERE/model/Replay.kt" "$HERE/model/Sim.kt" "$HERE/shadow/ShadowE2E.kt" \
    -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ShadowE2EKt "$OUT"
