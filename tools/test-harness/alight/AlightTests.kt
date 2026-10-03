// Getting-off logic in JourneyService: the moved-together tracking
// (updateBeside / BesideTrack) and the end rule of checkJourneyTimers.
// run_alight_tests.sh copies the REAL updateBeside, BesideTrack and
// LocationHelper.distanceMetres into the marked places below; the rest is
// the test. The end rule `concluded` is a hand copy of the condition in
// checkJourneyTimers — keep it in step if that condition changes.
import kotlin.math.*

object VehicleMatcher {
    data class Sighting(val tripId: String, val routeId: String, val distanceMetres: Double,
                        val timestamp: Long, val ageSec: Long, val lat: Double = 0.0, val lon: Double = 0.0)
}
object LocationHelper {
    private const val EARTH_RADIUS_M = 6_371_000.0
// @@DISTANCE@@
}
object J {
// @@BESIDE@@
}

var fails = 0
fun eq(n: String, e: Any?, a: Any?) { if (e != a) { fails++; println("FAIL $n: expected $e got $a") } else println("PASS $n") }
fun lat(m: Double) = 42.65 + m / 111_320.0                 // metres north of a base point
fun sg(id: String, ts: Long, m: Double, age: Long = 5) = VehicleMatcher.Sighting(id, "R", 5.0, ts, age, lat(m), 23.37)
// Vehicle reports at the given metres, we at `um` metres, reading at `now`.
internal fun up(b: Map<String, J.BesideTrack>, l: List<VehicleMatcher.Sighting>, um: Double, now: Long, acc: Boolean = true) =
    J.updateBeside(b, l, "OURS", 30, lat(um), 23.37, acc, 40.0, now, LocationHelper::distanceMetres)
// Hand copy of the end condition in checkJourneyTimers (ALIGHT_SETTLE_MS = 90 s).
fun concluded(now: Long, pending: Long, hold: Long) = pending != 0L && now - pending >= 90_000 && now - hold >= 90_000

fun main() {
    val t = up(emptyMap(), listOf(sg("OURS", 1, 0.0), sg("B", 1, 0.0), sg("OLD", 1, 0.0, 60),
        sg("NOPOS", 1, 0.0).copy(lat = 0.0, lon = 0.0)), 0.0, 1000)
    eq("only fresh other vehicles with a position", setOf("B"), t.keys)

    // Terminus: both stand, the bus's position jitters.
    var b = up(emptyMap(), listOf(sg("B", 1, 0.0)), 0.0, 0)
    for (k in 1..10) b = up(b, listOf(sg("B", 1L + k, if (k % 2 == 0) 10.0 else -8.0)), if (k % 2 == 0) 5.0 else -5.0, k * 30_000L)
    eq("terminus: never moved", 0L, b["B"]!!.movedAtMs)

    // Bus queue creeps past a standing passenger.
    b = up(emptyMap(), listOf(sg("B", 1, -25.0)), 0.0, 0)
    b = up(b, listOf(sg("B", 2, 20.0)), 3.0, 30_000)
    eq("bus creeping past a standing passenger: no", 0L, b["B"]!!.movedAtMs)

    // Jam crawl with stops: both move 0, 15, 15, 30, 45 m.
    b = up(emptyMap(), listOf(sg("B", 1, 0.0)), 2.0, 0)
    b = up(b, listOf(sg("B", 2, 15.0)), 17.0, 30_000); eq("15 m: not yet", 0L, b["B"]!!.movedAtMs)
    b = up(b, listOf(sg("B", 3, 15.0)), 16.0, 60_000); eq("standstill: not yet", 0L, b["B"]!!.movedAtMs)
    b = up(b, listOf(sg("B", 4, 30.0)), 31.0, 90_000); eq("30 m: not yet", 0L, b["B"]!!.movedAtMs)
    b = up(b, listOf(sg("B", 5, 45.0)), 47.0, 120_000); eq("45 m together: moved", 120_000L, b["B"]!!.movedAtMs)
    b = up(b, listOf(sg("B", 6, 60.0)), 62.0, 150_000); eq("re-anchored: +15 m is no new move", 120_000L, b["B"]!!.movedAtMs)
    b = up(b, listOf(sg("B", 7, 90.0)), 92.0, 180_000); eq("+45 m together: moved again", 180_000L, b["B"]!!.movedAtMs)

    // Our own fix inaccurate: no move counted, anchors kept.
    var c = up(emptyMap(), listOf(sg("B", 1, 0.0)), 0.0, 0)
    c = up(c, listOf(sg("B", 2, 50.0)), 50.0, 30_000, acc = false); eq("inaccurate own fix: no", 0L, c["B"]!!.movedAtMs)
    c = up(c, listOf(sg("B", 3, 55.0)), 55.0, 60_000, acc = true); eq("then accurate: counted from the first anchor", 60_000L, c["B"]!!.movedAtMs)

    // First sighting on an inaccurate fix: our anchor is taken at the next accurate one.
    var e = up(emptyMap(), listOf(sg("B", 1, 0.0)), 80.0, 0, acc = false)
    e = up(e, listOf(sg("B", 2, 45.0)), 5.0, 30_000, acc = true); eq("bad first anchor gives no false move", 0L, e["B"]!!.movedAtMs)
    e = up(e, listOf(sg("B", 3, 95.0)), 50.0, 60_000, acc = true); eq("then a real joint move counts", 60_000L, e["B"]!!.movedAtMs)

    // Chain broken; same report twice.
    b = up(b, emptyList(), 92.0, 210_000); eq("not beside: dropped", emptyMap<String, J.BesideTrack>(), b)
    b = up(emptyMap(), listOf(sg("B", 1, 0.0)), 0.0, 0); b = up(b, listOf(sg("B", 1, 0.0)), 45.0, 30_000)
    eq("same bus report while we moved: no", 0L, b["B"]!!.movedAtMs)

    // End rule (times from the first parting reading).
    eq("normal: ends at 90 s", true, concluded(90_001, 1, 0))
    eq("normal: not before", false, concluded(89_000, 1, 0))
    eq("moved with us at 60 s: not at 120 s", false, concluded(120_000, 1, 60_000))
    eq("moved with us at 60 s: ends at 150 s", true, concluded(150_001, 1, 60_000))

    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
