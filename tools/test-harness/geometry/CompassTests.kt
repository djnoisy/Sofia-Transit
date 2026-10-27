// The compass's verdict on a line's direction (JourneyService.compassDirection:
// RouteGeometry.headingOf + compassVerdict) along every road trips take in the
// bundled feed, against the compass before 27 Oct 2026 (the straight lines
// between stops; a route near us both ways taken as nothing near). The two
// directions as the app prepares them (compass_extract.py); along every shape
// the trips of each take, every 25 m, heading along the road and 25° off
// either way: how often each compass tells the other direction. With
// TRACE_DIR, the journey of 27 Oct 2026 b (76 round the block between ХМС and
// ДЪРЖАВНА ПЕЧАТНИЦА, 305 chosen): told as we pulled away.
import bg.sofia.transit.service.RouteGeometry
import bg.sofia.transit.service.RouteGeometry.Way
import java.io.File
import java.time.Instant
import java.time.ZoneId

// As JourneyService: SEGMENT_NEAR_RADIUS, SEGMENT_AMBIGUITY_SLACK, COMPASS_MATCH_DEG, COMPASS_OPPOSE_DEG.
const val NEAR = 150.0; const val SLACK = 30.0; const val MATCH = 60.0; const val OPPOSE = 120.0

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n ${detail()}") }
}

/** JourneyService.stretchesNear: the straight lines between consecutive stops near a point. */
fun chordStretches(ll: List<Pair<Double, Double>>, lat: Double, lon: Double): List<Pair<Double, Double>> {
    val cosLat = Math.cos(Math.toRadians(lat))
    fun x(lo: Double) = (lo - lon) * 111_320.0 * cosLat
    fun y(la: Double) = (la - lat) * 110_540.0
    val near = ArrayList<Pair<Double, Double>>()
    for (i in 0 until ll.lastIndex) {
        val (aLat, aLon) = ll[i]; val (bLat, bLon) = ll[i + 1]
        val ax = x(aLon); val ay = y(aLat); val bx = x(bLon); val by = y(bLat)
        val dx = bx - ax; val dy = by - ay; val len2 = dx * dx + dy * dy
        if (len2 < 25.0) continue
        val t = (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        val d = Math.hypot(ax + t * dx, ay + t * dy)
        if (d <= NEAR) near += d to bearing(aLat, aLon, bLat, bLon)
    }
    return near
}
fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
    val y = Math.sin(dl) * Math.cos(p2); val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
    return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
}
fun pts(s: String) = s.split(";").filter { it.isNotBlank() }.map { p -> p.split(",").let { it[0].toDouble() to it[1].toDouble() } }
fun floats(sp: List<Pair<Double, Double>>) = FloatArray(sp.size * 2) { k -> (if (k % 2 == 0) sp[k / 2].first else sp[k / 2].second).toFloat() }

class Line(val route: String, val heads: List<String>, val stops: List<List<Pair<Double, Double>>>,
           val roads: List<RouteGeometry?>) {
    /** As compassDirection now: by the road where a direction has one, else by its stops. */
    fun told(lat: Double, lon: Double, ours: Double): Int? =
        RouteGeometry.compassVerdict(heads.indices.map { j ->
            RouteGeometry.headingOf(roads[j]?.stretchesNear(lat, lon, NEAR)
                ?: chordStretches(stops[j], lat, lon), SLACK, OPPOSE) }, ours, MATCH, OPPOSE)
    /** As compassDirection before: by the stops, a route near us both ways taken as nothing near. */
    fun toldBefore(lat: Double, lon: Double, ours: Double): Int? {
        val diffs = heads.indices.map { j ->
            (RouteGeometry.headingOf(chordStretches(stops[j], lat, lon), SLACK, OPPOSE) as? Way.One)
                ?.let { RouteGeometry.angleApart(it.bearing, ours) } }
        val matching = diffs.indices.filter { diffs[it] != null && diffs[it]!! < MATCH }
        val others = diffs.indices - matching.toSet()
        return if (matching.size == 1 && others.all { diffs[it] == null || diffs[it]!! > OPPOSE }) matching.first() else null
    }
}

fun main(args: Array<String>) {
    val dir = File(args[0])
    val shapes = dir.resolve("shapes.csv").readLines().associate { it.substringBefore("|") to pts(it.substringAfter("|")) }
    val order = dir.resolve("order.csv").readLines().associate { it.substringBefore("|") to it.substringAfter("|").split(",") }
    val walks = dir.resolve("walks.csv").readLines().map { it.split("|") }.groupBy { it[0] }
    val lines = dir.resolve("directions.csv").readLines().map { it.split("|") }.groupBy { it[0] }.map { (route, ds) ->
        val stops = ds.map { pts(it[2]) }
        // As loadCandidateRoads: the first of the line's shapes, in the database's order, that fits the stops.
        Line(route, ds.map { it[1] }, stops, stops.map { ll ->
            order[route].orEmpty().firstNotNullOfOrNull { id -> shapes[id]?.let { RouteGeometry.fit(floats(it), ll) } } })
    }.associateBy { it.route }
    var points = 0; var right = 0; var wrong = 0; var rightBefore = 0; var wrongBefore = 0; var worse = 0
    val wrongAt = HashMap<String, Int>()
    for ((route, ws) in walks) {
        val l = lines[route] ?: continue
        for (w in ws) {
            val c = l.heads.indexOf(w[1])
            val sp = shapes[w[2]] ?: continue
            for (i in 0 until sp.lastIndex) {
                val (aLat, aLon) = sp[i]; val (bLat, bLon) = sp[i + 1]
                val len = Math.hypot((bLon - aLon) * 111_320.0 * Math.cos(Math.toRadians(aLat)), (bLat - aLat) * 110_540.0)
                if (len < 5.0) continue
                val h = bearing(aLat, aLon, bLat, bLon)
                val steps = maxOf(1, (len / 25.0).toInt())
                for (k in 0 until steps) {
                    val f = (k + 0.5) / steps
                    val lat = aLat + (bLat - aLat) * f; val lon = aLon + (bLon - aLon) * f
                    for (off in listOf(0.0, -25.0, 25.0)) {
                        val ours = (h + off + 360.0) % 360.0
                        points++
                        val now = l.told(lat, lon, ours); val before = l.toldBefore(lat, lon, ours)
                        if (now == c) right++ else if (now != null) { wrong++; wrongAt.merge("$route ${w[2]}", 1, Int::plus) }
                        if (before == c) rightBefore++ else if (before != null) wrongBefore++
                        if (now != null && now != c && before == c) worse++
                    }
                }
            }
        }
    }
    println("compass along the roads of ${walks.size} lines' trips (${points} points, heading along and 25° off): " +
        "now $right right, $wrong wrong; before $rightBefore right, $wrongBefore wrong; wrong now where right before: $worse")
    println("  wrong now, most: " + wrongAt.entries.sortedByDescending { it.value }.take(8).joinToString { "${it.key} ${it.value}" })
    check("the compass by road tells the other direction at under a tenth of the points it did", wrong * 10 < wrongBefore) {
        "$wrong against $wrongBefore" }
    check("never the other direction where the compass before told ours", worse == 0) { "$worse points" }
    check("lines whose two directions both have a road", lines.values.all { l -> l.roads.all { it != null } }) {
        lines.values.filter { l -> l.roads.any { it == null } }.joinToString { it.route } }

    // 27 Oct 2026, b: 76 round the block between ХМС and ДЪРЖАВНА ПЕЧАТНИЦА (305's road too), 305 chosen.
    val l305 = lines.getValue("A87")
    val gara = l305.heads.indexOf("ЦЕНТРАЛНА ГАРА")
    val trace = System.getenv("TRACE_DIR")?.let { File(it, "2026-10-27_76b.txt") }
    if (trace != null && trace.isFile) {
        fun clock(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.of("Europe/Sofia")).toLocalTime().toString().take(8)
        val fixes = trace.readLines().map { it.trimEnd('\r').split(",") }
            .filter { it[0] == "F" && (it[5].toDoubleOrNull() ?: 99.0) <= 30.0 && it.getOrNull(7)?.isNotEmpty() == true }
            .filter { clock(it[1].toLong()) >= "08:47:24" }        // departure, as the app took it
        fun first(byRoad: Boolean) = fixes.firstNotNullOfOrNull { p ->
            (if (byRoad) l305.told(p[3].toDouble(), p[4].toDouble(), p[7].toDouble())
             else l305.toldBefore(p[3].toDouble(), p[4].toDouble(), p[7].toDouble()))?.let { clock(p[1].toLong()) to it } }
        val road = first(true); val stopsOnly = first(false)
        println("27.10 b: by road ${road?.let { "${it.first} ${l305.heads[it.second]}" }}; " +
            "by the stops ${stopsOnly?.let { "${it.first} ${l305.heads[it.second]}" }}")
        check("27.10 b: ЦЕНТРАЛНА ГАРА told by road as we pulled away (by 08:47:26)",
            road != null && road.second == gara && road.first <= "08:47:26") { "$road" }
        check("27.10 b: never КУЛИНАРЕН К-Т, the other way", fixes.none { p ->
            l305.told(p[3].toDouble(), p[4].toDouble(), p[7].toDouble()).let { it != null && it != gara } })
    } else println("(no TRACE_DIR: the journey of 27.10 b not looked at)")

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
