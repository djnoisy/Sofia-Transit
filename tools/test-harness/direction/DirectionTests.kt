// A line's direction at a stop (data/repository/RouteOfTheDay.kt: directionKey, routeThrough, scheduleAt,
// otherDirections) — the board's options and the Lines screen (the owner, 24 Nov 2026): on made-up trips, and on a
// whole static feed (route/route_extract.py), day by day:
//  - every departure from a stop (a trip at a stop but its last) shown in the Lines screen: at a stop of one of the
//    routes it lists — the two directions of before and, under "Други направления", the others running that day and
//    the further routes each needs (othersListed) — in that direction's timetable there (scheduleAt); before, the two
//    directions' own trips, by the exact headsign, only;
//  - no trip of another direction put in a direction's timetable that runs the opposite way (RouteOfTheDay.opposite),
//    nor leaves the stop by another way than the direction's route through it — judged by place: its next stop
//    within OFF_WAY_M of one of the route's on from the stop;
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

fun dist(a: Stop, b: Stop) = Math.hypot((a.lat - b.lat) * 111_195.0, (a.lon - b.lon) * 111_195.0 * Math.cos(Math.toRadians(a.lat)))
/** A trip leaving a stop the way's way: its next stop is this near one of the way's next ones at most (another pole of a terminus). */
const val OFF_WAY_M = 400.0

class FeedTrip(val route: String, val name: String, val kind: String, val trip: TodaysTrip, val service: String)

fun main(args: Array<String>) {
    fun s(id: String, y: Double, name: String = id.uppercase()) = Stop(id, 42.7 + y / 111_195.0, 23.3, name)
    fun t(id: String, hs: String, vararg st: Stop) = TodaysTrip(id, hs, st.toList(), st.indices.map { 36_000 + 60 * it })
    val a = s("a", 0.0); val b = s("b", 400.0); val c = s("c", 800.0); val d = s("d", 1200.0); val e = s("e", 1600.0)
    val f = s("f", 2000.0)

    // Made up.
    check("one direction whatever its letters' case and the marks and spaces between its words",
        RouteOfTheDay.directionKey("Кв.  Орландовци ") == RouteOfTheDay.directionKey("КВ. ОРЛАНДОВЦИ") &&
            RouteOfTheDay.directionKey("Ж.к. Дружба 2") == RouteOfTheDay.directionKey("Ж.К. ДРУЖБА-2") &&
            RouteOfTheDay.directionKey("Бул.Проф.Цв.Лазаров") == RouteOfTheDay.directionKey("БУЛ. ПРОФ.ЦВ. ЛАЗАРОВ") &&
            RouteOfTheDay.directionKey(null) == "")
    check("but not another: the digits stay apart",
        RouteOfTheDay.directionKey("Ж.К. ЛЮЛИН-1,2") != RouteOfTheDay.directionKey("Ж.К. ЛЮЛИН-12"))
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
        // The board's row named by one day's spelling, the day's trips by another (trolley 4, data of 5 Oct 2026).
        val today = listOf(t("1", "Ж.к. Дружба 2", a, b, c), t("2", "Ж.к. Дружба 2", a, b, c))
        check("the board's row in another spelling than the day's trips: their route, their timetable",
            RouteOfTheDay.routeThrough(today, "Ж.К. ДРУЖБА-2", setOf("b")) != null &&
                RouteOfTheDay.scheduleAt(today, "Ж.К. ДРУЖБА-2", "b").size == 2)
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
        check("the other directions: not those shown, whatever the spelling; the busiest first; trips without a headsign one too",
            o.map { RouteOfTheDay.directionKey(it.headsign) } == listOf("RARE", "", "ONCE")) { o.map { it.headsign }.toString() }
    }

    run {
        // A direction whose runs from the depot call at stops its route does not (tram 22 to ГАРА ПОДУЯНЕ), one whose
        // runs leave the way and come back to it (44 through МАХАЛА ИВАНЯНЕ), and a direction not shown.
        val p = s("p", -800.0); val q = s("q", -400.0); val x = s("x", 600.0)
        val trips = listOf(t("1", "Main", a, b, c, d), t("2", "Main", a, b, c, d), t("3", "Main", p, q, b, c, d),
            t("4", "Back", d, c, b, a), t("5", "Back", d, c, b, a), t("6", "Back", d, x, b, a),
            t("7", "Rare", a, b), t("8", "Rare", e, f, a, b))
        val main = listOf(trips[0], trips[3])
        val l = RouteOfTheDay.othersListed(trips, main)
        fun ids(x: RouteOfTheDay.Listed) = x.route.tripId
        check("under Други направления: the main directions' further routes, by where they start or a stop on the way; then the others, each followed by its own",
            l.map { ids(it) to (it.by?.id to it.fromStart) } ==
                listOf("3" to ("p" to true), "6" to ("x" to false), "8" to (null to false))) {
            l.map { "${ids(it)} ${it.by?.id} ${it.fromStart}" }.toString() }
        check("a direction's runs all along its route: nothing more",
            RouteOfTheDay.othersListed(trips.take(2), listOf(trips[0])).isEmpty())
        check("told apart as said: from where it starts, through a stop on the way",
            l[0].label == "от P" && l[1].label == "през X" && l[2].label == null) { l.map { it.label }.toString() }
        // Runs from another pole of a stop the direction's route has (tram 22 to ДЕПО ИСКЪР on weekends): by its code too.
        val pole = Stop("b2", b.lat, b.lon + 0.001, "B", "0470")
        val pl = RouteOfTheDay.othersListed(listOf(t("1", "Main", a, b, c, d), t("2", "Main", pole, c, d)), listOf(t("1", "Main", a, b, c, d)))
        check("from another pole of a stop listed: its code said", pl.singleOrNull()?.label == "от B, код 0470") { pl.map { it.label }.toString() }
        // A trip without a headsign running a direction's way: marked with where it ends, by its last stop's name.
        val nameless = RouteOfTheDay.scheduleAt(listOf(t("1", "Main", a, b, c, d), t("2", "", a, b, c)), "Main", "b")
        check("a short run without a headsign: marked with its last stop", nameless.single { it.trip.tripId == "2" }.endsAt == "C")
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
                st.map { Stop(it[0], it[1].toDouble(), it[2].toDouble(), it.getOrElse(5) { "" }, it.getOrElse(4) { "" }) }, st.map { it[3].toInt() }), p[6])
        }
    }
    val byRoute = feed.groupBy { it.route }
    for ((date, services) in days) {
        var departures = 0; var shownBefore = 0; var shownNow = 0; var marked = 0; var opposite = 0; var offWay = 0
        var routes = 0; var routeWrong = 0; var lines = 0; var withOthers = 0; var others = 0; var further = 0
        val furtherBy = ArrayList<String>(); val sameLabels = ArrayList<String>()
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
            // The routes the two directions' screens list (getStopsForDirection → directionTripId): the route of the
            // day by the exact headsign, else as getDirectionsForRoute took it.
            val screens = two.map { d -> RouteOfTheDay.routeFor(today, d.headsign) ?: d }
            val listed = RouteOfTheDay.othersListed(today, screens)
            val more = listed.map { it.route }
            if (more.isNotEmpty()) withOthers++
            others += listed.count { it.by == null }; further += listed.count { it.by != null }
            for (l in listed) if (l.by != null) furtherBy += "${all[0].name} ${l.route.headsign} (${l.label})"
            val names = (two.map { RouteOfTheDay.directionKey(it.headsign) } +
                listed.map { RouteOfTheDay.directionKey(it.route.headsign) + "|" + it.label })
            if (names.size != names.toSet().size) sameLabels += "${all[0].name}: $names"
            val who = "${all[0].name}"
            // What the Lines screen shows: (trip, index) of each call in a listed direction's timetable at its stops.
            val seenNow = HashSet<Pair<String, Int>>(); val seenBefore = HashSet<Pair<String, Int>>()
            for (dir in screens + more) {
                for (stop in dir.stops.map { it.id }.distinct()) {
                    val calls = RouteOfTheDay.scheduleAt(today, dir.headsign, stop)
                    for (cl in calls) {
                        seenNow += cl.trip.tripId to cl.at
                        if (cl.endsAt == null) continue
                        marked++
                        val way = RouteOfTheDay.routeThrough(today, dir.headsign, setOf(stop))!!
                        val from = way.stops.indexOfFirst { it.id == stop }
                        if (RouteOfTheDay.opposite(cl.trip, way)) { opposite++; wrongBy.merge("$who → ${cl.trip.headsign} in ${dir.headsign}", 1, Int::plus) }
                        // By place, not by the rule's ids and names: the trip's next stop near one of the way's on from here.
                        val next = cl.trip.stops[cl.at + 1]
                        val off = way.stops.subList(from + 1, way.stops.size).minOfOrNull { dist(next, it) } ?: Double.MAX_VALUE
                        if (off > OFF_WAY_M) { offWay++; wrongBy.merge("$who → ${cl.trip.headsign} in ${dir.headsign} off ${off.toInt()} m", 1, Int::plus) }
                    }
                }
                if (dir in screens) for (x in today) if (x.headsign == dir.headsign)
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
        println("$label, $date: $lines lines, $withOthers with routes under Други направления: $others other directions, $further further routes")
        if (furtherBy.isNotEmpty()) println("  further routes: ${furtherBy.joinToString("; ")}")
        println("  departures from stops $departures; shown in the Lines screen before ${shownBefore} (${pct(departures - shownBefore)} not), " +
            "now $shownNow (${pct(departures - shownNow)} not; $marked calls marked with where they end)")
        if (hidden.isNotEmpty()) println("  not shown now (${hidden.values.sum()}): ${top(hidden)}")
        check("$label, $date: more departures shown than before", shownNow > shownBefore)
        check("$label, $date: every departure from a stop shown", departures == shownNow) { "${departures - shownNow}: ${top(hidden)}" }
        check("$label, $date: no two routes of a line under one name", sameLabels.isEmpty()) { sameLabels.toString() }
        check("$label, $date: no trip of the way back in a direction's timetable", opposite == 0) { top(wrongBy) }
        check("$label, $date: no trip leaving by another way in a direction's timetable — its next stop within $OFF_WAY_M m of the way's on",
            offWay == 0) { "$offWay: ${top(wrongBy)}" }
        check("$label, $date: the board's route of every line, direction and stop ($routes) calls there, of that direction", routeWrong == 0) { "$routeWrong" }
    }
    finish()
}

fun finish() {
    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
