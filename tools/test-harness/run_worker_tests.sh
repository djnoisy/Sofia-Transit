#!/usr/bin/env bash
# Static-data install/update: GtfsUpdateWorker (WorkerTests) and its dialogs in
# MainActivity (ActivityTests). Compiles the REAL source files against the
# stubs in worker/, after a few sed substitutions (local test server, short
# time limits). Needs kotlinc (tested with 1.9.24) and a JDK; no Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
CORO="$KLIB/kotlinx-coroutines-core-jvm.jar"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
mkdir -p "$OUT/src"

# The worker, pointed at the local test server (port 18765), with time limits shortened.
sed -e 's|"https://gtfs.sofiatraffic.bg/api/v1/static"|"http://127.0.0.1:18765/static"|' \
    -e 's|INSTALL_DOWNLOAD_MAX_MS = 180_000L|INSTALL_DOWNLOAD_MAX_MS = 3_000L|' \
    -e 's|UPDATE_DOWNLOAD_MAX_MS  = 240_000L|UPDATE_DOWNLOAD_MAX_MS  = 3_000L|' \
    -e 's|BUSY_WAIT_MAX_MS = 180_000L|BUSY_WAIT_MAX_MS = 3_000L|' \
    -e 's|UPDATE_BUSY_WAIT_MAX_MS = 60_000L|UPDATE_BUSY_WAIT_MAX_MS = 2_000L|' \
    "$SRC/worker/GtfsUpdateWorker.kt" > "$OUT/src/GtfsUpdateWorker.kt"
# Older kotlinc needs the explicit flow.collect import; harmless otherwise.
sed 's|^import kotlinx.coroutines.launch|import kotlinx.coroutines.launch\nimport kotlinx.coroutines.flow.collect|' \
    "$SRC/MainActivity.kt" > "$OUT/src/MainActivity.kt"

status=0
echo "== WorkerTests =="
"$KOTLINC" "$HERE"/worker/stubs/*.kt "$OUT/src/GtfsUpdateWorker.kt" "$HERE/worker/test/WorkerTests.kt" \
    -cp "$CORO" -d "$OUT/w" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/w:$KLIB/kotlin-stdlib.jar:$CORO" WorkerTestsKt | grep -v "^PASS" || status=1
echo "== ActivityTests =="
"$KOTLINC" "$HERE"/worker/stubs/*.kt "$HERE"/worker/stubs_ui/*.kt "$OUT/src/GtfsUpdateWorker.kt" \
    "$OUT/src/MainActivity.kt" "$HERE/worker/test/ActivityTests.kt" -cp "$CORO" -d "$OUT/a" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/a:$KLIB/kotlin-stdlib.jar:$CORO" ActivityTestsKt | grep -v "^PASS" || status=1
exit $status
