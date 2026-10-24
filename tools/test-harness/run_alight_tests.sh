#!/usr/bin/env bash
# The getting-off rule, the parting's "kept moving" and the no-progress limit of JourneyService (see
# alight/AlightTests.kt). The real functions are copied out of the source, since JourneyService itself cannot be
# compiled without the Android SDK.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SRC="$ROOT/app/src/main/java/bg/sofia/transit"
KOTLINC="${KOTLINC:-kotlinc}"
KLIB="${KOTLIN_LIB:-$(dirname "$(dirname "$(readlink -f "$(command -v "$KOTLINC")")")")/lib}"
OUT="$(mktemp -d)"; trap 'rm -rf "$OUT"' EXIT
python3 - "$SRC" "$HERE/alight/AlightTests.kt" "$OUT/AlightTests.kt" <<'PY'
import sys
src, tmpl, out = sys.argv[1:]
js = open(f"{src}/service/JourneyService.kt").read()
def grab(sig):
    i = js.index("        internal fun " + sig)
    return js[i:js.index("\n\n", i)]
open(out, "w").write(open(tmpl).read()
    .replace("// @@GOTOFF@@", grab("gotOff("))
    .replace("// @@MOVEDSINCE@@", grab("movedSince("))
    .replace("// @@ATSPEED@@", grab("atVehicleSpeed(")))
PY
"$KOTLINC" "$OUT/AlightTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" AlightTestsKt | grep -v "^PASS"
