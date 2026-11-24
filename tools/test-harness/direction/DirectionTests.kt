// A line's direction at a stop (data/repository/RouteOfTheDay.kt: directionKey, routeThrough, scheduleAt,
// otherDirections) — the board's options and the Lines screen (the owner, 24 Nov 2026): on made-up trips, and on a
// whole static feed (route/route_extract.py), day by day:
//  - every departure from a stop (a trip at a stop but its last) shown in the Lines screen: at a stop of one of the
//    directions it lists — the two of before and, under "Други направления", the others running that day — in that
//    direction's timetable there (scheduleAt); before, the two directions' own trips, by the exact headsign, only;
//  - no trip of another direction put in a direction's timetable that runs the opposite way (RouteOfTheDay.opposite),
//    nor leaves the stop by another way than the direction's route through it;
//  - the board's route (routeThrough) of every line, direction and stop: a route of that direction calling there;
//  - 111 at МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ (A0169): towards Ж.К. ЛЮЛИН-1,2 its own trips and the short runs to БУЛ. НИКОЛА
//    ПЕТКОВ, marked; towards БУЛ. НИКОЛА ПЕТКОВ, its 22 stops and none of the full runs.
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.data.repository.RouteOfTheDay.Stop
import bg.sofia.transit.data.repository.RouteOfTheDay.TodaysTrip
import java.io.File

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n ${detail()}") }
}

class FeedTrip(val route: String, val name: String, val kind: String, val trip: TodaysTrip, val service: String)

/** [stops] each a stop of [route] by id, in order. */
fun alongIds(stops: List<String>, route: List<String>): Boolean {
    var i = 0
    for (x in route) { if (i == stops.size) break; if (stops[i] == x) i++ }
    return i == stops.size
}

fun main(args: Array<String>) {
    fun s(id: String, y: Double, name: String = id.uppercase()) = Stop(id, 42.7 + y / 111_195.0, 23.3, name)
    fun t(id: String, hs: String, vararg st: Stop) = TodaysTrip(id, hs, st.toList(), st.indices.map { 36_000 + 60 * it })
    val a = s("a", 0.0); val b = s("b", 400.0); val c = s("c", 800.0); val d = s("d", 1200.0); val e = s("e", 1600.0)
    val f = s("f", 2000.0)

    // Made up.
    check("one direction whatever its letters' case and spaces",
        RouteOfTheDay.directionKey("Кв.  Орландовци ") == RouteOfTheDay.directionKey("КВ. ОРЛАНДОВЦИ") &&
            RouteOfTheDay.directionKey(null) == "")
    run {
        val full = t("1", "F", a, b, c, d, e, f); val full2 = t("2", "F", a, b, c, d, e, f)
        val short = t("3", "Short", a, b, c, s("d2", 1210.0, "D"))          // ends at another pole of d
        val shortOn = t("4", "Short2", a, b, c, d)                         // ends at d itself
        val beyond = t("5", "Beyond", b, c, s("x", 900.0))                 // leaves the way after c
        val oneOff = t("6", "Loop", c, s("y", 700.0, "Y"))                 // from c straight to a stop not the way's
        val oneSame = t("7", "OneSame", c, s("d3", 1190.0, "D"))           // from c straight to another pole of d
        val endsAtC = t("8", "EndsAtC", a, b, c)                           // arrives at c, leaves from nowhere there
        val variant = t("9", "f", a, b, s("z", 600.0), c, d)               // the direction's own, another way
        val back = t("10", "Back", f, e, d, c, b, a)
        val trips = listOf(full, full2, short, shortOn, beyond, oneOff, oneSame, endsAtC, variant, back)
        val cs = RouteOfTheDay.scheduleAt(trips, "F", "c")
        val ids = cs.map { it.trip.tripId to it.endsAt }.toSet()
        check("the direction's own trips at the stop, whatever the spelling, unmarked",
            ("1" to null) in ids && ("2" to null) in ids && ("9" to null) in ids)
        check("short runs leaving the stop the direction's way: marked with where they end — at another pole, or on the way",
            ("3" to "Short") in ids && ("4" to "Short2") in ids)
        check("from the stop straight to another pole of the way's next stop: the way", ("7" to "OneSame") in ids)
        check("not a trip leaving by another way, nor one going from the stop straight elsewhere, nor one ending there, nor the way back",
            cs.none { it.trip.tripId in setOf("5", "6", "8", "10") }) { ids.toString() }
        check("each call at the stop's index in its trip", cs.all { it.trip.stops[it.at].id == "c" })
        check("a direction not calling at the stop that day: nothing", RouteOfTheDay.scheduleAt(trips, "F", "y").isEmpty())
        val sAtC = RouteOfTheDay.scheduleAt(trips, "Short", "c")
        check("a short run's own timetable: not the full runs, which go on past its end; the trips ending where it does, marked",
            sAtC.map { it.trip.tripId }.toSet() == setOf("3", "4", "7", "9") && sAtC.single { it.trip.tripId == "3" }.endsAt == null) {
            sAtC.map { it.trip.tripId }.toString() }
    }
    run {
        val main = t("1", "E", a, b, c, d, e); val main2 = t("2", "E", a, b, c, d, e)
        val viaX = t("3", "E", a, b, s("x", 500.0), d, e)                     // one run goes by x, not c
        check("the route through a stop: the direction's busiest", RouteOfTheDay.routeThrough(listOf(main, main2, viaX), "e", setOf("c")) === main)
        check("the route through a stop only one run of the direction calls at: that run, though not the busiest",
            RouteOfTheDay.routeThrough(listOf(main, main2, viaX), "E", setOf("x")) === viaX)
        check("no run of the direction calls there: none", RouteOfTheDay.routeThrough(listOf(main), "E", setOf("q")) == null)
        check("by any of the stop's poles", RouteOfTheDay.routeThrough(listOf(main), "E", setOf("q", "b")) === main)
    }
    run {
        val trips = listOf(t("1", "A", a, b), t("2", "A", a, b), t("3", "B", b, a), t("4", "Rare", a, c),
            t("5", "rare ", a, c), t("6", "Once", a, d), t("7", "", a, e), t("8", "Rare", a, c))
        val o = RouteOfTheDay.otherDirections(trips, listOf("a", "B"))
        check("the other directions: not those shown, whatever the spelling; the busiest first; none without a headsign",
            o.map { RouteOfTheDay.directionKey(it.headsign) } == listOf("RARE", "ONCE")) { o.map { it.headsign }.toString() }
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
            feed += FeedTrip(p[1], p[2], p[3], TodaysTrip(p[4], p[5],
                st.map { Stop(it[0], it[1].toDouble(), it[2].toDouble(), it.getOrElse(5) { "" }) }, st.map { it[3].toInt() }), p[6])
        }
    }
    val byRoute = feed.groupBy { it.route }
    for ((date, services) in days) {
        var departures = 0; var shownBefore = 0; var shownNow = 0; var marked = 0; var opposite = 0; var offWay = 0
        var routes = 0; var routeWrong = 0; var lines = 0; var withOthers = 0; var others = 0
        val hidden = HashMap<String, Int>(); val wrongBy = HashMap<String, Int>()
        for ((route, all) in byRoute) {
            val today = all.filter { it.service in services }.map { it.trip }
            if (today.isEmpty()) continue
            lines++
            val ranking = all.filter { it.trip.headsign.isNotEmpty() }.groupingBy { it.trip.headsign }.eachCount()
                .entries.sortedWith(compareBy({ -it.value }, { it.key })).map { it.key }
            // The two of before: as getDirectionsForRoute (with none of them today, the two busiest of all, by most stops).
            val named = RouteOfTheDay.directions(today, ranking)
            val two = if (named.isNotEmpty()) named.map { it.second } else ranking.take(2).mapNotNull { hs ->
                all.filter { it.trip.headsign == hs }.maxByOrNull { it.trip.stops.size }?.trip }
            val more = RouteOfTheDay.otherDirections(today, two.map { it.headsign })
            if (more.isNotEmpty()) withOthers++
            others += more.size
            val who = "${all[0].name}"
            // What the Lines screen shows: (trip, index) of each call in a listed direction's timetable at its stops.
            val seenNow = HashSet<Pair<String, Int>>(); val seenBefore = HashSet<Pair<String, Int>>()
            for (dir in two + more) {
                for (stop in dir.stops.map { it.id }.distinct()) {
                    val calls = RouteOfTheDay.scheduleAt(today, dir.headsign, stop)
                    for (cl in calls) {
                        seenNow += cl.trip.tripId to cl.at
                        if (cl.endsAt == null) continue
                        marked++
                        val way = RouteOfTheDay.routeThrough(today, dir.headsign, setOf(stop))!!
                        val from = way.stops.indexOfFirst { it.id == stop }
                        val rest = cl.trip.stops.subList(cl.at, cl.trip.stops.lastIndex).map { it.id }
                        if (RouteOfTheDay.opposite(cl.trip, way)) { opposite++; wrongBy.merge("$who → ${cl.trip.headsign} in ${dir.headsign}", 1, Int::plus) }
                        if (!alongIds(rest, way.stops.subList(from, way.stops.size).map { it.id })) offWay++
                    }
                }
                if (dir in two) for (x in today) if (x.headsign == dir.headsign)
                    x.stops.forEachIndexed { i, st -> if (dir.stops.any { it.id == st.id }) seenBefore += x.tripId to i }
            }
            for (x in today) for (i in 0 until x.stops.lastIndex) {
                departures++
                if ((x.tripId to i) in seenBefore) shownBefore++
                if ((x.tripId to i) in seenNow) shownNow++ else hidden.merge("$who → ${x.headsign}", 1, Int::plus)
            }
            // The board: every line, direction and stop of the day.
            for ((key, own) in today.groupBy { RouteOfTheDay.directionKey(it.headsign) }) {
                if (key.isEmpty()) continue
                for (stop in own.flatMap { x -> x.stops.map { it.id } }.toSet()) {
                    routes++
                    val r = RouteOfTheDay.routeThrough(today, own[0].headsign, setOf(stop))
                    if (r == null || r.stops.none { it.id == stop } || RouteOfTheDay.directionKey(r.headsign) != key) routeWrong++
                }
            }
            // 111 at МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ.
            if (route == "A3" && today.any { x -> x.headsign == "БУЛ. НИКОЛА ПЕТКОВ" && x.stops.any { it.id == "A0169" } }) {
                val toLyulin = RouteOfTheDay.scheduleAt(today, "Ж.К. ЛЮЛИН-1,2", "A0169")
                val shortRuns = toLyulin.filter { it.endsAt == "БУЛ. НИКОЛА ПЕТКОВ" }
                val toPetkov = RouteOfTheDay.scheduleAt(today, "бул. Никола Петков", "A0169")
                val petkovRoute = RouteOfTheDay.routeThrough(today, "БУЛ. НИКОЛА ПЕТКОВ", setOf("A0169", "TB0169"))
                println("  111 at A0169: towards Ж.К. ЛЮЛИН-1,2 ${toLyulin.size - shortRuns.size} own + ${shortRuns.size} to БУЛ. НИКОЛА ПЕТКОВ; " +
                    "towards БУЛ. НИКОЛА ПЕТКОВ ${toPetkov.size}, its route ${petkovRoute?.stops?.size} stops to ${petkovRoute?.stops?.last()?.name}")
                check("$label, $date: 111 at АЛ. МАЛИНОВ towards ЛЮЛИН-1,2 — the short runs to НИКОЛА ПЕТКОВ, marked",
                    shortRuns.isNotEmpty() && shortRuns.size == today.count { x -> x.headsign == "БУЛ. НИКОЛА ПЕТКОВ" && x.stops.dropLast(1).any { it.id == "A0169" } })
                check("$label, $date: 111 at АЛ. МАЛИНОВ towards НИКОЛА ПЕТКОВ — its own only, its route ending there",
                    toPetkov.isNotEmpty() && toPetkov.all { it.endsAt == null } && petkovRoute?.stops?.last()?.name == "БУЛ. НИКОЛА ПЕТКОВ")
            }
        }
        fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(12).joinToString("; ") { "${it.key} ${it.value}" }
        fun pct(n: Int) = "%.2f %%".format(100.0 * n / departures)
        println("$label, $date: $lines lines, $withOthers with other directions ($others)")
        println("  departures from stops $departures; shown in the Lines screen before ${shownBefore} (${pct(departures - shownBefore)} not), " +
            "now $shownNow (${pct(departures - shownNow)} not; $marked calls marked with where they end)")
        if (hidden.isNotEmpty()) println("  not shown now (${hidden.values.sum()}): ${top(hidden)}")
        check("$label, $date: more departures shown than before", shownNow > shownBefore)
        check("$label, $date: under 0.5 % of departures not shown", (departures - shownNow) * 200 < departures) { pct(departures - shownNow) }
        check("$label, $date: no trip of the way back in a direction's timetable", opposite == 0) { top(wrongBy) }
        check("$label, $date: no trip leaving by another way in a direction's timetable", offWay == 0) { "$offWay" }
        check("$label, $date: the board's route of every line, direction and stop ($routes) calls there, of that direction", routeWrong == 0) { "$routeWrong" }
    }
    finish()
}

fun finish() {
    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
