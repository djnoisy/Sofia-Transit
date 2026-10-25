// The 10-minute no-progress limit of JourneyService (see INACTIVITY_TIMEOUT_MS):
// what counts as vehicle speed — progress (atVehicleSpeed) — and where the
// limit applies (noProgressLimitApplies; off the route with a vehicle
// identified too, since 25 Oct 2026). run_alight_tests.sh copies the REAL
// functions into the marked places below; the rest is the test, with the
// limit's constants as JourneyService has them. (Getting off and a parting
// are the model's since stage 3: RideModel, run_model_tests.sh.)
object J {
// @@ATSPEED@@
// @@NOPROGRESS@@
}

const val VEHICLE_KMH = 10.0     // JourneyService.MIN_SPEED_FOR_IDENTIFY
const val NO_PROGRESS = 10 * 60 * 1000L  // JourneyService.INACTIVITY_TIMEOUT_MS

/**
 * The no-progress limit replayed fix by fix (one a second) from departure, as
 * onFix and checkJourneyTimers apply it: progress is a new stop reached
 * ([stopsAt]) or a fix at vehicle speed. [fix] gives, at each second, whether
 * the fix is accurate and the short-average speed; [applies], whether the
 * limit applies then (noProgressLimitApplies). Returns when the limit ends
 * tracking (checked every 5 s), or null. (The vehicle we follow beside us
 * holds it off too — that is the model's, RideModel.ourBesideWithin.)
 */
fun noProgressEnd(stopsAt: Set<Long>, fix: (Long) -> Pair<Boolean, Double>, until: Long,
                  applies: (Long) -> Boolean = { true }): Long? {
    var last = 0L
    var t = 0L
    while (t <= until) {
        val (accurate, kmh) = fix(t)
        if (t in stopsAt || J.atVehicleSpeed(accurate, kmh, VEHICLE_KMH)) last = t
        if (t % 5_000L == 0L && applies(t) && t - last > NO_PROGRESS) return t
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

    // ── Where the limit applies (noProgressLimitApplies) ──
    eq("applies: on the route, placed on it", true, J.noProgressLimitApplies(false, false, false))
    eq("applies: on the route, placed, a vehicle identified", true, J.noProgressLimitApplies(false, false, true))
    eq("applies: on the route, still finding our place — no", false, J.noProgressLimitApplies(false, true, true))
    eq("applies: off the chosen route, nothing identified — no (its own limit)", false,
        J.noProgressLimitApplies(true, true, false))
    eq("applies: off the route of the vehicle we follow — yes", true, J.noProgressLimitApplies(true, true, true))

    // Got off during a detour (off the route from 2 min, a vehicle identified),
    // at 4 min, walking at 5 km/h after; ours silent, so the model sees no
    // parting. Before 25 Oct 2026 nothing ended it; now 10 min after the last
    // vehicle speed.
    val detour = noProgressEnd(setOf(0L), { t -> true to (if (t <= min(4)) 30.0 else 5.0) }, min(30),
        applies = { t -> J.noProgressLimitApplies(t >= min(2), t >= min(2), true) })
    eq("got off during a detour, ours silent: ends 10 min after the last vehicle speed", true,
        detour != null && detour > min(14) && detour <= min(14) + 5_000L)
    // The same with nothing identified: not this limit's (the limit for an
    // unestablished line ends it).
    eq("off the chosen route, nothing identified: not this limit", null,
        noProgressEnd(setOf(0L), { t -> true to (if (t <= min(4)) 30.0 else 5.0) }, min(30),
            applies = { t -> J.noProgressLimitApplies(t >= min(2), t >= min(2), false) }))
    // A detour in a jam: off the route, a crawl now and then — continues.
    eq("a detour in a jam with a crawl every 4 min: continues", null,
        noProgressEnd(setOf(0L), { t -> true to (if (t % min(4) in min(4) - 20_000L until min(4)) 15.0 else 0.0) },
            min(30), applies = { J.noProgressLimitApplies(true, true, true) }))

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
