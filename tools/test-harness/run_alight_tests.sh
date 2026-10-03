#!/usr/bin/env bash
# Getting-off logic of JourneyService (see alight/AlightTests.kt). The real
# functions are copied out of the source, since JourneyService itself cannot
# be compiled without the Android SDK.
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
lh = open(f"{src}/util/LocationHelper.kt").read()
def block(text, start, end="\n\n"):
    i = text.index(start); return text[i:text.index(end, i)]
beside = block(js, "        internal data class BesideTrack(") + "\n" + block(js, "        internal fun updateBeside(")
i = lh.index("    fun distanceMetres"); dist = lh[i:lh.index("\n    }\n", i) + 7]
t = open(tmpl).read().replace("// @@BESIDE@@", beside).replace("// @@DISTANCE@@", dist)
open(out, "w").write(t)
PY
"$KOTLINC" "$OUT/AlightTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" AlightTestsKt | grep -v "^PASS"
