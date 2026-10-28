#!/usr/bin/env bash
# The directions of a line and the route each takes, from the trips running that day
# (data/repository/RouteOfTheDay.kt; see route/RouteTests.kt): on made-up trips, and on the bundled
# feed for DATES (YYYYMMDD, comma-separated; default today and the six days after, in Sofia) against the rule it
# replaced. With GTFS_DIR set to an unpacked static feed (e.g. today's, from gtfs.sofiatraffic.bg/api/v1/static),
# on that one as well.
# Compiles the REAL source file; needs kotlinc, a JDK and python3, no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
DATES="${DATES:-$(for i in 0 1 2 3 4 5 6; do TZ=Europe/Sofia date -d "+$i day" +%Y%m%d; done | paste -sd,)}"
"$KOTLINC" "$SRC/data/repository/RouteOfTheDay.kt" "$HERE/route/RouteTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
run() { java -Xmx4g -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" RouteTestsKt "$@" | grep -v "^PASS"; }
status=0
python3 "$HERE/route/route_extract.py" "$ROOT/app/src/main/assets/gtfs" "$DATES" "$OUT/bundled.txt"
run "$OUT/bundled.txt" "bundled feed ($(cat "$ROOT/app/src/main/assets/gtfs/bundle_date.txt"))" || status=1
if [ -n "${GTFS_DIR:-}" ]; then
    python3 "$HERE/route/route_extract.py" "$GTFS_DIR" "$DATES" "$OUT/feed.txt"
    run "$OUT/feed.txt" "GTFS_DIR feed" || status=1
fi
exit $status
