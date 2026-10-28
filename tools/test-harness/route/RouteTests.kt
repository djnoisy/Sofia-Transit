// The directions of a line and the route each takes (data/repository/RouteOfTheDay.kt): on made-up trips, and
// on a whole static feed (route/route_extract.py), day by day, against the rule it replaced — the two headsigns
// with most trips of all, and for each the trip with most stops of all; now the two of those running that day,
// each by the route it takes that day. Also: lines whose two directions do not run opposite ways. Every trip running that day is ridden
// twice: as a passenger choosing the direction it runs along (none offered: "not offered"), and as an
// identified vehicle, named by its trip id's prefix as the app names it (getHeadsignByTripIdPrefix) and followed
// by its variant, that prefix (JourneyService.adoptVehicle → getStopsForTripVariant), else by that name; and how often identifying the vehicle
// says "Посоката е коригирана" for a trip followed by the direction it runs along.
// Judged by the stops themselves, as the owner judges them — two poles are two stops, however near (28 Oct 2026):
// right when each of its stops is a stop of the route followed, by id, in order; and how many stops of that route
// it passes without calling ("said, not called").
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.data.repository.RouteOfTheDay.Stop
import bg.sofia.transit.data.repository.RouteOfTheDay.TodaysTrip
import java.io.File

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n ${detail()}") }
}

fun dist(a: Stop, b: Stop) = Math.hypot((a.lat - b.lat) * 111_195.0, (a.lon - b.lon) * 111_195.0 * Math.cos(Math.toRadians(a.lat)))
/** The positions on [route] that [trip]'s stops are at, in order; null if it does not run along it. */
fun matched(trip: TodaysTrip, route: TodaysTrip): List<Int>? {
    val at = ArrayList<Int>(); var j = 0
    for (s in trip.stops) {
        while (j < route.stops.size && route.stops[j].id != s.id) j++
        if (j == route.stops.size) return null
        at += j; j++
    }
    return at
}
/** Stops of [route] between the first and the last of [trip]'s that it does not call at. */
fun skipped(at: List<Int>) = if (at.isEmpty()) 0 else (at.last() - at.first() + 1) - at.size

class FeedTrip(val route: String, val name: String, val kind: String, val trip: TodaysTrip, val service: String)
fun kindOf(t: String) = when (t) { "0" -> "трамвай"; "3" -> "автобус"; "11" -> "тролей"; "1" -> "метро"; else -> "вид $t" }

/** The rule replaced: TripDao.getHeadsignCountsForRoute (top 2) + getRepresentativeTrip (most stops), all trips. */
fun oldFor(all: List<FeedTrip>, headsign: String): TodaysTrip? =
    all.filter { it.trip.headsign == headsign }.maxByOrNull { it.trip.stops.size }?.trip
fun oldDirections(all: List<FeedTrip>): List<TodaysTrip> =
    all.filter { it.trip.headsign.isNotEmpty() }.groupBy { it.trip.headsign }.entries
        .sortedByDescending { it.value.size }.take(2).mapNotNull { oldFor(all, it.key) }

fun prefixOf(tripId: String) = tripId.split("-").let { if (it.size < 2) "" else "${it[0]}-${it[1]}-" }
fun mostCommon(names: List<String>) = names.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull()?.key

/** Whether two routes run the opposite ways: their stops near one another (150 m, across the street) meet mostly backwards. */
fun opposite(a: TodaysTrip, b: TodaysTrip): Boolean {
    val at = a.stops.mapNotNull { s -> b.stops.indices.minByOrNull { dist(s, b.stops[it]) }?.takeIf { dist(s, b.stops[it]) <= 150.0 } }
    val back = at.zipWithNext().count { (x, y) -> y < x }
    return back >= 2 && back > at.zipWithNext().count { (x, y) -> y > x }
}

class Tally { var right = 0; var notOffered = 0; var wrong = 0; var said = 0
    override fun toString() = "right $right, wrong stops $wrong, not offered $notOffered, stops said but not called $said" }
fun ride(trip: TodaysTrip, route: TodaysTrip?, t: Tally): Boolean {
    if (route == null) { t.notOffered++; return false }
    val at = matched(trip, route) ?: run { t.wrong++; return false }
    t.right++; t.said += skipped(at); return true
}
/** As a passenger: the direction its trip runs along, if one is offered. */
fun chosen(trip: TodaysTrip, dirs: List<TodaysTrip>): TodaysTrip? = dirs.firstOrNull { matched(trip, it) != null }

fun main(args: Array<String>) {
    fun s(id: String, y: Double) = Stop(id, 42.7 + y / 111_195.0, 23.3)
    fun t(id: String, hs: String, vararg st: Stop) = TodaysTrip(id, hs, st.toList())
    val a = s("a", 0.0); val b = s("b", 400.0); val c = s("c", 800.0); val d = s("d", 1200.0); val e = s("e", 1600.0)
    // Made up.
    run {
        val full = t("f", "E", a, b, c, d, e); val short = t("s", "E", c, d, e)
        check("a full run and its short runs: the full run", RouteOfTheDay.routeFor(listOf(short, short, short, full), "E") === full)
    }
    run {
        val full = t("f", "Z", a, b, c, d, e); val fromPole = t("s", "Z", s("c2", 825.0), d, e)   // another pole of c, 25 m off
        check("short runs from another pole of a stop, more of them than full runs: the short run — two poles are two stops (63, known)",
            RouteOfTheDay.routeFor(listOf(fromPole, fromPole, fromPole, full), "Z") === fromPole)
    }
    run {
        val there = t("t", "E", a, b, c, d, e)
        val back = t("r", "A", s("e'", 1600.0), s("d'", 1200.0), s("c'", 800.0), s("b'", 400.0), s("a'", 0.0))  // across the street
        check("the way back across the street is not along the way there", !RouteOfTheDay.along(back, there))
    }
    run {
        val fromDepot = t("x", "ГАРА ПОДУЯНЕ", a, b, c, d, e); val fromIztok = t("y", "Гара Подуяне", d, e)
        val all = listOf(fromDepot, fromIztok, fromIztok)
        check("headsigns compared exactly: 'Гара Подуяне' and 'ГАРА ПОДУЯНЕ' are runs from different ends (tram 22)",
            RouteOfTheDay.routeFor(all, "Гара Подуяне") === fromIztok && RouteOfTheDay.routeFor(all, "ГАРА ПОДУЯНЕ") === fromDepot)
    }
    run {
        val main = t("m", "E", a, b, c, e); val detour = t("v", "E", a, b, s("x", 900.0), s("y", 1000.0), e)
        check("a detour run now and then under the same headsign: the route most of them take",
            RouteOfTheDay.routeFor(listOf(main, main, main, detour), "E") === main)
    }
    run {
        val school = t("s", "E", a, b, s("x", 600.0), c, d, e); val usual = t("u", "E", a, b, c, d, e)
        check("a run calling at extra stops takes in the usual ones too, and is followed (known: they hear its extra stops)",
            RouteOfTheDay.routeFor(listOf(usual, usual, usual, school), "E") === school)
    }
    run {
        val there = t("t", "E", a, b, c, d, e); val back = t("r", "A", e, d, c, b, a); val other = t("o", "Ш", a, b)
        val dirs = RouteOfTheDay.directions(listOf(there, back, other), listOf("E", "X", "A", "Ш"))
        check("the busiest headsigns of all, of those running today: one that does not run today gives way to the next",
            dirs.map { it.first } == listOf("E", "A")) { "${dirs.map { it.first }}" }
        check("none of them running today: no directions (the rule of all trips decides)",
            RouteOfTheDay.directions(listOf(there), listOf("X", "Y")).isEmpty())
    }
    run {
        // The way back's headsign not running today: the next is a short run of the way there.
        val there = t("t", "E", a, b, c, d, e); val short = t("s", "C", a, b, c)
        check("the next headsign a short run the same way: not offered as the way back — the rule of all trips decides",
            RouteOfTheDay.directions(listOf(there, short), listOf("E", "A", "C")).isEmpty())
    }
    run {
        val fromL = t("A3-A2388-1", "БУЛ. НИКОЛА ПЕТКОВ", a, b, c); val fromM = t("A3-A1801-1", "БУЛ. НИКОЛА ПЕТКОВ", e, d, c)
        val all = listOf(fromL, fromL, fromM)
        check("a headsign served from both ends: each vehicle by its variant (111 to БУЛ. НИКОЛА ПЕТКОВ)",
            RouteOfTheDay.routeOfVariant(all, "A3-A1801-") === fromM && RouteOfTheDay.routeOfVariant(all, "A3-A2388-") === fromL)
    }
    check("no trips today: no route", RouteOfTheDay.routeFor(emptyList(), "E") == null)

    // A whole feed, day by day.
    val file = args.getOrNull(0)?.let { File(it) }
    if (file == null || !file.isFile) { println("(no feed extract)"); return finish() }
    val label = args.getOrNull(1) ?: ""
    val days = ArrayList<Pair<String, Set<String>>>(); val feed = ArrayList<FeedTrip>()
    file.forEachLine { l -> val p = l.split("|")
        if (p[0] == "D") days += p[1] to (if (p[2].isEmpty()) emptySet() else p[2].split(",").toSet())
        else feed += FeedTrip(p[1], p[2], p[3], TodaysTrip(p[4], p[5], p[7].split(";").map { x -> x.split(":").let { Stop(it[0], it[1].toDouble(), it[2].toDouble()) } }), p[6])
    }
    val byRoute = feed.groupBy { it.route }
    val oldDirs = byRoute.mapValues { oldDirections(it.value) }
    for ((date, services) in days) {
        val pOld = Tally(); val pNew = Tally(); val vOld = Tally(); val vNew = Tally()
        val worse = HashMap<String, Int>(); val vWorse = HashMap<String, Int>()
        var trips = 0; var falseOld = 0; var falseNew = 0; val falseNewBy = HashMap<String, Int>()
        val notOppositeNew = ArrayList<String>(); var notOppositeOld = 0
        for ((route, all) in byRoute) {
            val today = all.filter { it.service in services }
            if (today.isEmpty()) continue
            val todays = today.map { it.trip }
            val ranking = all.filter { it.trip.headsign.isNotEmpty() }.groupingBy { it.trip.headsign }.eachCount()
                .entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
            val named = RouteOfTheDay.directions(todays, ranking)
            // With none of them running today, the app keeps the rule of all trips (getDirectionsForRoute).
            val newDirs = if (named.isEmpty()) oldDirs[route]!! else named.map { it.second }
            val newNames = if (named.isEmpty()) oldDirs[route]!!.map { it.headsign } else named.map { it.first }
            if (newDirs.size == 2 && !opposite(newDirs[0], newDirs[1])) notOppositeNew += "$route ${newNames}"
            val od = oldDirs[route]!!
            if (od.size == 2 && !opposite(od[0], od[1])) notOppositeOld++
            val who = "${kindOf(all[0].kind)} ${all[0].name}"
            val prefixAll = all.groupBy { prefixOf(it.trip.tripId) }.mapValues { e -> mostCommon(e.value.map { it.trip.headsign }) }
            val prefixToday = today.groupBy { prefixOf(it.trip.tripId) }.mapValues { e -> mostCommon(e.value.map { it.trip.headsign }) }
            for (x in today) {
                trips++
                val po = ride(x.trip, chosen(x.trip, oldDirs[route]!!), pOld); val pn = ride(x.trip, chosen(x.trip, newDirs), pNew)
                if (po && !pn) worse.merge("$who → ${x.trip.headsign}", 1, Int::plus)
                // As an identified vehicle: named by its trip id's prefix, as GtfsRepository.getHeadsignByTripIdPrefix.
                val hsOld = prefixAll[prefixOf(x.trip.tripId)] ?: x.trip.headsign
                val hsNew = prefixToday[prefixOf(x.trip.tripId)] ?: hsOld
                val vo = ride(x.trip, oldFor(all, hsOld), vOld)
                // Its own variant, by the trip id's prefix (getStopsForTripVariant), then its headsign (JourneyService.adoptVehicle).
                val vn = ride(x.trip, RouteOfTheDay.routeOfVariant(todays, prefixOf(x.trip.tripId))
                    ?: RouteOfTheDay.routeFor(todays, hsNew) ?: oldFor(all, hsNew), vNew)
                if (vo && !vn) vWorse.merge("$who → ${x.trip.headsign}", 1, Int::plus)
                // Followed by the direction it runs along, then identified: "Посоката е коригирана" (and the stop
                // to get off at cleared) when the two differ by name and — now — by way (JourneyService.adoptVehicle).
                // Of the trips followed by a direction under both rules, so that more trips followed is not counted against.
                val dOld = oldDirs[route]!!.indexOfFirst { matched(x.trip, it) != null }
                val dNew = newDirs.indexOfFirst { matched(x.trip, it) != null }
                if (dOld >= 0 && dNew >= 0) {
                    if (!oldDirs[route]!![dOld].headsign.equals(hsOld, ignoreCase = true)) falseOld++
                    if (!newNames[dNew].equals(hsNew, ignoreCase = true)) { falseNew++; falseNewBy.merge("$who → ${x.trip.headsign}", 1, Int::plus) }
                }
            }
        }
        println("$label, $date: $trips trips")
        println("  choosing a direction — before: $pOld; now: $pNew")
        println("  identified vehicle  — before: $vOld; now: $vNew")
        fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(12).joinToString("; ") { "${it.key} ${it.value}" }
        if (worse.isNotEmpty()) println("  right before, not now, choosing (${worse.values.sum()}): ${top(worse)}")
        if (vWorse.isNotEmpty()) println("  right before, not now, as a vehicle (${vWorse.values.sum()}): ${top(vWorse)}")
        println("  direction said to change on identifying the vehicle (trips followed by a direction under both rules) — before: $falseOld; now: $falseNew" +
            (if (falseNewBy.isEmpty()) "" else " (${top(falseNewBy)})"))
        check("$label, $date: more trips right choosing a direction than before", pNew.right >= pOld.right) { "${pNew.right} < ${pOld.right}" }
        check("$label, $date: more trips right as an identified vehicle than before", vNew.right >= vOld.right) { "${vNew.right} < ${vOld.right}" }
        check("$label, $date: fewer trips on wrong stops, both ways", pNew.wrong <= pOld.wrong && vNew.wrong <= vOld.wrong)
        check("$label, $date: no more stops said but not called", pNew.said <= pOld.said && vNew.said <= vOld.said) {
            "choosing ${pNew.said} vs ${pOld.said}, as a vehicle ${vNew.said} vs ${vOld.said}" }
        check("$label, $date: fewer direction changes said on identifying the vehicle", falseNew <= falseOld) { "$falseNew vs $falseOld" }
        println("  lines whose two directions do not run opposite ways — before: $notOppositeOld; now: ${notOppositeNew.size} ${notOppositeNew.take(8)}")
        check("$label, $date: no more lines whose two directions do not run opposite ways", notOppositeNew.size <= notOppositeOld)
    }
    finish()
}

fun finish() {
    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
