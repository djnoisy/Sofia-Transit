#!/usr/bin/env bash
# RideModel — which vehicle we are in and when we get off, by metres
# travelled together (model/ModelTests.kt): simulated journeys along the real
# roads of 213 and 304 (model/Sim.kt), and, with TRACE_DIR set to a folder of
# journey traces from the phone (journey_trace.txt), the recorded journeys.
# Compiles the REAL service/RideModel.kt; needs kotlinc and a JDK.
#
#   run_model_tests.sh                       the tests
#   run_model_tests.sh --replay <trace>      what the model concludes on a trace
#
# Traces hold the owner's positions: they are not kept in the repository.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
GTFS="$ROOT/app/src/main/assets/gtfs"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT

"$KOTLINC" "$SRC/service/RideModel.kt" "$HERE/model/Replay.kt" "$HERE/model/Sim.kt" "$HERE/model/ModelTests.kt" \
    -d "$OUT/c" 2>&1 | grep -A3 "error:" || true

if [ "${1:-}" = "--replay" ]; then
    java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ReplayKt "$2"
    exit 0
fi

# The roads of 213 (A4508) and 304 (A2791) and the stops the tests use.
python3 - "$GTFS" "$OUT" <<'PY'
import sys, csv
g, out = sys.argv[1:]
want = {"A4508", "A2791"}
rows = [r for r in csv.DictReader(open(f"{g}/shapes.txt", encoding="utf-8")) if r["shape_id"] in want]
rows.sort(key=lambda r: (r["shape_id"], int(r["shape_pt_sequence"])))
with open(f"{out}/shapes.csv", "w") as f:
    for r in rows: f.write(f'{r["shape_id"]},{r["shape_pt_lat"]},{r["shape_pt_lon"]}\n')
stops = {"A1196", "A2327", "A1290", "A1289", "A0440", "A1914"}
with open(f"{out}/stops.csv", "w") as f:
    for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8")):
        if r["stop_id"] in stops: f.write(f'{r["stop_id"]},{r["stop_lat"]},{r["stop_lon"]}\n')
PY
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ModelTestsKt "$OUT" | grep -v "^PASS"
