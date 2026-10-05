// The getting-off rule of JourneyService (gotOff, see ALIGHT_WAIT_MS).
// run_alight_tests.sh copies the REAL gotOff into the marked place below;
// the rest is the test, with the rule's constants as JourneyService has them.
object J {
// @@GOTOFF@@
}

const val WAIT = 120_000L        // JourneyService.ALIGHT_WAIT_MS
const val VEHICLE_KMH = 10.0     // JourneyService.MIN_SPEED_FOR_IDENTIFY

var fails = 0
fun eq(n: String, e: Any?, a: Any?) { if (e != a) { fails++; println("FAIL $n: expected $e got $a") } else println("PASS $n") }
fun off(now: Long, withUs: Long, far: Boolean, peak: Double) = J.gotOff(now, withUs, far, peak, WAIT, VEHICLE_KMH)
fun s(h: Int, m: Int, sec: Int) = ((h * 60 + m) * 60 + sec) * 1000L

/**
 * A ride replayed reading by reading: the vehicle's distance at each reading
 * and our short-average speed in between, as checkParted and onFix see them.
 * Returns when the rule fires (checked every 5 s, as the timer does), or null.
 */
fun replay(readings: List<Pair<Long, Double>>, speedAt: (Long) -> Double, until: Long): Long? {
    var withUs = 0L; var far = false; var peak = 0.0
    var r = 0
    var now = readings.first().first
    while (now <= until) {
        while (r < readings.size && readings[r].first <= now) {
            val d = readings[r].second
            if (d <= 30.0) { withUs = readings[r].first; peak = 0.0 }
            far = d > 250.0
            r++
        }
        if (withUs != 0L) peak = maxOf(peak, speedAt(now))
        if (off(now, withUs, far, peak)) return now
        now += 5_000L
    }
    return null
}

fun main() {
    eq("never with us → never", false, off(10 * WAIT, 0L, true, 0.0))
    eq("far, 2 min since with us, standing → got off", true, off(WAIT + 1, 1, true, 0.0))
    eq("not yet 2 min", false, off(WAIT, 1, true, 0.0))
    eq("vehicle not far (within 250 m) → no", false, off(10 * WAIT, 1, false, 0.0))
    eq("vehicle speed since it was with us → no (we are in another vehicle)", false, off(10 * WAIT, 1, true, 12.0))
    eq("walking pace is fine", true, off(10 * WAIT, 1, true, 6.0))

    // 5 Oct 2026, ПЛ. ОРЛОВ МОСТ: "Слизате тук" 09:06:54; bus with us 09:07:03
    // (0 m) and 09:08:03 (21 m); 558 m at 09:09:04; we stood or walked
    // (≤ 5 km/h). The old rules ended at 09:10:35.
    val orlov = listOf(s(9,7,3) to 0.0, s(9,8,3) to 21.0, s(9,9,4) to 558.0, s(9,9,34) to 900.0, s(9,10,4) to 1200.0)
    val end = replay(orlov, { 5.0 }, s(9, 20, 0))
    eq("Орлов мост: ends 2 min after the bus was last with us (09:10:03 → first tick)", true,
        end != null && end >= s(9,10,3) && end < s(9,10,10))

    // Stayed aboard at a terminus: the bus stands with us for 10 min.
    val terminus = (0..20).map { s(10,0,0) + it * 30_000L to 5.0 }
    eq("aboard a standing bus: never", null, replay(terminus, { 0.0 }, s(10, 12, 0)))

    // Stayed aboard past the chosen stop: carried off at vehicle speed.
    val carried = listOf(s(11,0,0) to 0.0, s(11,1,0) to 5.0, s(11,2,0) to 0.0, s(11,3,0) to 3.0)
    eq("aboard, moving on: never", null, replay(carried, { if (it < s(11,0,30)) 0.0 else 40.0 }, s(11, 4, 0)))

    // Wrongly identified bus leaves while our own bus waits at a red light
    // for 60 s, then we move off at vehicle speed: never ends.
    val wrong = listOf(s(12,0,0) to 0.0, s(12,0,30) to 400.0, s(12,1,0) to 700.0, s(12,1,30) to 1000.0, s(12,2,30) to 1500.0)
    eq("in another vehicle, red light 70 s: never", null,
        replay(wrong, { if (it < s(12,1,10)) 0.0 else 30.0 }, s(12, 5, 0)))

    // One stale/outlying far report while aboard and standing, then close again.
    val blip = listOf(s(13,0,0) to 0.0, s(13,1,0) to 600.0, s(13,2,0) to 4.0, s(13,3,0) to 2.0, s(13,4,0) to 3.0)
    eq("aboard, one outlying report: never", null, replay(blip, { 0.0 }, s(13, 6, 0)))

    // The bus stops reporting after we got off: the last report was close.
    val silent = listOf(s(14,0,0) to 0.0, s(14,1,0) to 10.0)
    eq("bus silent after we got off: left to the backstop timers", null, replay(silent, { 4.0 }, s(14, 10, 0)))

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
