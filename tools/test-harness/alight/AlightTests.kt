// The getting-off rule of JourneyService (gotOff, see ALIGHT_WAIT_MS), whether
// we kept moving while a parting was confirmed (movedSince), and what counts
// as vehicle speed — the one measure of riding for both, and progress for the
// 10-minute no-progress limit (atVehicleSpeed, see INACTIVITY_TIMEOUT_MS).
// run_alight_tests.sh copies the REAL functions into the marked places below;
// the rest is the test, with the rule's constants as JourneyService has them.
object J {
// @@GOTOFF@@
// @@MOVEDSINCE@@
// @@ATSPEED@@
}

const val WAIT = 120_000L        // JourneyService.ALIGHT_WAIT_MS
const val VEHICLE_KMH = 10.0     // JourneyService.MIN_SPEED_FOR_IDENTIFY
const val NO_PROGRESS = 10 * 60 * 1000L  // JourneyService.INACTIVITY_TIMEOUT_MS
const val LEAD = 5_000L          // JourneyService.PARTING_SPEED_LEAD_MS

/**
 * The no-progress limit replayed fix by fix (one a second) from departure, as
 * onFix and checkJourneyTimers apply it: progress is a new stop reached
 * ([stopsAt]) or a fix at vehicle speed. [fix] gives, at each second, whether
 * the fix is accurate and the short-average speed. Returns when the limit
 * ends tracking (checked every 5 s), or null.
 */
fun noProgressEnd(stopsAt: Set<Long>, fix: (Long) -> Pair<Boolean, Double>, until: Long): Long? {
    var last = 0L
    var t = 0L
    while (t <= until) {
        val (accurate, kmh) = fix(t)
        if (t in stopsAt || J.atVehicleSpeed(accurate, kmh, VEHICLE_KMH)) last = t
        if (t % 5_000L == 0L && t - last > NO_PROGRESS) return t
        t += 1_000L
    }
    return null
}
fun min(m: Int) = m * 60_000L

var fails = 0
fun eq(n: String, e: Any?, a: Any?) { if (e != a) { fails++; println("FAIL $n: expected $e got $a") } else println("PASS $n") }
fun off(now: Long, withUs: Long, far: Boolean, speedAt: Long) = J.gotOff(now, withUs, far, speedAt, WAIT)
fun s(h: Int, m: Int, sec: Int) = ((h * 60 + m) * 60 + sec) * 1000L

/**
 * A ride replayed second by second, as onFix, checkParted and the timer see
 * it: a fix every second with [speedAt] its short-average speed (accurate
 * fixes), the vehicle's distance at each of [readings], and the rule looked
 * at every 5 s. Returns when it fires, or null.
 */
fun replay(readings: List<Pair<Long, Double>>, speedAt: (Long) -> Double, until: Long,
           from: Long = readings.first().first): Long? {
    var withUs = 0L; var far = false; var vehicleSpeedAt = 0L
    var r = 0
    var now = from
    while (now <= until) {
        if (J.atVehicleSpeed(true, speedAt(now), VEHICLE_KMH)) vehicleSpeedAt = now
        while (r < readings.size && readings[r].first <= now) {
            val d = readings[r].second
            if (d <= 30.0) withUs = readings[r].first
            if (d <= 250.0) far = false else far = true
            r++
        }
        if (now % 5_000L == 0L && off(now, withUs, far, vehicleSpeedAt)) return now
        now += 1_000L
    }
    return null
}

fun main() {
    eq("never with us → never", false, off(10 * WAIT, 0L, true, 0L))
    eq("far, 2 min since with us and since vehicle speed → got off", true, off(WAIT + 1, 1, true, 1))
    eq("not yet 2 min since with us", false, off(WAIT, 1, true, 0L))
    eq("vehicle not far (within 250 m) → no", false, off(10 * WAIT, 1, false, 0L))
    eq("vehicle speed within the last 2 min → no (we are in another vehicle)", false,
        off(10 * WAIT, 1, true, 10 * WAIT - 60_000L))
    eq("vehicle speed exactly 2 min ago → got off", true, off(10 * WAIT, 1, true, 9 * WAIT))
    eq("no vehicle speed at all → got off", true, off(10 * WAIT, 1, true, 0L))

    // ── movedSince: did we keep moving while a parting was confirmed? ──
    eq("movedSince: vehicle speed after the first reading", true, J.movedSince(s(9,0,20), s(9,0,0), LEAD))
    eq("movedSince: at the first reading (the short average then)", true, J.movedSince(s(9,0,0) - 3_000L, s(9,0,0), LEAD))
    eq("movedSince: only well before it", false, J.movedSince(s(9,0,0) - 10_000L, s(9,0,0), LEAD))
    eq("movedSince: never at vehicle speed", false, J.movedSince(0L, s(9,0,0), LEAD))
    // 6 Oct 2026, 09:04:28: walking through underpasses, fixes 56-300 m out
    // read as 14 km/h and the vehicle was withdrawn. Inaccurate fixes are no
    // vehicle speed, so nothing moves the last moment at vehicle speed.
    eq("noisy walk: inaccurate 14 km/h is no vehicle speed", false, J.atVehicleSpeed(false, 14.0, VEHICLE_KMH))

    // 5 Oct 2026, ПЛ. ОРЛОВ МОСТ: "Слизате тук" 09:06:54; bus with us 09:07:03
    // (0 m) and 09:08:03 (21 m); 558 m at 09:09:04; we stood or walked
    // (≤ 5 km/h). The old rules ended at 09:10:35.
    val orlov = listOf(s(9,7,3) to 0.0, s(9,8,3) to 21.0, s(9,9,4) to 558.0, s(9,9,34) to 900.0, s(9,10,4) to 1200.0)
    val end = replay(orlov, { 5.0 }, s(9, 20, 0))
    eq("Орлов мост 5.10: ends 2 min after the bus was last with us (09:10:03 → first tick)", true,
        end != null && end >= s(9,10,3) && end < s(9,10,10))

    // 6 Oct 2026, ПЛ. ОРЛОВ МОСТ, with 304 wrongly followed: last beside us
    // at 08:56:51 while we rode on at 30-38 km/h; we slowed from 08:57:41
    // and stood from 08:58:11. Its distances after 08:56:51 that the log does
    // not give (only "beyond 30 m", no parting) are put at 100-200 m. Far
    // from 09:00:55. The rule of 5 Oct never ended it (the speed ridden after
    // 08:56:51 stood in the way); now it ends at that first far reading.
    val ride304 = listOf(s(8,56,51) to 0.0, s(8,57,51) to 100.0, s(8,58,51) to 150.0,
        s(8,59,54) to 200.0, s(9,0,55) to 450.0, s(9,1,25) to 406.0, s(9,2,26) to 683.0,
        s(9,2,57) to 697.0, s(9,3,57) to 852.0)
    val speed6Oct = { t: Long -> when {
        t < s(8,57,41) -> 34.0
        t < s(8,58,11) -> 8.0
        else -> 3.0 } }
    val end304 = replay(ride304, speed6Oct, s(9, 10, 0), from = s(8,56,0))
    eq("Орлов мост 6.10, wrong vehicle: ends at the first far reading (09:00:55 → first tick)", true,
        end304 != null && end304 >= s(9,0,55) && end304 < s(9,1,0))

    // The same with 213, the bus actually ridden: beside us at 08:57:51 and
    // 08:58:51 (7 m), gone from 08:59:54 (distances assumed as above).
    val ride213 = listOf(s(8,56,51) to 0.0, s(8,57,51) to 7.0, s(8,58,51) to 7.0,
        s(8,59,54) to 150.0, s(9,0,55) to 400.0, s(9,1,55) to 700.0)
    val end213 = replay(ride213, speed6Oct, s(9, 10, 0), from = s(8,56,0))
    eq("Орлов мост 6.10, right vehicle: ends at the first far reading 2 min after it was with us", true,
        end213 != null && end213 >= s(9,0,55) && end213 < s(9,1,0))

    // The last reading with the bus beside us is taken while still riding:
    // 40 km/h until the stop 20 s later, then off and walking; the bus far
    // from 80 s. Ends 2 min after our last vehicle speed.
    val lastWhileRiding = listOf(s(15,0,0) to 3.0, s(15,0,30) to 90.0, s(15,1,0) to 160.0,
        s(15,1,20) to 300.0, s(15,1,50) to 600.0, s(15,2,20) to 900.0, s(15,2,50) to 1200.0)
    val endRiding = replay(lastWhileRiding, { t -> if (t < s(15,0,20)) 40.0 else 4.0 }, s(15, 10, 0),
        from = s(14,59,0))
    eq("last with-us reading while riding: ends 2 min after the last vehicle speed", true,
        endRiding != null && endRiding >= s(15,2,19) && endRiding < s(15,2,25))

    // Stayed aboard at a terminus: the bus stands with us for 10 min.
    val terminus = (0..20).map { s(10,0,0) + it * 30_000L to 5.0 }
    eq("aboard a standing bus: never", null, replay(terminus, { 0.0 }, s(10, 12, 0)))

    // Stayed aboard past the chosen stop: carried off at vehicle speed.
    val carried = listOf(s(11,0,0) to 0.0, s(11,1,0) to 5.0, s(11,2,0) to 0.0, s(11,3,0) to 3.0)
    eq("aboard, moving on: never", null, replay(carried, { if (it < s(11,0,30)) 0.0 else 40.0 }, s(11, 4, 0)))

    // Wrongly identified bus leaves while our own bus waits at a red light
    // for 70 s, having ridden before it; then we move off at vehicle speed:
    // never ends.
    val wrong = listOf(s(12,0,0) to 0.0, s(12,0,30) to 400.0, s(12,1,0) to 700.0, s(12,1,30) to 1000.0, s(12,2,30) to 1500.0)
    eq("in another vehicle, red light 70 s: never", null,
        replay(wrong, { if (it < s(12,0,0) || it >= s(12,1,10)) 30.0 else 0.0 }, s(12, 5, 0),
            from = s(11,59,0)))

    // One stale/outlying far report while aboard and standing, then close again.
    val blip = listOf(s(13,0,0) to 0.0, s(13,1,0) to 600.0, s(13,2,0) to 4.0, s(13,3,0) to 2.0, s(13,4,0) to 3.0)
    eq("aboard, one outlying report: never", null, replay(blip, { 0.0 }, s(13, 6, 0)))

    // The bus stops reporting after we got off: the last report was close.
    val silent = listOf(s(14,0,0) to 0.0, s(14,1,0) to 10.0)
    eq("bus silent after we got off: left to the backstop timers", null, replay(silent, { 4.0 }, s(14, 10, 0)))

    // ── The 10-minute no-progress limit ──
    eq("atVehicleSpeed: accurate 12 km/h", true, J.atVehicleSpeed(true, 12.0, VEHICLE_KMH))
    eq("atVehicleSpeed: accurate 10 km/h (the threshold)", true, J.atVehicleSpeed(true, 10.0, VEHICLE_KMH))
    eq("atVehicleSpeed: walking 5 km/h", false, J.atVehicleSpeed(true, 5.0, VEHICLE_KMH))
    eq("atVehicleSpeed: inaccurate fix at 40 km/h", false, J.atVehicleSpeed(false, 40.0, VEHICLE_KMH))
    eq("atVehicleSpeed: no speed yet", false, J.atVehicleSpeed(true, null, VEHICLE_KMH))

    // A jam: 25 min between two stops, standing, with a crawl at 15 km/h for
    // 20 s every 4 min. Before, it ended 10 min after the last stop.
    val jam = noProgressEnd(setOf(0L, min(25)),
        { t -> true to (if (t % min(4) in min(4) - 20_000L until min(4)) 15.0 else 0.0) }, min(30))
    eq("jam with a crawl every 4 min: continues", null, jam)

    // Got off unnoticed and walk: last moment at vehicle speed at 3 min,
    // walking at 5 km/h after. Ends 10 min after that.
    val walk = noProgressEnd(setOf(0L), { t -> true to (if (t <= min(3)) 30.0 else 5.0) }, min(20))
    eq("walking after getting off: ends 10 min after the last vehicle speed", true,
        walk != null && walk > min(13) && walk <= min(13) + 5_000L)

    // Indoors: the phone, placed coarsely, seems to move at 40 km/h. Not progress.
    val indoors = noProgressEnd(setOf(0L), { t -> (t <= min(1)) to 40.0 }, min(20))
    eq("indoors, inaccurate fixes at 'speed': ends", true,
        indoors != null && indoors <= min(11) + 5_000L)

    // A stop now and then and speed between them: never.
    val ride = noProgressEnd((0..30).map { min(it) }.toSet(), { true to 30.0 }, min(30))
    eq("an ordinary ride: never", null, ride)

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
