#!/usr/bin/env bash
# The compile check of every change: all Kotlin sources of the app compiled with kotlinc alone — without the
# Android SDK, so the Android, AndroidX, Room… types do not resolve — and the errors, without line numbers and
# sorted, compared with compile/baseline.err, the errors of the last checked version. The same errors: nothing
# new is broken. Any other difference is shown: a new error of that kind (new code using Location, Context, a
# DAO…) is expected — check it is one, then take the new list as the baseline with --update and commit it with
# the change; anything else is a real error.
#   bash tools/test-harness/run_compile_check.sh [--update]
# Takes 3 GB of memory and a few minutes; not to be run alongside another kotlinc.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
BASE="$HERE/compile/baseline.err"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
cd "$ROOT"
find app/src/main/java -name '*.kt' | sort > "$OUT/files"
JAVA_OPTS="${JAVA_OPTS:--Xmx3g}" "$KOTLINC" @"$OUT/files" -cp "$KLIB/kotlinx-coroutines-core-jvm.jar" \
    -d "$OUT/classes" > "$OUT/raw" 2>&1 || true
if grep -q OutOfMemory "$OUT/raw"; then echo "kotlinc ran out of memory — run it alone, with JAVA_OPTS=-Xmx3g"; exit 2; fi
grep -E 'error:' "$OUT/raw" | sed -E 's/:[0-9]+:[0-9]+: /: /' | sort > "$OUT/now.err" || true
if [ "${1:-}" = "--update" ]; then
    cp "$OUT/now.err" "$BASE"; echo "Baseline updated: $(wc -l < "$BASE") errors"; exit 0
fi
if diff "$BASE" "$OUT/now.err" > "$OUT/diff"; then
    echo "SAME ERRORS AS THE BASELINE ($(wc -l < "$BASE"), all of the missing Android SDK)"
else
    echo "DIFFERENT FROM THE BASELINE (< baseline only, > now only):"; cat "$OUT/diff"; exit 1
fi
