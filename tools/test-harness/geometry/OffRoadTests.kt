// Off-route by the road (OffRoadWatch in service/RouteGeometry.kt) on the real
// roads of buses 213 and 305 towards ЦЕНТРАЛНА ГАРА (feed of 5 Oct 2026).
//
// Between УМБАЛ СВ. АННА and ХОТЕЛ ПЛИСКА the two share the road but for a
// short loop of 305 to ПЛОЩАД НА АВИАЦИЯТА, 80 m aside; after ХОТЕЛ ПЛИСКА they
// share it again for 1.3 km and then part — 213 by ПЛ. ОРЛОВ МОСТ, 305 by
// ГАРА ПОДУЯНЕ — until they meet again at УЛ. БЯЛО МОРЕ.
//
// Each ride replays what JourneyService does with every fix: locateOnRoad
// (the search window, the place kept for the next fix) and watchOffRoad.
import bg.sofia.transit.service.OffRoadWatch
import bg.sofia.transit.service.RouteGeometry
import kotlin.math.cos
import kotlin.math.sin

var ok = 0; val bad = mutableListOf<String>()
fun t(name: String, body: () -> Unit) {
    try { body(); ok++; println("PASS  $name") }
    catch (e: Throwable) { bad += "$name: ${e.message}"; println("FAIL  $name\n      ${e.message}") }
}
fun yes(w: String, c: Boolean) { if (!c) throw AssertionError(w) }

const val LOOKAHEAD = 3                  // JourneyService.LOOKAHEAD
const val PASSED_STOP_MARGIN = 30.0      // JourneyService.PASSED_STOP_MARGIN
const val OFF_ROUTE_GROWTH = 300.0       // JourneyService.OFF_ROUTE_GROWTH
const val STEP = 8.0                     // metres per fix: one a second at 29 km/h

fun straight(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h))
}
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

/** Deterministic GPS scatter of up to [m] metres each way. */
class Scatter(var seed: Long) {
    fun next(): Double { seed = (seed * 6364136223846793005L + 1442695040888963407L); return ((seed ushr 33) % 2001) / 1000.0 - 1.0 }
    fun around(lat: Double, lon: Double, m: Double) = shift(lat, lon, next() * m, next() * m)
}

class Line(val name: String, shape: FloatArray, stops: List<Triple<String, Double, Double>>) {
    val names = stops.map { it.first }
    val latLon = stops.map { Pair(it.second, it.third) }
    val g = RouteGeometry.fit(shape, latLon)!!
    val road = walk(shape, STEP)
    fun along(stop: String) = g.stopAlong[names.indexOf(stop)]
}

class Outcome(
    /** Where on the ridden road the watch fired, each time. */
    val fired: List<Double>,
    /** Where on the ridden road the watch by the stops would first have fired (null: never). */
    val growthAt: Double?,
    /** After each firing: where on the ridden road a fix was placed on the followed road again… */
    val back: List<Double>,
    /** …and the followed line's stop that was next from there. */
    val backStop: List<String?>
) {
    val firedAt get() = fired.lastOrNull()
}

/**
 * Rides [ridden]'s road from stop [from] to stop [to] while tracking follows
 * [followed], attached at its stop [startStop]. [blip] may move a fix aside.
 */
fun ride(
    ridden: Line, followed: Line, from: String, to: String, startStop: String,
    scatter: Double, seed: Long,
    blip: (Double) -> Double = { 0.0 }
): Outcome {
    val g = followed.g
    val sc = Scatter(seed)
    val watch = OffRoadWatch()
    var cur = followed.names.indexOf(startStop)
    var last: Double? = g.stopAlong[cur]
    var offRoute = false
    val fired = mutableListOf<Double>()
    val back = mutableListOf<Double>()
    val backStop = mutableListOf<String?>()
    var growthAt: Double? = null
    val windowMin = HashMap<Int, Double>()
    for ((trueAlong, lat, lon) in ridden.road) {
        if (trueAlong < ridden.along(from) || trueAlong > ridden.along(to)) continue
        val (sLat, sLon) = sc.around(lat, lon, scatter)
        val (fLat, fLon) = shift(sLat, sLon, blip(trueAlong), 0.0)
        // locateOnRoad
        val lastIdx = g.stopAlong.lastIndex
        val lo = g.stopAlong[(cur - 1).coerceIn(0, lastIdx)] - RouteGeometry.BACK_MARGIN
        val hi = if (offRoute) g.length
                 else g.stopAlong[(cur + LOOKAHEAD).coerceIn(0, lastIdx)] + RouteGeometry.BACK_MARGIN
        val near = g.nearest(fLat, fLon, last, 1.0, lo, hi)
        val placed = near?.takeIf { it.offset <= RouteGeometry.MAX_OFFSET }?.along
        last = placed
        if (offRoute) {
            // Back on the route only on its road (the gate before attaching).
            if (placed == null) continue
            cur = g.nextStop(placed, cur, PASSED_STOP_MARGIN) ?: lastIdx
            back += trueAlong
            backStop += followed.names[cur]
            offRoute = false
            windowMin.clear()
            continue
        }
        if (placed != null) cur = g.nextStop(placed, cur, PASSED_STOP_MARGIN) ?: lastIdx
        // The watch by the stops, for comparison (watchOffRoute).
        if (growthAt == null) {
            val end = (cur + LOOKAHEAD).coerceAtMost(lastIdx)
            windowMin.keys.retainAll { it in cur..end }
            var all = true
            for (i in cur..end) {
                val d = straight(fLat, fLon, followed.latLon[i].first, followed.latLon[i].second)
                val m = windowMin[i]
                if (m == null || d < m) windowMin[i] = d
                if (d - windowMin[i]!! < OFF_ROUTE_GROWTH) all = false
            }
            if (all) growthAt = trueAlong
        }
        // watchOffRoad
        if (watch.update(near?.offset, fLat, fLon, accurate = true, moving = true)) {
            fired += trueAlong
            offRoute = true
            last = null
        }
    }
    return Outcome(fired, growthAt, back, backStop)
}

/** Where [ridden]'s road first goes more than [m] from [other]'s, after stop [after]. */
fun parting(ridden: Line, other: Line, after: String, m: Double): Double =
    ridden.road.first { (a, lat, lon) ->
        a > ridden.along(after) &&
            other.g.locate(lat, lon, 0.0, other.g.length)!!.offset > m
    }.first

fun main() {
    val l213 = Line("213", Data213.shape, Data213.stops)
    val l305 = Line("305", Data305.shape, Data305.stops)
    val anna = "УМБАЛ СВ. АННА"; val pliska = "ХОТЕЛ ПЛИСКА"; val more = "УЛ. БЯЛО МОРЕ"
    val seeds = 1L..20L

    t("R0 the data: 305 calls at ПЛОЩАД НА АВИАЦИЯТА and Ж.К. ИЗТОК between the two, 213 at neither") {
        val i305 = l305.names.indexOf(anna); val i213 = l213.names.indexOf(anna)
        yes("305: ${l305.names.subList(i305, i305 + 4)}",
            l305.names.subList(i305, i305 + 4) == listOf(anna, "ПЛОЩАД НА АВИАЦИЯТА", "Ж.К. ИЗТОК", pliska))
        yes("213: ${l213.names.subList(i213, i213 + 2)}", l213.names.subList(i213, i213 + 2) == listOf(anna, pliska))
        // ПЛОЩАД НА АВИАЦИЯТА is on the loop, Ж.К. ИЗТОК on the shared road.
        val av = l305.latLon[i305 + 1]; val iz = l305.latLon[i305 + 2]
        val avOff = l213.g.locate(av.first, av.second, 0.0, l213.g.length)!!.offset
        val izOff = l213.g.locate(iz.first, iz.second, 0.0, l213.g.length)!!.offset
        yes("ПЛ. НА АВИАЦИЯТА $avOff m from 213's road", avOff in 70.0..90.0)
        yes("Ж.К. ИЗТОК $izOff m from 213's road", izOff < 5.0)
    }

    t("R1 aboard 305, following 213, УМБАЛ СВ. АННА → ХОТЕЛ ПЛИСКА: the 80 m loop is at the limit; if it counts, rightly, and back on the road after it") {
        // The loop: where 305's road is more than 50 m from 213's.
        val loop = l305.road.filter { (a, lat, lon) ->
            a in l305.along(anna)..l305.along(pliska) &&
                l213.g.locate(lat, lon, 0.0, l213.g.length)!!.offset > OffRoadWatch.OFFSET
        }.map { it.first }
        println("      the loop is over 50 m from 213's road for ${(loop.last() - loop.first()).toInt()} m")
        var counted = 0
        for (s in seeds) {
            val o = ride(l305, l213, anna, pliska, anna, 8.0, s)
            if (o.fired.isEmpty()) continue
            counted++
            yes("seed $s: once", o.fired.size == 1 && o.back.size == 1)
            yes("seed $s: on the loop (${o.fired[0]})", o.fired[0] in loop.first()..loop.last() + 10)
            yes("seed $s: back on the road at its end (${o.back[0]})", o.back[0] - loop.last() in 0.0..60.0)
            yes("seed $s: next stop ХОТЕЛ ПЛИСКА (${o.backStop[0]})", o.backStop[0] == pliska)
        }
        println("      counted in $counted of ${seeds.count()} rides")
    }

    t("R2 aboard 213, following 305, УМБАЛ СВ. АННА → ХОТЕЛ ПЛИСКА: not off the route (20 rides)") {
        for (s in seeds) {
            val o = ride(l213, l305, anna, pliska, anna, 8.0, s)
            yes("seed $s fired at ${o.fired}", o.fired.isEmpty())
        }
    }

    t("R3 aboard 305, following 213, on to УЛ. БЯЛО МОРЕ: off the route within 200 m of parting; the watch by the stops never") {
        val part = parting(l305, l213, pliska, OffRoadWatch.OFFSET)
        println("      305 parts from 213's road ${(part - l305.along(pliska)).toInt()} m after ХОТЕЛ ПЛИСКА")
        for (s in seeds) {
            val o = ride(l305, l213, anna, more, anna, 8.0, s)
            yes("seed $s: fired", o.firedAt != null)
            val after = o.firedAt!! - part
            yes("seed $s: fired $after m after parting", after in 100.0..200.0)
            yes("seed $s: watch by the stops at ${o.growthAt}", o.growthAt == null || o.growthAt!! > o.firedAt!! + 1000)
            if (s == 1L) println("      fired ${after.toInt()} m after parting; by the stops: " +
                (o.growthAt?.let { "${(it - o.firedAt!!).toInt()} m later" } ?: "never"))
        }
    }

    t("R4 aboard 213, following 305, on to УЛ. БЯЛО МОРЕ: off the route within 200 m of parting, well before the watch by the stops") {
        val part = parting(l213, l305, pliska, OffRoadWatch.OFFSET)
        println("      213 parts from 305's road ${(part - l213.along(pliska)).toInt()} m after ХОТЕЛ ПЛИСКА")
        for (s in seeds) {
            val o = ride(l213, l305, anna, more, anna, 8.0, s)
            yes("seed $s: fired", o.firedAt != null)
            val after = o.firedAt!! - part
            yes("seed $s: fired $after m after parting", after in 100.0..200.0)
            yes("seed $s: watch by the stops at ${o.growthAt}", o.growthAt == null || o.growthAt!! > o.firedAt!! + 1000)
            if (s == 1L) println("      fired ${after.toInt()} m after parting; by the stops: " +
                (o.growthAt?.let { "${(it - o.firedAt!!).toInt()} m later" } ?: "never"))
        }
    }

    t("R5 back on the road: aboard 305 following 213, placed again only where the roads meet, next stop УЛ. БЯЛО МОРЕ") {
        val meet = l305.road.first { (a, lat, lon) ->
            a > parting(l305, l213, pliska, OffRoadWatch.OFFSET) &&
                l213.g.locate(lat, lon, 0.0, l213.g.length)!!.offset <= RouteGeometry.MAX_OFFSET
        }.first
        for (s in seeds) {
            val o = ride(l305, l213, anna, more, anna, 8.0, s)
            yes("seed $s: back at ${o.back}", o.back.isNotEmpty() && o.back.last() >= meet - 30.0)
            yes("seed $s: next stop ${o.backStop}", o.backStop.last() == more)
        }
    }

    t("R6 aboard 213, following 213, the whole way with 8 m scatter: never off the route (20 rides)") {
        for (s in seeds) {
            val o = ride(l213, l213, l213.names[1], l213.names.last(), l213.names[1], 8.0, s)
            yes("seed $s fired at ${o.fired}", o.fired.isEmpty())
        }
    }

    t("R7 aboard 213, following 213: GPS thrown 80 m aside for 120 m of road — not off the route") {
        val start = l213.along(anna) + 300.0
        for (s in seeds) {
            val o = ride(l213, l213, anna, more, anna, 8.0, s) { a -> if (a in start..start + 120.0) 80.0 else 0.0 }
            yes("seed $s fired at ${o.fired}", o.fired.isEmpty())
        }
    }

    t("R8 OffRoadWatch: counts only accurate fixes at vehicle speed off the road; back on it starts afresh") {
        val w = OffRoadWatch()
        val lat = 42.67; val lon = 23.36
        fun at(m: Double) = shift(lat, lon, 0.0, m)
        var p = at(0.0)
        yes("first off-road fix only starts the count", !w.update(70.0, p.first, p.second, true, true))
        p = at(149.0); yes("149 m: not yet", !w.update(70.0, p.first, p.second, true, true))
        p = at(170.0); yes("slow: not counted", !w.update(70.0, p.first, p.second, true, false))
        p = at(170.0); yes("inaccurate: not counted", !w.update(70.0, p.first, p.second, false, true))
        p = at(151.0); yes("151 m: off the route", w.update(70.0, p.first, p.second, true, true))
        // Afresh after firing.
        p = at(200.0); yes("afresh: starts again", !w.update(70.0, p.first, p.second, true, true))
        p = at(300.0); yes("back within 50 m: reset", !w.update(40.0, p.first, p.second, true, false))
        p = at(400.0); yes("off again: starts again", !w.update(70.0, p.first, p.second, true, true))
        p = at(500.0); yes("100 m more: not yet", !w.update(70.0, p.first, p.second, true, true))
        p = at(560.0); yes("160 m: off the route", w.update(70.0, p.first, p.second, true, true))
        p = at(600.0); w.update(70.0, p.first, p.second, true, true)
        yes("no road: reset", !w.update(null, p.first, p.second, true, true))
        p = at(800.0); yes("after no road: starts again", !w.update(70.0, p.first, p.second, true, true))
    }

    println("\n$ok passed, ${bad.size} failed")
    bad.forEach { println("  FAILED: $it") }
    kotlin.system.exitProcess(if (bad.isEmpty()) 0 else 1)
}
