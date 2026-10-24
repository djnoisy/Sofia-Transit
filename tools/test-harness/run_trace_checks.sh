#!/usr/bin/env bash
# Checks of one recorded journey from the phone (journey_trace.txt), as done
# for every journey in stage 2 (HANDOVER, "Етап 2"):
#
#   run_trace_checks.sh --shadow <trace> <log>   replaying the trace gives the log's
#                                                "Model" lines (one journey per trace file)
#   run_trace_checks.sh --stress <trace>         thinned fixes, dropped fixes, tracking
#                                                begun mid-way: does the model stay right?
#
# Compiles the REAL service/RideModel.kt and service/RideShadow.kt; needs kotlinc
# and a JDK. Traces hold the owner's positions: they are not kept in the repository.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/../../app/src/main/java/bg/sofia/transit/service"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
case "${1:-}" in
  --shadow)
    "$KOTLINC" "$SRC/RideModel.kt" "$SRC/RideShadow.kt" "$HERE/model/ShadowCmp.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
    java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ShadowCmpKt "$2" 2>/dev/null | sed 's/^[ID] //' > "$OUT/replay.txt"
    grep -o "RideModel: .*" "$3" | sed 's/^RideModel: //' | grep -v "at the end\|(boarding stop" > "$OUT/log.txt" || true
    if diff "$OUT/replay.txt" "$OUT/log.txt"; then echo "same Model lines as the log ($(wc -l < "$OUT/log.txt"))"; else echo "DIFFERENT (< replay, > log)"; exit 1; fi ;;
  --stress)
    "$KOTLINC" "$SRC/RideModel.kt" "$SRC/RideShadow.kt" "$HERE/model/Replay.kt" "$HERE/model/Stress.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
    java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" StressKt "$2" 2>/dev/null | sed 's/\[.*//' ;;
  *) echo "usage: $0 --shadow <trace> <log> | --stress <trace>"; exit 2 ;;
esac
