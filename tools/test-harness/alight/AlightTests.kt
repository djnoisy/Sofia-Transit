// What counts as vehicle speed in JourneyService — progress for the
// 10-minute no-progress limit (atVehicleSpeed, see INACTIVITY_TIMEOUT_MS).
// run_alight_tests.sh copies the REAL function into the marked place below;
// the rest is the test, with the limit's constants as JourneyService has them.
// (Getting off and a parting are the model's since stage 3: RideModel,
// run_model_tests.sh.)
object J {
// @@ATSPEED@@
}

const val VEHICLE_KMH = 10.0     // JourneyService.MIN_SPEED_FOR_IDENTIFY
const val NO_PROGRESS = 10 * 60 * 1000L  // JourneyService.INACTIVITY_TIMEOUT_MS

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

fun main() {
    // 6 Oct 2026, 09:04:28: walking through underpasses, fixes 56-300 m out
    // read as 14 km/h. Inaccurate fixes are no vehicle speed.
    eq("noisy walk: inaccurate 14 km/h is no vehicle speed", false, J.atVehicleSpeed(false, 14.0, VEHICLE_KMH))

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
