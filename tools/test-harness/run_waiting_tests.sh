#!/usr/bin/env bash
# Where we waited before departure (service/WaitingPlace.kt), for telling which stop we board at: on made-up
# journeys, and with TRACE_DIR on the recorded ones, against the stops of the direction each travelled in the
# bundled feed. Compiles the REAL source file; needs kotlinc and a JDK, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
if [ -n "${TRACE_DIR:-}" ]; then
# Each line's stop patterns: route|lat,lon,name;... — one line per distinct sequence of stops.
python3 - "$ROOT/app/src/main/assets/gtfs" "$OUT/patterns.csv" <<'PY'
import csv, sys, collections
g, out = sys.argv[1:]
route = {t["trip_id"]: t["route_id"] for t in csv.DictReader(open(f"{g}/trips.txt", encoding="utf-8"))}
stops = {r["stop_id"]: (r["stop_lat"], r["stop_lon"], r["stop_name"]) for r in csv.DictReader(open(f"{g}/stops.txt", encoding="utf-8"))}
seqs = collections.defaultdict(list)
for r in csv.DictReader(open(f"{g}/stop_times.txt", encoding="utf-8")): seqs[r["trip_id"]].append((int(r["stop_sequence"]), r["stop_id"]))
pats = set()
for t, s in seqs.items(): pats.add((route[t], tuple(i for _, i in sorted(s))))
with open(out, "w", encoding="utf-8") as f:
    for r, p in sorted(pats):
        f.write(r + "|" + ";".join(f"{stops[i][0]},{stops[i][1]},{stops[i][2].replace(';', ' ').replace(',', ' ')}" for i in p) + "\n")
PY
fi
"$KOTLINC" "$SRC/service/WaitingPlace.kt" "$HERE/waiting/WaitingTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" WaitingTestsKt "$OUT/patterns.csv" | grep -v "^PASS"
