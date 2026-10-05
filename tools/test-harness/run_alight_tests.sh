#!/usr/bin/env bash
# The getting-off rule of JourneyService (see alight/AlightTests.kt). The real
# function is copied out of the source, since JourneyService itself cannot be
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
i = js.index("        internal fun gotOff(")
fn = js[i:js.index("\n\n", i)]
open(out, "w").write(open(tmpl).read().replace("// @@GOTOFF@@", fn))
PY
"$KOTLINC" "$OUT/AlightTests.kt" -d "$OUT/c" 2>&1 | grep -A3 "error:" || true
java -cp "$OUT/c:$KLIB/kotlin-stdlib.jar" AlightTestsKt | grep -v "^PASS"
