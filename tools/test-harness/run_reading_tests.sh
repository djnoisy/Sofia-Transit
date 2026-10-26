#!/usr/bin/env bash
# When the vehicles around us are read, and from what (see readings/ReadingTests.kt). The real functions are copied
# out of JourneyService, which itself cannot be compiled without the Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
python3 - "$SRC" "$HERE/readings/ReadingTests.kt" "$OUT/ReadingTests.kt" <<'PY'
import sys
src, tmpl, out = sys.argv[1:]
js = open(f"{src}/service/JourneyService.kt").read()
def grab(start):
    i = js.index(start)
    return js[i:js.index("\n\n", i)]
open(out, "w").write(open(tmpl).read().replace("// @@READINGS@@", "\n".join([
    grab("        internal enum class ReadingFrom"),
    grab("        internal fun readingFrom("),
    grab("        internal fun readingAccepted("),
    grab("        internal fun nextLookMs(")])))
PY
"$KOTLINC" "$OUT/ReadingTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" ReadingTestsKt | grep -v "^PASS"
