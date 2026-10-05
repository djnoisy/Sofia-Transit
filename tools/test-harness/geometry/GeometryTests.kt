// Road geometry (service/RouteGeometry.kt) on the real shape of bus 213 —
// the ХМС → ДЪРЖАВНА ПЕЧАТНИЦА corner where "Спирка, ДЪРЖАВНА ПЕЧАТНИЦА" was
// said two minutes early (log of 5 Oct 2026) — plus synthetic edge cases.
import bg.sofia.transit.service.RouteGeometry
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

var ok = 0; val bad = mutableListOf<String>()
fun t(name: String, body: () -> Unit) {
    try { body(); ok++; println("PASS  $name") }
    catch (e: Throwable) { bad += "$name: ${e.message}"; println("FAIL  $name\n      ${e.message}") }
}
fun yes(w: String, c: Boolean) { if (!c) throw AssertionError(w) }
fun near(w: String, expected: Double, actual: Double?, tol: Double) {
    if (actual == null || kotlin.math.abs(actual - expected) > tol)
        throw AssertionError("$w: expected $expected ± $tol but was $actual")
}

const val ARRIVAL_RADIUS = 45.0          // JourneyService.ARRIVAL_RADIUS
const val PASSED_STOP_MARGIN = 30.0      // JourneyService.PASSED_STOP_MARGIN

fun straight(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
}

/** Moves a point by metres east / north. */
fun shift(lat: Double, lon: Double, east: Double, north: Double) =
    Pair(lat + north / 110_540.0, lon + east / (111_320.0 * cos(Math.toRadians(lat))))

/** Points along the shape every [step] metres: (along, lat, lon). */
fun walk(shape: FloatArray, step: Double): List<Triple<Double, Double, Double>> {
    val out = mutableListOf<Triple<Double, Double, Double>>()
    var along = 0.0; var carry = 0.0
    for (i in 0 until shape.size / 2 - 1) {
        val aLat = shape[2 * i].toDouble(); val aLon = shape[2 * i + 1].toDouble()
        val bLat = shape[2 * i + 2].toDouble(); val bLon = shape[2 * i + 3].toDouble()
        val seg = straight(aLat, aLon, bLat, bLon)
        var s = carry
        while (s < seg) {
            val f = s / seg
            out += Triple(along + s, aLat + f * (bLat - aLat), aLon + f * (bLon - aLon))
            s += step
        }
        carry = s - seg; along += seg
    }
    return out
}

/** Deterministic GPS scatter of up to [m] metres. */
class Scatter(var seed: Long = 42) {
    fun next(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 2001) / 1000.0 - 1.0 }
    fun around(lat: Double, lon: Double, m: Double) = shift(lat, lon, next() * m, next() * m)
}

/**
 * Where the road after ХМС runs closest to ДЪРЖАВНА ПЕЧАТНИЦА while the stop
 * is still 200+ m away by road — the spot of the early "Спирка" (35–42 m).
 */
fun nearPass(g: RouteGeometry, from: Int, to: Int, stop: Pair<Double, Double>) =
    walk(Data213.shape, 1.0)
        .filter { it.first > g.stopAlong[from] && g.stopAlong[to] - it.first > 200 }
        .minByOrNull { straight(it.second, it.third, stop.first, stop.second) }!!

fun main() {
    val stops = Data213.stops.map { Pair(it.second, it.third) }
    val names = Data213.stops.map { it.first }
    val hms = names.indexOf("ХМС")
    val dp = names.indexOf("ДЪРЖАВНА ПЕЧАТНИЦА")
    val g = RouteGeometry.fit(Data213.shape, stops)

    t("G1 bus 213: every stop placed on the road, in order; ХМС → ДЪРЖ. ПЕЧАТНИЦА is 634 m of road") {
        yes("fitted", g != null)
        g!!
        yes("${stops.size} stops", g.stopAlong.size == stops.size)
        for (i in 1 until g.stopAlong.size) yes("stop $i not before stop ${i - 1}", g.stopAlong[i] >= g.stopAlong[i - 1])
        near("ХМС → ДП by road", 634.0, g.stopAlong[dp] - g.stopAlong[hms], 5.0)
        near("ДП → ХЕРМЕС ПАРК by road", 500.0, g.stopAlong[dp + 1] - g.stopAlong[dp], 5.0)
        near("ХМС → ДП straight", 279.0, straight(stops[hms].first, stops[hms].second,
            stops[dp].first, stops[dp].second), 3.0)
    }
    g!!

    t("G2 the old fault: in a straight line the road comes within 45 m of ДЪРЖ. ПЕЧАТНИЦА with 300+ m to go") {
        val first = walk(Data213.shape, 2.0)
            .filter { it.first > g.stopAlong[hms] && it.first < g.stopAlong[dp] }
            .first { straight(it.second, it.third, stops[dp].first, stops[dp].second) <= ARRIVAL_RADIUS }
        val toGo = g.stopAlong[dp] - first.first
        yes("still $toGo m to go by road", toGo > 300)
    }

    t("G3 ride ХМС → ДЪРЖ. ПЕЧАТНИЦА at 7 m/s with 8 m GPS scatter: 'Спирка' only at the stop itself") {
        val sc = Scatter()
        var last: Double? = null
        var announcedAt: Double? = null
        for ((trueAlong, lat, lon) in walk(Data213.shape, 7.0)) {
            if (trueAlong < g.stopAlong[hms] || trueAlong > g.stopAlong[dp] + 50) continue
            val (fLat, fLon) = sc.around(lat, lon, 8.0)
            val pos = g.place(fLat, fLon, last, 1.0,
                g.stopAlong[hms] - RouteGeometry.BACK_MARGIN, g.length)
            yes("fix at ${trueAlong.toInt()} placed", pos != null)
            // At a corner a scattered fix can fit the other leg of the turn.
            near("placed where it is", trueAlong, pos, 40.0)
            last = pos
            val d = g.distanceToStop(pos, dp, straight(fLat, fLon, stops[dp].first, stops[dp].second))
            if (announcedAt == null && d <= ARRIVAL_RADIUS) announcedAt = trueAlong
        }
        yes("announced", announcedAt != null)
        val short = g.stopAlong[dp] - announcedAt!!
        yes("'Спирка' $short m before the stop by road (≤ 60)", short <= 60.0)
    }

    t("G4 a fix scattered 40 m towards the stop at the near pass stays on its own stretch") {
        val nearPass = nearPass(g, hms, dp, stops[dp])
        val gap = straight(nearPass.second, nearPass.third, stops[dp].first, stops[dp].second)
        yes("the near pass is $gap m from the stop", gap in 30.0..45.0)
        // The fix lands right at the stop: 35–42 m of error, all towards it.
        val pos = g.place(stops[dp].first, stops[dp].second, nearPass.first - 7.0, 1.0, 0.0, g.length)
        yes("placed", pos != null)
        near("not jumped to the stop", nearPass.first, pos, 60.0)
        yes("stop still ahead by road", g.distanceToStop(pos, dp, 0.0) > 250.0)
    }

    t("G5 started at the near pass (no previous place): located on its own stretch") {
        val nearPass = nearPass(g, hms, dp, stops[dp])
        val gap = straight(nearPass.second, nearPass.third, stops[dp].first, stops[dp].second)
        yes("the near pass is $gap m from the stop", gap in 30.0..45.0)
        val pos = g.place(nearPass.second, nearPass.third, null, 0.0,
            g.stopAlong[hms] - RouteGeometry.BACK_MARGIN, g.length)
        near("located", nearPass.first, pos, 5.0)
        yes("next stop is ДЪРЖ. ПЕЧАТНИЦА", g.nextStop(pos!!, 0, PASSED_STOP_MARGIN) == dp)
        val d = g.distanceToStop(pos, dp, straight(nearPass.second, nearPass.third, stops[dp].first, stops[dp].second))
        yes("by road $d m, not arrived", d > 300.0)
    }

    t("G6 next stop by road: at a stop, just past it (within 30 m), and beyond") {
        val at = g.stopAlong[dp]
        yes("10 m before → ДП", g.nextStop(at - 10, 0, PASSED_STOP_MARGIN) == dp)
        yes("20 m past → still ДП", g.nextStop(at + 20, 0, PASSED_STOP_MARGIN) == dp)
        yes("40 m past → ХЕРМЕС ПАРК", g.nextStop(at + 40, 0, PASSED_STOP_MARGIN) == dp + 1)
        yes("past the last stop → null", g.nextStop(g.length + 100, 0, PASSED_STOP_MARGIN) == null)
    }

    t("G7 distance to a stop: by road ahead, straight line behind or unplaced, never below the straight line") {
        val pos = g.stopAlong[dp] - 200
        near("ahead, by road", 200.0, g.distanceToStop(pos, dp, 80.0), 0.01)
        near("road shorter than the straight line → straight", 250.0, g.distanceToStop(pos, dp, 250.0), 0.01)
        near("behind → straight", 30.0, g.distanceToStop(g.stopAlong[dp] + 30, dp, 30.0), 0.01)
        near("unplaced → straight", 41.0, g.distanceToStop(null, dp, 41.0), 0.01)
        near("unknown stop → straight", 41.0, g.distanceToStop(pos, 999, 41.0), 0.01)
    }

    t("G8 a shape that is not this trip's road is refused") {
        val moved = stops.toMutableList()
        moved[dp] = shift(stops[dp].first, stops[dp].second, 0.0, 400.0)
        val off = g.locate(moved[dp].first, moved[dp].second, 0.0, g.length)!!.offset
        yes("the moved stop is $off m off the road", off > RouteGeometry.STOP_MAX_OFFSET)
        yes("a stop off the road", RouteGeometry.fit(Data213.shape, moved) == null)
        yes("the other direction (stops reversed)", RouteGeometry.fit(Data213.shape, stops.reversed()) == null)
        yes("fewer than two points", RouteGeometry.fit(floatArrayOf(42f, 23f), stops) == null)
        yes("no stops", RouteGeometry.fit(Data213.shape, emptyList()) == null)
    }

    t("G9 a loop: the same place as first and last stop goes to the start and the end") {
        // A 400 m square, starting and ending at its south-west corner.
        val (aLat, aLon) = 42.70 to 23.30
        val c = listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0, 0.0 to 0.0)
            .map { (e, n) -> shift(aLat, aLon, e, n) }
        val shape = FloatArray(c.size * 2) { if (it % 2 == 0) c[it / 2].first.toFloat() else c[it / 2].second.toFloat() }
        val loop = RouteGeometry.fit(shape, listOf(c[0], c[2], c[0]))
        yes("fitted", loop != null)
        near("first stop at the start", 0.0, loop!!.stopAlong[0], 1.0)
        near("middle stop half way", 200.0, loop.stopAlong[1], 1.0)
        near("last stop at the end", loop.length, loop.stopAlong[2], 1.0)
    }

    t("G10 a fix far off the road is not placed; a window with no road gives nothing") {
        val (lat, lon) = shift(stops[dp].first, stops[dp].second, 0.0, 400.0)
        // Check it really is 100 m off every part of the road first.
        val off = g.locate(lat, lon, 0.0, g.length)!!.offset
        yes("test point $off m off the road", off > RouteGeometry.MAX_OFFSET)
        yes("not placed", g.place(lat, lon, null, 0.0, 0.0, g.length) == null)
        yes("empty window", g.locate(stops[dp].first, stops[dp].second, g.length + 10, g.length + 20) == null)
    }

    t("G11 duplicate points (stops are written twice in the feed) do no harm") {
        var dups = 0
        for (i in 1 until Data213.shape.size / 2)
            if (Data213.shape[2 * i] == Data213.shape[2 * i - 2] && Data213.shape[2 * i + 1] == Data213.shape[2 * i - 1]) dups++
        yes("the test shape has duplicates ($dups)", dups > 0)
        val p = g.locate(stops[dp].first, stops[dp].second, g.stopAlong[dp] - 50, g.stopAlong[dp] + 50)!!
        near("stop located on itself", g.stopAlong[dp], p.along, 1.0)
        yes("finite", !p.along.isNaN() && !p.offset.isNaN())
    }

    t("G12 a terminus may lie up to 100 m off the road, a stop between not more than 30 m") {
        // First stop 50 m off the start of the road, as at ЦЕНТРАЛНА ГАРА (33 m).
        val first = shift(stops[0].first, stops[0].second, 0.0, -50.0)
        val off = g.locate(first.first, first.second, 0.0, g.length)!!.offset
        yes("terminus test point $off m off", off in 31.0..RouteGeometry.TERMINUS_MAX_OFFSET)
        val withTerminus = listOf(first) + stops.drop(1)
        yes("terminus 50 m off: fitted", RouteGeometry.fit(Data213.shape, withTerminus) != null)
        val mid = stops.toMutableList()
        mid[dp] = shift(stops[dp].first, stops[dp].second, 0.0, -50.0)
        val midOff = g.locate(mid[dp].first, mid[dp].second, 0.0, g.length)!!.offset
        if (midOff > RouteGeometry.STOP_MAX_OFFSET)
            yes("a stop between $midOff m off: refused", RouteGeometry.fit(Data213.shape, mid) == null)
    }

    println("\n$ok passed, ${bad.size} failed"); bad.forEach { println("  ✗ $it") }
    kotlin.system.exitProcess(if (bad.isEmpty()) 0 else 1)
}
