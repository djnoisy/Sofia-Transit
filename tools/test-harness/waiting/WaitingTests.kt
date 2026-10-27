// Where we waited before departure (WaitingPlace), fed as JourneyService feeds it (onFix, recordSpeed:
// settled accurate fixes from 5 s after tracking began, until departure — three accurate fixes in a row
// at 10 km/h and over, 30 m from where we settled) and asked as findBoardingStop asks it: by the stops of
// the direction actually travelled — the stop pattern of the line in the bundled feed that the fixes after
// departure follow. Against the old rule, the first settled fix under 10 km/h. On made-up journeys — a
// stand at a light after pulling away, a crawl in a queue, walking to the stop first; a fix every 1-10 s,
// with and without the receiver's speed — and on the recorded ones (TRACE_DIR), as recorded and thinned.
import bg.sofia.transit.service.WaitingPlace
import java.io.File
import java.util.Random

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n ${detail()}") }
}
fun dist(a: Double, b: Double, c: Double, d: Double): Double {
    val x = Math.toRadians(d - b) * Math.cos(Math.toRadians((a + c) / 2)); val y = Math.toRadians(c - a)
    return 6_371_000.0 * Math.hypot(x, y)
}

class Fix(val rec: Long, val lat: Double, val lon: Double, val acc: Double?, val speed: Double?)
class Stop(val lat: Double, val lon: Double, val name: String)

/** Before departure, as JourneyService: where we waited, the old reference, the fix departure was detected at (-1: none). */
class Before(val wp: WaitingPlace, val old: Pair<Double, Double>?, val depIdx: Int)

fun replay(fixes: List<Fix>, startMs: Long): Before {
    val wp = WaitingPlace()
    var old: Pair<Double, Double>? = null
    var prev: Fix? = null
    var streak = 0
    var depRef: Pair<Double, Double>? = null
    val samples = ArrayDeque<Double>()
    fun short() = if (samples.isEmpty()) null else samples.toList().takeLast(5).average() * 3.6
    for ((i, f) in fixes.withIndex()) {
        val gap = prev?.let { f.rec - it.rec } ?: 0L
        val derived = prev?.takeIf { gap in 1..30_000 }?.let { dist(it.lat, it.lon, f.lat, f.lon) / (gap / 1000.0) }
        val accurate = f.acc != null && f.acc <= 30.0
        // recordSpeed: the receiver's speed when above 0, else from positions.
        val mps = if (f.speed != null && f.speed > 0.0) f.speed else derived
        if (mps == null || mps.isNaN() || mps > 25.0) streak = 0
        else {
            streak = if (depRef != null && accurate && mps * 3.6 >= 10.0) streak + 1 else 0
            samples.addLast(mps); while (samples.size > 15) samples.removeFirst()
            if (streak >= 3 && dist(depRef!!.first, depRef.second, f.lat, f.lon) >= 30.0) return Before(wp, old, i)
        }
        // onFix: once settled, the departure reference, the old rule and where we waited.
        val settled = accurate && f.rec - startMs >= 5_000
        if (depRef == null && settled) depRef = f.lat to f.lon
        if (settled) {
            if (old == null && (short() ?: 0.0) < 10.0) old = f.lat to f.lon
            wp.onFix(f.rec, f.lat, f.lon, f.speed ?: derived, short())
        }
        prev = f
    }
    return Before(wp, old, -1)
}

/** The stop nearest [ref], and how far (findBoardingStop); found when within 60 m (BOARDING_REF_MAX_DIST). */
fun nearest(stops: List<Stop>, ref: Pair<Double, Double>?): Pair<Int, Double>? = ref?.let { r ->
    stops.indices.minByOrNull { dist(r.first, r.second, stops[it].lat, stops[it].lon) }
        ?.let { it to dist(r.first, r.second, stops[it].lat, stops[it].lon) } }
fun boarded(stops: List<Stop>, ref: Pair<Double, Double>?): Int? = nearest(stops, ref)?.takeIf { it.second <= 60.0 }?.first
fun newRef(b: Before, stops: List<Stop>) = b.wp.placeFor(stops.map { it.lat to it.lon }, 60.0)

// ── Made up: a straight street northwards; stop S at 0, the next N at +D, the one before at -300 ──
const val LAT0 = 42.65
const val LON0 = 23.37
const val M_LAT = 1.0 / 111_195.0
val M_LON = 1.0 / (111_195.0 * Math.cos(Math.toRadians(LAT0)))
fun stopsOf(d: Double) = listOf(Stop(LAT0 - 300 * M_LAT, LON0 + 8 * M_LON, "P"), Stop(LAT0, LON0 + 8 * M_LON, "S"),
    Stop(LAT0 + d * M_LAT, LON0 + 8 * M_LON, "N"))

/** Where we are, every 0.1 s: y metres along the street, at v m/s. */
class Sample(val t: Double, val y: Double, val v: Double)

/**
 * Standing 60 s 5 m short of S, 15 s more aboard, then away to [stand] m on (a = 1 m/s², braking 1.2, at
 * most 11 m/s) — or, [crawl], in a queue: 6 m at 1.8 m/s, 4 s standing, over and over — standing there
 * 25 s, then on for good.
 */
fun profile(stand: Double, crawl: Boolean): List<Sample> {
    val out = ArrayList<Sample>()
    var t = 0.0; var y = -5.0; var v = 0.0
    val dt = 0.1
    while (t < 75.0) { out += Sample(t, y, 0.0); t += dt }
    if (!crawl) {
        while (y < stand - 0.05) {
            v = if (stand - y <= v * v / 2.4 + 0.01) maxOf(0.3, v - 1.2 * dt) else minOf(11.0, v + dt)
            y += v * dt; out += Sample(t, y, v); t += dt
        }
    } else {
        while (y < stand - 0.05) {
            var moved = 0.0
            while (moved < 6.0 && y < stand) { y += 1.8 * dt; moved += 1.8 * dt; out += Sample(t, y, 1.8); t += dt }
            val until = t + 4.0
            while (t < until) { out += Sample(t, y, 0.0); t += dt }
        }
    }
    v = 0.0
    val until = t + 25.0
    while (t < until) { out += Sample(t, y, 0.0); t += dt }
    while (y < stand + 700) { v = minOf(11.0, v + dt); y += v * dt; out += Sample(t, y, v); t += dt }
    return out
}

/** Fixes of [prof] about every [every] s, wandering ~2 m; walking [walkIn] m to S first, at 1.4 m/s. */
fun fixesOf(prof: List<Sample>, every: Double, rnd: Random, speedGiven: Boolean, walkIn: Double): List<Fix> {
    val out = ArrayList<Fix>()
    var ex = 0.0; var ey = 0.0
    fun fix(t: Double, y: Double, v: Double) {
        ex = 0.8 * ex + rnd.nextGaussian() * 1.2; ey = 0.8 * ey + rnd.nextGaussian() * 1.2
        val sp = if (v == 0.0) Math.abs(0.1 + rnd.nextGaussian() * 0.08) else maxOf(0.0, v + rnd.nextGaussian() * 0.15)
        out += Fix(START + (t * 1000).toLong(), LAT0 + (y + ey) * M_LAT, LON0 + (3 + ex) * M_LON, 5.0, if (speedGiven) sp else null)
    }
    var off = 0.0
    if (walkIn > 0) {
        var t = 1.0
        while (t * 1.4 < walkIn) { fix(t, -5.0 - walkIn + t * 1.4, 1.4); t += every }
        off = t
    }
    var next = if (walkIn > 0) 0.0 else 1.0
    for (s in prof) if (s.t >= next) { fix(s.t + off, s.y, s.v); next = s.t + every * (0.9 + rnd.nextDouble() * 0.2) }
    return out
}
const val START = 1_000_000_000_000L

/** Of [runs] made-up journeys: boarded at S, at N, at none — by the new rule and by the old. */
class Tally(var s: Int = 0, var n: Int = 0, var none: Int = 0, var oldS: Int = 0, var oldN: Int = 0, var oldNone: Int = 0)
fun tally(stand: Double, crawl: Boolean, every: Double, speedGiven: Boolean, walkIn: Double, d: Double, runs: Int): Tally {
    val stops = stopsOf(d)
    val rnd = Random((stand * 1000 + every * 10 + d).toLong() + (if (crawl) 7 else 0) + (if (speedGiven) 13 else 0) + walkIn.toLong())
    val out = Tally()
    val prof = profile(stand, crawl)
    repeat(runs) {
        val b = replay(fixesOf(prof, every, rnd, speedGiven, walkIn), START)
        when (boarded(stops, newRef(b, stops))) { 1 -> out.s++; 2 -> out.n++; else -> out.none++ }
        when (boarded(stops, b.old)) { 1 -> out.oldS++; 2 -> out.oldN++; else -> out.oldNone++ }
    }
    return out
}

// ── Recorded: the stop patterns of each line, and the one the fixes after departure follow ──
/** Where along [stops] (stop index + fraction) [lat]/[lon] lies, and how far off. */
fun along(stops: List<Stop>, lat: Double, lon: Double): Pair<Double, Double> {
    var best = Double.MAX_VALUE; var pos = 0.0
    val mLat = 111_195.0; val mLon = 111_195.0 * Math.cos(Math.toRadians(lat))
    for (i in 0 until stops.size - 1) {
        val ax = (stops[i].lon - lon) * mLon; val ay = (stops[i].lat - lat) * mLat
        val bx = (stops[i + 1].lon - lon) * mLon; val by = (stops[i + 1].lat - lat) * mLat
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
        val d = Math.hypot(ax + t * dx, ay + t * dy)
        if (d < best) { best = d; pos = i + t }
    }
    return pos to best
}
fun travelled(patterns: List<List<Stop>>, after: List<Fix>): List<Stop>? {
    if (after.size < 5) return null
    return patterns.mapNotNull { p ->
        val a = after.map { along(p, it.lat, it.lon) }
        if (a.last().first - a.first().first <= 0.2) null else p to a.map { it.second }.average()
    }.minByOrNull { it.second }?.first
}

fun main(args: Array<String>) {
    // Unit: what WaitingPlace keeps.
    val line = stopsOf(200.0).map { it.lat to it.lon }
    run {
        val w = WaitingPlace()
        var t = START
        for (k in 0 until 30) { w.onFix(t, LAT0 - 150 * M_LAT, LON0, 0.0, 0.0); t += 1000 }        // a crossing
        for (k in 1..110) { w.onFix(t, LAT0 + (-150 + k * 1.3) * M_LAT, LON0, 1.3, 4.7); t += 1000 }
        for (k in 0 until 20) { w.onFix(t, LAT0 - 4 * M_LAT, LON0, 0.1, 0.4); t += 1000 }          // the stop
        val p = w.placeFor(line, 60.0)
        check("stood at a crossing, then at the stop: the stop", p != null && dist(p.first, p.second, LAT0 - 4 * M_LAT, LON0) < 1.0) { "$p" }
    }
    run {
        val w = WaitingPlace()
        var t = START
        for (k in 0 until 40) { w.onFix(t, LAT0 - 150 * M_LAT, LON0, 0.0, 0.0); t += 1000 }        // a crossing only
        val p = w.placeFor(line, 60.0)
        check("stood only away from every stop: the first fix, as before", p == LAT0 - 150 * M_LAT to LON0) { "$p" }
    }
    run {
        val w = WaitingPlace()
        var t = START
        for (k in 0 until 20) { w.onFix(t, LAT0 + k * 2.5 * M_LAT, LON0, 2.5, 9.0); t += 1000 }
        check("never standing, aboard at a crawl: the first fix, as before", w.placeFor(line, 60.0) == LAT0 to LON0)
    }
    run {
        val w = WaitingPlace()
        var t = START
        for (k in 0 until 20) { w.onFix(t, LAT0, LON0, 0.1, 0.4); t += 1000 }                     // at S
        w.onFix(t + 10_000, LAT0 + 175 * M_LAT, LON0, 0.1, 0.4)                                   // 10 s on, standing 175 m on
        for (k in 1..20) w.onFix(t + 10_000 + k * 1000, LAT0 + 175 * M_LAT, LON0, 0.1, 0.4)       // ...25 m short of N
        val p = w.placeFor(line, 60.0)
        check("first seen again standing far beyond walking reach, near the next stop: still S",
            p != null && dist(p.first, p.second, LAT0, LON0) < 1.0) { "$p" }
    }
    run {
        val w = WaitingPlace()
        w.onFix(START, LAT0, LON0, null, null)
        check("no speed at all on the first fix: taken as before", w.placeFor(line, 60.0) == LAT0 to LON0)
        w.reset()
        check("reset: nothing known", w.placeFor(line, 60.0) == null)
    }

    // Made up: what the review of 27 Oct 2026 found the first version lost. 200 runs a case.
    val runs = 200
    println("Made up, $runs runs a case: boarded at S/N/none by the new rule [the old], next stop N at +200 m")
    for (crawl in listOf(false, true)) for (walkIn in listOf(0.0, 100.0)) for (speedGiven in listOf(true, false)) {
        println(" ${if (crawl) "crawling in a queue" else "a light"}, ${if (walkIn > 0) "walked 100 m to S" else "begun at S"}, " +
            "receiver's speed ${if (speedGiven) "given" else "absent"}")
        for (stand in listOf(20.0, 65.0, 100.0, 130.0, 160.0)) {
            val row = StringBuilder("   %4.0f m:".format(stand))
            for (every in listOf(1.0, 3.0, 5.0, 8.0, 10.0)) {
                val r = tally(stand, crawl, every, speedGiven, walkIn, 200.0, runs)
                row.append("  ${every.toInt()}s %3d/%3d/%3d [%3d/%3d/%3d]".format(r.s, r.n, r.none, r.oldS, r.oldN, r.oldNone))
                val name = "${if (crawl) "crawl" else "light"} to $stand m, walked $walkIn m, speed ${if (speedGiven) "given" else "absent"}, every $every s"
                // Never worse than the old rule by more than chance, never the wrong stop — but for
                // a crawl in a queue from S to within 60 m of N before departure was seen (known
                // limit: standing there is standing near a stop, and never beyond walking reach).
                if (crawl && stand > 140.0) continue
                check("$name: S at least as often as before", r.s >= r.oldS - runs / 50) { "${r.s} < ${r.oldS}" }
                check("$name: never N", r.n == 0) { "${r.n}" }
            }
            println(row)
        }
    }
    // Stops 100 m apart, a light between them: with a fix a second or two, the stop we waited at; with
    // sparser fixes the stand at the light may be the place, nearer the next stop (known limit, review of
    // 27 Oct 2026: the first fix after pulling away is within walking reach of the stop) — shown, not asserted.
    println(" a light between stops 100 m apart, begun at S, receiver's speed given")
    for (stand in listOf(40.0, 50.0, 60.0)) {
        val row = StringBuilder("   %4.0f m:".format(stand))
        for (every in listOf(1.0, 2.0, 3.0, 5.0)) {
            val r = tally(stand, false, every, true, 0.0, 100.0, runs)
            row.append("  ${every.toInt()}s %3d/%3d/%3d [%3d/%3d/%3d]".format(r.s, r.n, r.none, r.oldS, r.oldN, r.oldNone))
            if (every <= 2.0) check("stops 100 m apart, light at $stand m, every $every s: S", r.s == runs) { "${r.s}/${r.n}/${r.none}" }
        }
        println(row)
    }
    // Walking to the stop first, with the receiver's speed and a fix a second or so: found, unlike before.
    for (stand in listOf(20.0, 65.0, 100.0, 130.0)) for (crawl in listOf(false, true)) for (every in listOf(1.0, 3.0)) {
        val r = tally(stand, crawl, every, true, 100.0, 200.0, runs)
        check("walked 100 m to S, ${if (crawl) "crawl" else "light"} to $stand m, every $every s: S found nearly always",
            r.s >= runs * 95 / 100) { "${r.s}/$runs" }
    }

    // Recorded.
    val dir = System.getenv("TRACE_DIR")?.let { File(it) }
    val patternsFile = args.getOrNull(0)?.let { File(it) }
    if (dir == null || !dir.isDirectory || patternsFile == null || !patternsFile.isFile) {
        println("(no TRACE_DIR: recorded journeys not looked at)")
    } else {
        val patterns = HashMap<String, MutableList<List<Stop>>>()
        patternsFile.readLines().forEach { l ->
            val (r, s) = l.split("|", limit = 2)
            patterns.getOrPut(r) { ArrayList() } += s.split(";").map { it.split(",").let { p -> Stop(p[0].toDouble(), p[1].toDouble(), p[2]) } }
        }
        val farthest = HashMap<String, Double>()
        for (thin in listOf(0, 3, 5, 10, -1)) {   // -1: as recorded, without the receiver's speed
            val label = when (thin) { 0 -> "as recorded"; -1 -> "without the receiver's speed"; else -> "a fix every $thin s" }
            println("Recorded, $label:")
            for (f in dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".txt") }.sortedBy { it.name }) {
                var route = ""; var start = 0L; var fixes = ArrayList<Fix>(); var part = 0
                fun judge() {
                    if (fixes.isEmpty()) return
                    part++
                    var last = Long.MIN_VALUE / 2
                    val use = when (thin) {
                        0 -> fixes
                        -1 -> fixes.map { Fix(it.rec, it.lat, it.lon, it.acc, null) }
                        else -> fixes.filter { if (it.rec - last >= thin * 1000L) { last = it.rec; true } else false }
                    }
                    val b = replay(use, start)
                    val name = f.name.removeSuffix(".txt") + if (part > 1) " #$part" else ""
                    if (b.depIdx < 0) { println("  $name: no departure"); return }
                    val after = use.drop(b.depIdx).filter { (it.acc ?: 999.0) <= 30.0 }.takeWhile { it.rec - use[b.depIdx].rec <= 240_000 }
                    val p = travelled(patterns[route].orEmpty(), after) ?: run { println("  $name: no stop pattern followed"); return }
                    val n = nearest(p, newRef(b, p)); val o = nearest(p, b.old)
                    fun show(x: Pair<Int, Double>?) = x?.let { "%.0f m from %s".format(it.second, p[it.first].name) } ?: "—"
                    println("  $name: where we waited ${show(n)} (before: ${show(o)})")
                    if (thin == 0 && n != null && n.second <= 60.0) farthest[name] = n.second
                    if (o != null && o.second <= 60.0)
                        check("$name, $label: the stop we boarded at still found", n != null && n.second <= 60.0 && n.first == o.first) { show(n) }
                    if (f.name == "2026-10-27_76.txt" && thin == 0)
                        check("27.10: begun walking to МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ — found now", n != null && n.second <= 30.0) { show(n) }
                }
                for (l in f.readLines().map { it.trimEnd('\r') }) {
                    if (l.startsWith("# start ")) {
                        judge(); route = Regex("""\(route ([^,)]+)""").find(l)?.groupValues?.get(1) ?: ""
                        start = l.split(" ")[2].toLong(); fixes = ArrayList()
                    } else if (l.startsWith("F,")) {
                        val q = l.split(",")
                        fixes += Fix(q[1].toLong(), q[3].toDouble(), q[4].toDouble(), q[5].toDoubleOrNull(), q.getOrNull(6)?.toDoubleOrNull())
                    }
                }
                judge()
            }
        }
        if (farthest.isNotEmpty()) println("As recorded, where we waited lies %.0f-%.0f m from the stop we boarded at"
            .format(farthest.values.min(), farthest.values.max()))
    }

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
