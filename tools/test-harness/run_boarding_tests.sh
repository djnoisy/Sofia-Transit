#!/usr/bin/env bash
# Trips at a stop around us, or just past it (service/ReachedStops.kt), for
# the early rule by the boarding stop: made-up orders of stops, and every
# order of stops of the network in the bundled feed (or GTFS_DIR=<unzipped
# feed>) — the stop given is always the one the vehicle has just reached,
# never one still ahead of it. Compiles the REAL source file; needs kotlinc
# and a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
GTFS="${GTFS_DIR:-$ROOT/app/src/main/assets/gtfs}"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
# Each distinct order of stops: trip|lat,lon of each stop as id:lat:lon;...
python3 - "$GTFS" "$OUT/orders.txt" <<'PY'
import csv, sys, collections
g, out = sys.argv[1:]
stops = {r["stop_id"]: (r["stop_lat"], r["stop_lon"]) for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8-sig"))}
seqs = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/stop_times.txt", encoding="utf-8-sig")): seqs[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"]))
seen = {}
for t, s in seqs.items():
    p = tuple(i for _, i in sorted(s))
    seen.setdefault(p, t)
with open(out, "w", encoding="utf-8") as f:
    for p, t in sorted(seen.items(), key=lambda x: x[1]):
        f.write(t + "|" + ";".join(f"{i}:{stops[i][0]}:{stops[i][1]}" for i in p) + "\n")
PY
"$KOTLINC" "$SRC/service/ReachedStops.kt" "$HERE/boarding/BoardingTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" BoardingTestsKt "$OUT/orders.txt"
