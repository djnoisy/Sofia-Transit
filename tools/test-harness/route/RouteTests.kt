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
// The way on from the stop we boarded at, by the timetable (RouteOfTheDay.wayOn, boardedAt; step 2): every stop of
// every trip running that day boarded once, the metro aside, departing on time, 20 min late and 5 min early — the
// window's edges — and on time with where we waited 25 m off the stop, four ways: never worse than the direction's
// route followed; reported only, beyond the window, where the trip taken ends before the bus does ("Крайна спирка"
// said too early). And a reversal on identifying a vehicle (JourneyService.adoptVehicle: RouteOfTheDay.opposite of the
// order followed and the vehicle's): never a trip running the way followed, nor its variant's route.
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

fun opposite(a: TodaysTrip, b: TodaysTrip) = RouteOfTheDay.opposite(a, b)

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
    run {
        val there = t("t", "E", a, b, c, d, e); val short = t("s", "C", a, b, c)
        val back = t("r", "A", s("e'", 1600.0), s("d'", 1200.0), s("c'", 800.0), s("b'", 400.0), s("a'", 0.0))
        check("a reversal: the way back is one, a short run the same way is not (111 to БУЛ. НИКОЛА ПЕТКОВ), nor two stops back",
            RouteOfTheDay.opposite(there, back) && !RouteOfTheDay.opposite(there, short) &&
            !RouteOfTheDay.opposite(there, t("2", "A", s("c'", 800.0), s("b'", 400.0))))
    }

    // The way on by the timetable.
    run {
        fun tt(id: String, at0: Int, vararg st: Stop) = TodaysTrip(id, "E", st.toList(), st.indices.map { at0 + it * 120 })
        val full1 = tt("2", 36_000, a, b, c, d, e); val full2 = tt("1", 36_600, a, b, c, d, e)
        val short = tt("3", 36_300, a, b, c)                         // ends at c
        check("all due go on the same way: the first by trip id, where we boarded",
            RouteOfTheDay.wayOn(listOf(full1, full2), setOf("b"), 36_500).let { it?.first === full2 && it.second == 1 })
        check("a short run and a full one due: different ways — none (111)",
            RouteOfTheDay.wayOn(listOf(full1, short), setOf("b"), 36_300) == null)
        check("the window: due from 20 min before our departure to 5 min after, no further",
            RouteOfTheDay.wayOn(listOf(full1), setOf("a"), 36_000 + 20 * 60) != null &&
            RouteOfTheDay.wayOn(listOf(full1), setOf("a"), 36_000 + 20 * 60 + 1) == null &&
            RouteOfTheDay.wayOn(listOf(full1), setOf("a"), 36_000 - 5 * 60) != null &&
            RouteOfTheDay.wayOn(listOf(full1), setOf("a"), 36_000 - 5 * 60 - 1) == null)
        check("a run ending at the stop has no way on: the one going on decides",
            RouteOfTheDay.wayOn(listOf(short, full1), setOf("c"), 36_300).let { it?.first === full1 && it.second == 2 })
        check("due at another id of the pole: counted",
            RouteOfTheDay.wayOn(listOf(full1, short), setOf("x", "b"), 36_300) == null &&
            RouteOfTheDay.wayOn(listOf(full1), setOf("x", "b"), 36_300)?.second == 1)
        check("a trip without times: not due", RouteOfTheDay.wayOn(listOf(t("n", "E", a, b, c)), setOf("a"), 36_000) == null)
        check("times: H:MM:SS, past midnight; anything else not a time",
            RouteOfTheDay.secondsOf("5:07:09") == 18_429 && RouteOfTheDay.secondsOf("24:30:00") == 88_200 &&
            RouteOfTheDay.secondsOf("") == -1 && RouteOfTheDay.secondsOf(null) == -1 && RouteOfTheDay.secondsOf("7:60:00") == -1)
    }
    run {
        // Where we waited, against the stops: the nearest within 60 m.
        fun nearTo(w: Stop): (List<Stop>) -> Int? = { st -> st.indices.minByOrNull { dist(w, st[it]) }?.takeIf { dist(w, st[it]) <= 60.0 } }
        fun east(s: Stop, m: Double) = Stop(s.id + "'", s.lat, s.lon + m / (111_195.0 * Math.cos(Math.toRadians(s.lat))))
        val there = t("t", "E", a, b, c, d, e)
        val depot = t("v", "D", s("p", -1200.0), s("q", -800.0), s("r", -400.0), a, b)   // from the depot, joining at a
        val back = t("r", "A", east(e, 30.0), east(d, 30.0), east(c, 30.0), east(b, 30.0), east(a, 30.0))
        check("boarded at a stop of the order followed: that one, by its index",
            RouteOfTheDay.boardedAt(there, listOf(there, depot, back), nearTo(c))?.let { it.first.id == "c" && it.second == 2 } == true)
        check("boarded off it, at a stop of another route of the line its way (the depot run): that one",
            RouteOfTheDay.boardedAt(there, listOf(there, depot, back), nearTo(s("q", -800.0)))?.let { it.first.id == "q" && it.second == -1 } == true)
        val pastEnd = east(s("z", 2000.0), 30.0)
        val backLonger = t("r2", "A", pastEnd, east(e, 30.0), east(d, 30.0), east(c, 30.0), east(b, 30.0), east(a, 30.0))
        check("the way back's stops are never taken: where we waited near only one of them, none",
            RouteOfTheDay.boardedAt(there, listOf(there, backLonger), nearTo(pastEnd)) == null)
    }

    // A whole feed, day by day.
    val file = args.getOrNull(0)?.let { File(it) }
    if (file == null || !file.isFile) { println("(no feed extract)"); return finish() }
    val label = args.getOrNull(1) ?: ""
    val days = ArrayList<Pair<String, Set<String>>>(); val feed = ArrayList<FeedTrip>()
    file.forEachLine { l -> val p = l.split("|")
        if (p[0] == "D") days += p[1] to (if (p[2].isEmpty()) emptySet() else p[2].split(",").toSet())
        else {
            val st = p[7].split(";").map { it.split(":") }
            st.forEach { codes[it[0]] = it[4] }
            feed += FeedTrip(p[1], p[2], p[3], TodaysTrip(p[4], p[5], st.map { Stop(it[0], it[1].toDouble(), it[2].toDouble()) },
                st.map { it[3].toInt() }), p[6])
        }
    }
    codes.forEach { (id, code) -> poleIds.getOrPut(pole(id)) { HashSet() } += id }
    val byRoute = feed.groupBy { it.route }
    val oldDirs = byRoute.mapValues { oldDirections(it.value) }
    for ((date, services) in days) {
        val pOld = Tally(); val pNew = Tally(); val vOld = Tally(); val vNew = Tally()
        val worse = HashMap<String, Int>(); val vWorse = HashMap<String, Int>()
        var trips = 0; var falseOld = 0; var falseNew = 0; val falseNewBy = HashMap<String, Int>()
        val notOppositeNew = ArrayList<String>(); var notOppositeOld = 0
        val firstDay = date == days.first().first
        var falseReversals = 0; val falseReversalBy = HashMap<String, Int>()
        val boardings = LinkedHashMap<String, Boarding>()
        for (dl in listOf(0, 20 * 60, -5 * 60) + (if (firstDay) listOf(25 * 60, -6 * 60) else emptyList()))
            boardings["departing ${dl / 60} min late"] = Boarding(dl, null)
        if (firstDay) for ((n, m) in listOf("north" to (25.0 to 0.0), "east" to (0.0 to 25.0), "south" to (-25.0 to 0.0), "west" to (0.0 to -25.0)))
            boardings["on time, where we waited 25 m $n of the stop"] = Boarding(0, m)
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
            if (all[0].kind != "1") board(today.map { it.trip }, newDirs, boardings.values)
            // Identifying a vehicle that runs the way followed: never a reversal (adoptVehicle), by its trip or its variant's route.
            for (x in today) {
                val d = newDirs.firstOrNull { matched(x.trip, it) != null } ?: continue
                val v = RouteOfTheDay.routeOfVariant(todays, prefixOf(x.trip.tripId)) ?: x.trip
                if (opposite(d, x.trip) || (matched(x.trip, v) != null && opposite(d, v))) {
                    falseReversals++; falseReversalBy.merge("$who → ${x.trip.headsign}", 1, Int::plus)
                }
            }
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
        println("  a vehicle running the way followed taken for a reversal: $falseReversals" +
            (if (falseReversalBy.isEmpty()) "" else " (${top(falseReversalBy)})"))
        check("$label, $date: no vehicle running the way followed is taken for a reversal", falseReversals == 0)
        println("  the way on by the timetable, boarding at every stop (the metro aside) — on wrong stops / the run's end not known, now -> by the timetable:")
        for ((name, b) in boardings) {
            println("    $name: ${b.n} boardings; ${b.now[2]} -> ${b.new[2]} / ${b.now[1]} -> ${b.new[1]}; worse ${b.worse}" +
                (if (b.worseBy.isEmpty()) "" else " (${top(b.worseBy)})") + "; the trip taken ends before the bus ${b.endsEarly}")
            if (b.lateS in -5 * 60..20 * 60) {
                check("$label, $date, $name: never worse than the direction's route", b.worse == 0)
                check("$label, $date, $name: fewer on wrong stops, fewer not knowing the run's end", b.new[2] <= b.now[2] && b.new[1] <= b.now[1])
            }
        }
    }
    finish()
}

/** Stop codes by stop id, and the ids of each pole (one code; RouteOfTheDay.wayOn's poles, GtfsRepository.stopIdsOfSamePlace). */
val codes = HashMap<String, String>()
val poleIds = HashMap<String, MutableSet<String>>()
fun pole(id: String) = codes[id]?.takeIf { it.isNotEmpty() } ?: id

/** Boardings at every stop, departing [lateS] after the timetable, where we waited [waitedOff] m (north, east) off the stop. */
class Boarding(val lateS: Int, val waitedOff: Pair<Double, Double>?) {
    var n = 0; var worse = 0; var endsEarly = 0; val now = IntArray(3); val new = IntArray(3); val worseBy = HashMap<String, Int>()
}

/** The stops after [s] — by its pole — of [route]; null when it does not call there. */
fun restOf(route: List<String>, s: String): List<String>? {
    val p = pole(s); val j = route.indexOfFirst { pole(it) == p }
    return if (j < 0) null else route.subList(j + 1, route.size)
}
fun alongIds(t: List<String>, r: List<String>): Boolean { var i = 0; for (x in r) { if (i == t.size) break; if (t[i] == x) i++ }; return i == t.size }
/** The stops after boarding, [rem], heard by [followed]: 0 right, 1 right but going on past the end of the run, 2 wrong stops. */
fun judge(rem: List<String>, followed: List<String>?): Int = when {
    followed == null || !alongIds(rem, followed) -> 2
    rem.isEmpty() || followed.indexOf(rem.last()) < followed.lastIndex -> 1
    else -> 0
}
fun stopsMatched(t: TodaysTrip, r: TodaysTrip): Int { var j = 0; var n = 0
    for (s in t.stops) { var k = j; while (k < r.stops.size && r.stops[k].id != s.id) k++; if (k < r.stops.size) { n++; j = k + 1 } }; return n }

/**
 * One line's trips running that day, each boarded at each of its stops but the last: the direction the passenger
 * follows — the one it runs along, else the nearest by its stops — as now, and with the way on by the timetable
 * (RouteOfTheDay.boardedAt, wayOn, as JourneyService.followTimetableWay).
 */
fun board(todays: List<TodaysTrip>, dirs: List<TodaysTrip>, runs: Collection<Boarding>) {
    if (dirs.isEmpty()) return
    // Calls at each pole by time, so that wayOn is asked about the trips due around then only — it finds the same.
    val calls = HashMap<String, MutableList<Pair<Int, TodaysTrip>>>()
    for (x in todays) for (i in 0 until x.stops.lastIndex) if (x.times[i] >= 0) calls.getOrPut(pole(x.stops[i].id)) { ArrayList() } += x.times[i] to x
    calls.values.forEach { l -> l.sortBy { it.first } }
    fun dueAround(p: String, dep: Int): List<TodaysTrip> {
        val l = calls[p] ?: return emptyList()
        var lo = 0; var hi = l.size
        while (lo < hi) { val m = (lo + hi) / 2; if (l[m].first < dep - RouteOfTheDay.DUE_BEFORE_S) lo = m + 1 else hi = m }
        val out = LinkedHashSet<TodaysTrip>()
        while (lo < l.size && l[lo].first <= dep + RouteOfTheDay.DUE_AFTER_S) out += l[lo++].second
        return out.toList()
    }
    // Only trips that run the direction's way decide (as JourneyService.followTimetableWay).
    val thisWay = dirs.associateWith { RouteOfTheDay.thisWay(todays, it).toHashSet() }
    for (x in todays) {
        val followed = dirs.firstOrNull { matched(x, it) != null } ?: dirs.maxByOrNull { stopsMatched(x, it) }!!
        val fIds = followed.stops.map { it.id }
        val ours = thisWay[followed]!!
        for (i in 0 until x.stops.lastIndex) {
            if (x.times[i] < 0) continue
            val s = x.stops[i]; val rem = x.stops.subList(i + 1, x.stops.size).map { it.id }
            val now = judge(rem, restOf(fIds, s.id))
            for (b in runs) {
                val w = b.waitedOff?.let { (n, e) -> Stop("w", s.lat + n / 111_195.0, s.lon + e / (111_195.0 * Math.cos(Math.toRadians(s.lat)))) } ?: s
                val near: (List<Stop>) -> Int? = { st -> st.indices.minByOrNull { dist(w, st[it]) }?.takeIf { dist(w, st[it]) <= 60.0 } }
                val dep = x.times[i] + b.lateS
                var early = false
                val new = RouteOfTheDay.boardedAt(followed, todays, near)?.let { (at, idx) ->
                    val p = pole(at.id)
                    RouteOfTheDay.wayOn(dueAround(p, dep).filter { it in ours }, poleIds[p] ?: setOf(at.id), dep)?.let { (trip, k) ->
                        val way = trip.stops.subList(k + 1, trip.stops.size).map { it.id }
                        if (idx >= 0 && fIds.subList(idx + 1, fIds.size) == way) null else {
                            // Its last stop one the bus calls at before its own last: tracking ends there.
                            early = way.isNotEmpty() && rem.indexOf(way.last()).let { it in 0 until rem.lastIndex }
                            judge(rem, way)
                        }
                    }
                } ?: now
                b.n++; b.now[now]++; b.new[new]++
                if (early) b.endsEarly++
                if (new > now) { b.worse++; b.worseBy.merge(x.trip(), 1, Int::plus) }
            }
        }
    }
}
fun TodaysTrip.trip() = tripId.split("-").take(2).joinToString("-")

fun finish() {
    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
