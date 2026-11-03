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
// @@LEAVING@@
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

/** Leaving a stop (JourneyService.leavingOnFoot) at walking speed 5 km/h or [peak], accurate unless said. */
fun onFoot(toNext: Double?, stopToNext: Double?, peak: Double = 5.0, gone: Boolean = false, accurate: Boolean = true) =
    J.leavingOnFoot(accurate, peak, VEHICLE_KMH, toNext, stopToNext, gone)

fun main() {
    // ── Leaving a stop on foot (3 Nov 2026) ──
    // The 16 departures of 2 Nov 2026 (22, 18, 76), at 90 m from the stop:
    // to the next stop, the stop to it, the top speed since leaving.
    for ((n, d) in listOf("ул. Опълченска" to Triple(363.0, 459.0, 23.0), "бул. Христо Ботев" to Triple(141.0, 217.0, 22.0),
            "Женски пазар" to Triple(371.0, 459.0, 28.0), "Централни хали (18)" to Triple(296.0, 389.0, 26.0),
            "пл. Св. Неделя" to Triple(308.0, 394.0, 22.0), "пл. Гарибалди" to Triple(208.0, 300.0, 21.0),
            "пл. Славейков" to Triple(392.0, 485.0, 24.0), "бул. Васил Левски" to Triple(236.0, 328.0, 21.0),
            "пл. Орлов мост" to Triple(477.0, 569.0, 36.0), "БТА" to Triple(1637.0, 1727.0, 35.0),
            "Хотел Плиска" to Triple(1880.0, 1972.0, 39.0), "УМБАЛ Св. Анна" to Triple(837.0, 922.0, 35.0),
            "ХМС" to Triple(347.0, 440.0, 31.0), "бл. 43 (stayed on)" to Triple(590.0, 680.0, 25.0)))
        eq("2.11 by vehicle from $n: a departure", false, onFoot(d.first, d.second, d.third))
    eq("2.11 c on foot from Метростанция Ал. Малинов, off the road, 76 gone: on foot", true, onFoot(279.0, 215.0, gone = true))
    eq("2.11 c the same, by the next stop alone: on foot", true, onFoot(279.0, 215.0))
    eq("2.11 on foot back from Централни хали (22): on foot", true, onFoot(343.0, 265.0))
    eq("a crawl in a jam towards the next stop, 6 km/h: a departure, as before", false, onFoot(220.0, 300.0, peak = 6.0))
    eq("walking on towards the next stop, ours still with us: a departure", false, onFoot(220.0, 300.0))
    eq("walking on towards the next stop, ours gone: on foot", true, onFoot(220.0, 300.0, gone = true))
    eq("vehicle speed since leaving, ours gone (its report wrong): a departure", false, onFoot(220.0, 300.0, peak = 15.0, gone = true))
    eq("an inaccurate fix: a departure, as before", false, onFoot(343.0, 265.0, accurate = false))
    eq("no next stop: on foot", true, onFoot(null, null))
    eq("level with the stop (as far from the next as it): on foot", true, onFoot(300.0, 300.0))

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
