package bg.sofia.transit.data.repository

import kotlin.math.cos
import kotlin.math.hypot

/**
 * The route a line takes today towards a headsign — for the Lines screen
 * and for journey tracking alike, before a vehicle is identified and after
 * (GtfsRepository.getDirectionsForRoute, the stops of a direction or of a
 * vehicle's headsign).
 *
 * Not the fullest trip of all in the feed. The feed keeps diversions for
 * weeks after they end, and carries planned ones weeks ahead, and the
 * fullest trip was one of them more than once: on 28 Oct 2026 tram 3 to
 * Кв. Орландовци was followed by a diversion through ЦЕНТРАЛНА ГАРА due
 * from 7 Nov, tram 4 by one due from 4 Nov; in the data bundled on 5 Oct,
 * tram 22 to Ж.К. КРАСНА ПОЛЯНА by a route via Гео Милев run on 5-7 Oct,
 * 213, 305, 404 and 78 by one via УЛ. КЛОКОТНИЦА ended on 12 Oct.
 *
 * The directions themselves are named as before — the busiest headsigns
 * over all trips — of those running today. Named instead by today's own
 * trips, or by the routes they run along, they changed from day to day and
 * hour to hour (63's short runs outnumber its full ones on weekdays; tram 8
 * runs a short turn by day on Saturdays), and an identified vehicle's
 * headsign came to differ from the direction it was on (reviews of
 * 28 Oct 2026).
 *
 * And, before a vehicle is identified, the way on from the stop we boarded
 * at, by the timetable (wayOn).
 *
 * Pure Kotlin, so it can be tested on the feed itself
 * (tools/test-harness/run_route_tests.sh).
 */
object RouteOfTheDay {

    /** A stop: its id, where it is, and its name and code ("0470") where looked up (scheduleAt, othersListed). */
    class Stop(val id: String, val lat: Double, val lon: Double, val name: String = "", val code: String = "")

    /**
     * A trip that runs today: its headsign, trimmed, its stops in order, and
     * when it leaves each — seconds after midnight of today's service day
     * (secondsOf; -1 where not given), none when not looked up.
     */
    class TodaysTrip(val tripId: String, val headsign: String, val stops: List<Stop>, val times: List<Int> = emptyList())

    /**
     * The first two of [ranking] — the line's headsigns, busiest over all
     * its trips first — that run today, each with its route today
     * (routeFor). Empty when none of them runs today, or when the two do not
     * run the opposite ways (opposite): with one of the two not running
     * today, the next may be a short run of the other way, and the way back
     * would not be offered (review of 28 Oct 2026: never in 153 days of
     * feeds, 63 of 120 lines when made so) — the busiest of all, as before,
     * then.
     */
    fun directions(trips: List<TodaysTrip>, ranking: List<String>): List<Pair<String, TodaysTrip>> {
        val out = ranking.asSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .mapNotNull { hs -> routeFor(trips, hs)?.let { hs to it } }
            .take(2).toList()
        return if (out.size == 2 && !opposite(out[0].second, out[1].second)) emptyList() else out
    }

    /**
     * The trip whose route stands for [headsign] today: of today's trips
     * with that very headsign — compared exactly: CGM spells "ГАРА ПОДУЯНЕ"
     * for tram 22's runs from ДЕПО ИСКЪР and "Гара Подуяне" for those from
     * Автостанция Изток — the one the most of them run along (along): the
     * full run, of which today's short runs are parts (76 towards Гоце
     * Делчев runs trips of 31 stops and of 16, starting halfway at УМБАЛ Св.
     * Анна); of routes as busy, the one with most stops, then by trip id, so
     * that the choice does not change from one call to the next. Null when
     * none runs today.
     */
    fun routeFor(trips: List<TodaysTrip>, headsign: String): TodaysTrip? = busiest(trips.filter { it.headsign == headsign })

    /**
     * The route today of the variant [prefix] names — the first two parts
     * of a trip id, the line and its road shape, which the vehicle feed
     * carries: one route each, all but the metro's, on 28 Oct 2026. Exact
     * where the headsign is not: 111's short runs to БУЛ. НИКОЛА ПЕТКОВ start
     * from either end (A3-A2388- from Ж.К. ЛЮЛИН-1,2, A3-A1801- from Ж.К.
     * МЛАДОСТ-1), and by the headsign the one end's vehicles were followed by
     * the other's stops. Null when none runs today.
     */
    fun routeOfVariant(trips: List<TodaysTrip>, prefix: String): TodaysTrip? =
        busiest(trips.filter { it.tripId.startsWith(prefix) })

    /** Of [own], the route the most of them run along; see routeFor. */
    private fun busiest(own: List<TodaysTrip>): TodaysTrip? = heaviest(own.map { it to 1 })

    /**
     * Of [own] — trips, each standing for as many as its number — the route
     * the most of them run along (busiest, each trip counted as many times).
     */
    private fun heaviest(own: List<Pair<TodaysTrip, Int>>): TodaysTrip? =
        own.map { it.first }.sortedBy { it.tripId }.distinctBy { t -> t.stops.map { it.id } }
            .map { r -> r to own.sumOf { (t, n) -> if (along(t, r)) n else 0 } }
            .filter { it.second > 0 }
            .maxWithOrNull(compareBy<Pair<TodaysTrip, Int>> { it.second }
                .thenBy { it.first.stops.size }
                .thenByDescending { it.first.tripId })
            ?.first

    /**
     * Whether [trip] runs along [route]: each of its stops, in order, a stop
     * of the route, by its id. Not by place: two poles are two stops, however
     * near (the owner, 28 Oct 2026). So 63's short runs to БУЛ. ЦАР БОРИС ІІІ,
     * from УЛ. БЯЛАТА ЧЕШМА (1839), are not along its full runs from ЗЛАТНИ
     * МОСТОВЕ, which call at another pole 19 m off (6569), and on weekdays,
     * 13 of them to 9, the short run is the route: the full runs' first 7
     * stops are not known until the vehicle is identified. Of the 3119 routes
     * chosen over 9 days of the feed of 28 Oct 2026, every direction every
     * day, the only one that place (50 m) chose otherwise. In order, so that
     * the stops of the way back are not taken for the way there.
     */
    fun along(trip: TodaysTrip, route: TodaysTrip): Boolean = along(trip.stops, route.stops)

    /** [stops], each a stop of [route] by its id, in order (along). */
    private fun along(stops: List<Stop>, route: List<Stop>): Boolean {
        var i = 0
        for (s in route) {
            if (i == stops.size) break
            if (stops[i].id == s.id) i++
        }
        return i == stops.size
    }

    // ── A direction at a stop: the board's options and the Lines screen ──

    /**
     * The direction [headsign] names, whatever the case of its letters and the
     * marks and spaces between its words: the board shows the feed's
     * headsigns in capitals, single-spaced (RealtimeRepository), and CGM
     * spells some directions two ways — "Кв. Орландовци" and "КВ. ОРЛАНДОВЦИ"
     * for trams 3 and 4, "ГАРА ПОДУЯНЕ" and "Гара Подуяне" for tram 22 (the
     * owner, 24 Nov 2026: one direction); in the data of 5 Oct 2026 also by the
     * day — "Ж.К. ДРУЖБА-2" and "Ж.к. Дружба 2" for trolley 4, "КВ. КНЯЖЕВО"
     * and "Кв.Княжево" for tram 11 (review of 24 Nov 2026: the board's row
     * named by the one, the day's trips by the other, an empty timetable). A
     * run of anything but letters and digits is one space: "ЛЮЛИН-1,2" is not
     * "ЛЮЛИН-12". No two directions of a line merged otherwise in the data of
     * 5 Oct and 24 Nov 2026. Only for the board's options and the Lines
     * screen: the route of the day (routeFor) and journey tracking compare
     * headsigns exactly, as before.
     */
    fun directionKey(headsign: String?): String =
        headsign.orEmpty().uppercase().replace(NOT_WORD, " ").trim()

    /**
     * The route of the direction [headsign] names (directionKey) through the
     * stop [poles] (its ids): of [trips] towards it that call there, the route
     * the most of them run along (busiest) — the line's actual route through
     * the stop, which the main route of the direction may not be (100 to
     * БАЛКАН СТАР calls at 7 stops its main route does not; 179 such of 8138
     * line, direction and stop on 24 Nov 2026). 111 at МЕТРОСТАНЦИЯ АЛ.
     * МАЛИНОВ: towards Ж.К. ЛЮЛИН-1,2 its 41 stops, towards БУЛ. НИКОЛА
     * ПЕТКОВ the short run's 22 (the owner, 24 Nov 2026: the route the
     * direction shown takes). Null when none calls there.
     */
    fun routeThrough(trips: List<TodaysTrip>, headsign: String, poles: Set<String>): TodaysTrip? {
        val key = directionKey(headsign)
        return busiest(trips.filter { t -> directionKey(t.headsign) == key && t.stops.any { it.id in poles } })
    }

    /**
     * A call at a stop for its timetable (scheduleAt): the trip, the index of
     * the stop in it, and — for a trip of another direction that runs this
     * one's way and ends sooner — its headsign, where it ends; null for the
     * direction's own.
     */
    class Call(val trip: TodaysTrip, val at: Int, val endsAt: String?)  // endsAt: its last stop's name, when it has no headsign

    /**
     * The timetable of the direction [headsign] names at the stop [stopId], of
     * [trips] (the line's, on one day): its own trips calling there, by
     * directionKey — as the Lines screen showed, but whatever the spelling —
     * and those of the line's other directions that leave the stop its way
     * (runsAhead) — ending sooner, as a rule, or a stop past the way's end
     * (3ТМ's to БУЛ. КН. МАРИЯ ЛУИЗА in the data of 5 Oct 2026) — each with
     * where it ends: 111 at МЕТРОСТАНЦИЯ
     * АЛ. МАЛИНОВ towards Ж.К. ЛЮЛИН-1,2 its 70 trips and the 11 short runs to
     * БУЛ. НИКОЛА ПЕТКОВ (the owner, 24 Nov 2026: no bus leaving a stop is to
     * be lost from the Lines screen). The way is the route of the direction
     * through the stop (routeThrough); on a day the direction does not call
     * there, [way] — the stop first — if given (a stop's timetable,
     * directionsAt), else none of its short runs. In the order of [trips];
     * the caller sorts by time.
     */
    fun scheduleAt(trips: List<TodaysTrip>, headsign: String, stopId: String, way: List<Stop>? = null): List<Call> {
        val key = directionKey(headsign)
        val out = ArrayList<Call>()
        for (t in trips) if (directionKey(t.headsign) == key)
            t.stops.forEachIndexed { i, s -> if (s.id == stopId) out += Call(t, i, null) }
        val ahead = routeThrough(trips, headsign, setOf(stopId))
            ?.let { route -> route.stops.subList(route.stops.indexOfFirst { it.id == stopId }, route.stops.size) }
            ?: way?.takeIf { it.firstOrNull()?.id == stopId }
            ?: return out
        for (t in trips) {
            if (directionKey(t.headsign) == key) continue
            // Not where it ends: it leaves from nowhere there.
            for (i in 0 until t.stops.lastIndex)
                if (t.stops[i].id == stopId && runsAhead(t.stops.subList(i, t.stops.size), ahead))
                    out += Call(t, i, t.headsign.ifBlank { t.stops.last().name.ifBlank { t.stops.last().id } })
        }
        return out
    }

    // ── A stop's timetable: the lines to choose from (the owner, 26 Nov 2026) ──

    /**
     * A line's direction a stop's timetable offers to choose: its headsign
     * (of its busiest route there), the pole of the stop its trips leave from
     * — the stop of its timetable (scheduleAt) — whether its trips only
     * end there ("само слизане", as the board says), and the way on from the
     * pole of its busiest route, for its timetable on a day none of its trips
     * leaves there (scheduleAt's [way]): the others' trips leaving its way are
     * not then lost (tram 22's runs from ДЕПО ИСКЪР to ГАРА ПОДУЯНЕ on days
     * its runs to Ж.К. КРАСНА ПОЛЯНА do not pass by УЛ. ХЕМУС).
     */
    class AtStop(val headsign: String, val pole: String, val dropOffOnly: Boolean, val way: List<Stop>)

    /**
     * The directions of a line a stop's timetable offers (the owner, 26 Nov
     * 2026: one line, one row; two only for two ways of its own). [runs]: the
     * line's trips calling at the stop — at any of its [poles] (the bus and
     * the trolley rows of one place) — each standing for as many trips, of all
     * days, as its number (one a variant and headsign: GtfsRepository.linesAtStop).
     *
     * The direction most of them leave the stop towards first — its timetable
     * marks the others' trips leaving its way (scheduleAt: 111 at
     * МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ towards Ж.К. ЛЮЛИН-1,2, with the short runs to
     * БУЛ. НИКОЛА ПЕТКОВ) — then each other direction some of whose trips no
     * timetable offered before has: leaving by another way (runsAhead) than
     * any of the routes of the offered direction's trips leaving there — the
     * day's route through the stop is one of them, which one is not known —
     * a metro station, one stop for both ways, its two directions; or from
     * another pole, its own direction's too; the busier first. Directions whose trips only end there
     * are not offered while any leaves (a loop's end, as the board drops it);
     * at a terminus none leaves: the direction most of them end there,
     * drop-off only. Empty when none calls there.
     */
    fun directionsAt(runs: List<Pair<TodaysTrip, Int>>, poles: Set<String>): List<AtStop> {
        // Where a trip leaves the stop: not where it ends, there is no way on.
        fun leaves(t: TodaysTrip) = (0 until t.stops.lastIndex).firstOrNull { t.stops[it].id in poles }
        fun byWeight(runs: List<Pair<TodaysTrip, Int>>) = runs.groupBy { directionKey(it.first.headsign) }.entries
            .sortedWith(compareBy<Map.Entry<String, List<Pair<TodaysTrip, Int>>>>({ -it.value.sumOf { r -> r.second } }, { it.key }))
        val leaving = runs.filter { leaves(it.first) != null }
        if (leaving.isEmpty()) {
            val own = byWeight(runs.filter { it.first.stops.lastOrNull()?.id in poles }).firstOrNull()?.value ?: return emptyList()
            val route = heaviest(own) ?: return emptyList()
            return listOf(AtStop(route.headsign, route.stops.last().id, dropOffOnly = true, way = route.stops.takeLast(1)))
        }
        val out = ArrayList<AtStop>()
        // Of each offered, its direction and the ways on from its pole of the routes of its trips leaving there
        // (the pole first): the day's route through the stop (routeThrough) is one of them, which one is not known.
        val keys = ArrayList<String>(); val ways = ArrayList<List<List<Stop>>>()
        fun from(t: TodaysTrip): List<Stop> = t.stops.subList(leaves(t)!!, t.stops.size)
        fun shown(t: TodaysTrip, key: String): Boolean {
            val rest = from(t)
            return out.indices.any { j -> out[j].pole == rest[0].id && (keys[j] == key || ways[j].all { runsAhead(rest, it) }) }
        }
        for ((key, own) in byWeight(leaving)) while (true) {
            val route = heaviest(own.filter { !shown(it.first, key) }) ?: break
            val rest = from(route)
            out += AtStop(route.headsign, rest[0].id, dropOffOnly = false, way = rest)
            keys += key
            ways += own.map { from(it.first) }.filter { it[0].id == rest[0].id }.distinctBy { w -> w.map { it.id } }
        }
        return out
    }

    /**
     * A time of the timetable shown under a date (timetableOfDate): seconds
     * from that date's midnight — below 0 the evening before (a night line's
     * first runs), 24:00 and past after midnight (a day line's last runs);
     * said and shown as a clock (clock) — and where the trip ends, as Call.
     */
    class TimetableTime(val seconds: Int, val endsAt: String?)

    /**
     * Whether most departures of [trips] — a line's, of one day of the
     * timetable — are after its midnight (24:00 and past, in the data): a
     * night line. N1–N4, 90 % on 24 Nov 2026; every other line at most 3 %.
     */
    fun mostlyAfterMidnight(trips: List<TodaysTrip>): Boolean {
        var after = 0; var before = 0
        for (t in trips) for (i in 0 until t.stops.lastIndex) {
            val at = t.times.getOrNull(i) ?: continue
            if (at < 0) continue
            if (at >= DAY_S) after++ else before++
        }
        return after > before
    }

    /**
     * The timetable of the direction [headsign] at the stop [stopId] shown
     * under a date (scheduleAt; the owner, 25 Nov 2026): a day of the
     * timetable under the date most of the line's departures fall on
     * (mostlyAfterMidnight) — a day line's under its own date, its runs after
     * midnight last (00:15, not 24:15); a night line's under the next: the
     * night from Wednesday to Thursday under Thursday, its 23:32 first.
     * [dayBefore]: the line's trips of the day before the date, [day]: of the
     * date; [way]: as scheduleAt's. By time.
     */
    fun timetableOfDate(dayBefore: List<TodaysTrip>, day: List<TodaysTrip>, headsign: String, stopId: String,
                        way: List<Stop>? = null): List<TimetableTime> {
        val out = ArrayList<TimetableTime>()
        fun add(trips: List<TodaysTrip>, shift: Int) = scheduleAt(trips, headsign, stopId, way).forEach { c ->
            c.trip.times.getOrNull(c.at)?.takeIf { it >= 0 }?.let { out += TimetableTime(it - shift, c.endsAt) }
        }
        if (mostlyAfterMidnight(dayBefore)) add(dayBefore, DAY_S)
        if (!mostlyAfterMidnight(day)) add(day, 0)
        return out.sortedBy { it.seconds }
    }

    /**
     * Which of [days] — the times (TimetableTime.seconds) of the timetables
     * shown under consecutive dates, today's at the index [today] — the
     * timetable opens on (the owner, 25 Nov 2026): the first with a time not
     * yet passed at [now] (seconds from today's midnight). So a day line's
     * yesterday after midnight while its last runs are still to come (M2's
     * 01:03 at 00:50), a night line's tomorrow in the evening (the night
     * beginning tonight), else today. Null when none has.
     */
    fun openOn(days: List<List<Int>>, today: Int, now: Int): Int? =
        days.indices.firstOrNull { i -> firstToCome(days[i], i - today, now) != null }

    /**
     * Of the [times] of a timetable shown under the date [dayOffset] days
     * from today (0 today, -1 yesterday, 1 tomorrow), in order, the first not
     * yet passed at [now] (seconds from today's midnight) — where the screen
     * puts TalkBack's focus on opening (the owner, 25 Nov 2026); null when all
     * have.
     */
    fun firstToCome(times: List<Int>, dayOffset: Int, now: Int): Int? =
        times.firstOrNull { it >= now - dayOffset * DAY_S }


    /** A time of timetableOfDate as a clock, "HH:MM": "23:32" for the evening before, "00:32" for 24:32. */
    fun clock(seconds: Int): String {
        val s = Math.floorMod(seconds, DAY_S)
        return "%02d:%02d".format(s / 3600, s % 3600 / 60)
    }

    /**
     * Whether a trip that leaves a stop by [rest] (that stop first, then on to
     * where it ends) runs the way [ahead] goes from it (the same stop first):
     * its stops, each one of the way's, by id, in order (along) — but for
     * where it ends, often a pole of its own, off the way (111's short runs
     * end at another pole of БУЛ. НИКОЛА ПЕТКОВ than the full runs call at).
     * With no stop between, by that last stop's name: that of the way's next
     * stop; any other — at a loop's end, the way back — is not the way.
     */
    private fun runsAhead(rest: List<Stop>, ahead: List<Stop>): Boolean {
        if (along(rest, ahead)) return true
        val before = rest.subList(0, rest.lastIndex)
        if (!along(before, ahead)) return false
        return before.size > 1 || (ahead.size > 1 && rest.last().name.isNotEmpty() && rest.last().name == ahead[1].name)
    }

    /**
     * The line's directions running today besides [shown] (the Lines screen's
     * two, getDirectionsForRoute), by directionKey: each by its route today
     * (busiest), the busiest directions first, then by name — 111's БУЛ.
     * НИКОЛА ПЕТКОВ, tram 22's ДЕПО ИСКЪР (the owner, 24 Nov 2026: shown under
     * "Други направления"). Trips without a headsign — none in the data of
     * 5 Oct and 24 Nov 2026 — are one direction, "—": not left out.
     */
    fun otherDirections(trips: List<TodaysTrip>, shown: List<String>): List<TodaysTrip> {
        val seen = shown.map { directionKey(it) }.toSet()
        return trips.filter { directionKey(it.headsign) !in seen }
            .groupBy { directionKey(it.headsign) }.entries
            .sortedWith(compareBy<Map.Entry<String, List<TodaysTrip>>>({ -it.value.size }, { it.key }))
            .mapNotNull { busiest(it.value) }
    }

    /**
     * A route the Lines screen lists under "Други направления" (othersListed):
     * another direction's route today ([by] null), or one more route of a
     * direction listed already — [by], the first of its stops the
     * direction's other lists lack, which tells it apart: where it starts
     * ([fromStart]: "от ДЕПО ИСКЪР") or a stop on the way ("през …").
     */
    class Listed(val route: TodaysTrip, val by: Stop? = null, val fromStart: Boolean = false, val sameName: Boolean = false) {
        /**
         * What tells it apart, as the screen says it: "от ДЕПО ИСКЪР", "през
         * МАХАЛА ИВАНЯНЕ"; with the stop's code when a stop of that name is
         * in the direction's lists already — another pole (tram 22's runs to
         * ДЕПО ИСКЪР from the other pole of ГАРА ПОДУЯНЕ on weekends, review
         * of 24 Nov 2026): "от ГАРА ПОДУЯНЕ, код 0470". Null for another
         * direction's own route.
         */
        val label: String?
            get() = by?.let { s ->
                (if (fromStart) "от " else "през ") + s.name.ifEmpty { s.id } +
                    (if (sameName && s.code.isNotEmpty()) ", код ${s.code}" else "")
            }
    }

    /**
     * What the Lines screen lists under "Други направления", besides the two
     * directions [main] (the routes their screens list, getStopsForDirection): the routes of each
     * of those that its trips of [trips] need (coverOf), then each other
     * direction running today (otherDirections), followed by the routes it
     * needs. So every stop a trip of the day leaves from is in a list of its
     * direction, and in that direction's timetable there (scheduleAt): no
     * departure of the line is missing from the screen (the owner, 24 Nov
     * 2026: "no fixes for the cases missing — new ones would not be fixed by
     * themselves"). One route a direction, as before, left out tram 22's runs
     * from ДЕПО ИСКЪР to ГАРА ПОДУЯНЕ, 63's from ЗЛАТНИ МОСТОВЕ, 21's from the
     * depot: 513 of 364 208 departures on 24 Nov 2026.
     */
    fun othersListed(trips: List<TodaysTrip>, main: List<TodaysTrip>): List<Listed> {
        val out = ArrayList<Listed>()
        for ((key, routes) in main.groupBy { directionKey(it.headsign) }) out += coverOf(trips, key, routes)
        for (route in otherDirections(trips, main.map { it.headsign })) {
            out += Listed(route)
            out += coverOf(trips, directionKey(route.headsign), listOf(route))
        }
        return out
    }

    /**
     * The routes besides [listed] — its routes listed already — that the
     * direction [key] (directionKey) needs for every stop its trips of [trips]
     * leave from (all but where each ends) to be in one of its lists: of its
     * trips leaving from a stop none lists, the route the most of them run
     * along (busiest), and again, until none is left. Each is one of those
     * trips' own, so each round lists at least one more of them: it ends.
     */
    private fun coverOf(trips: List<TodaysTrip>, key: String, listed: List<TodaysTrip>): List<Listed> {
        val own = trips.filter { directionKey(it.headsign) == key }
        val listedIds = listed.flatMapTo(HashSet()) { r -> r.stops.map { it.id } }
        val listedNames = listed.flatMapTo(HashSet()) { r -> r.stops.map { directionKey(it.name) } }
        val out = ArrayList<Listed>()
        while (true) {
            val left = own.filter { t -> t.stops.subList(0, t.stops.lastIndex).any { it.id !in listedIds } }
            val route = busiest(left) ?: break
            val first = route.stops.indexOfFirst { it.id !in listedIds }
            val by = route.stops[first]
            out += Listed(route, by, first == 0, directionKey(by.name) in listedNames)
            route.stops.mapTo(listedIds) { it.id }
            route.stops.mapTo(listedNames) { directionKey(it.name) }
        }
        return out
    }

    /**
     * Whether routes [a] and [b] run the opposite ways: the stops of [a]
     * that have one of [b] near (OPPOSITE_M — across the street), taken in
     * order, meet those of [b] mostly backwards. So every line with two
     * directions, every day of the feeds of 5 and 28 Oct 2026. Also what a
     * reversal of direction is, on identifying a vehicle
     * (JourneyService.adoptVehicle): not another headsign.
     */
    fun opposite(a: TodaysTrip, b: TodaysTrip): Boolean {
        val at = a.stops.mapNotNull { s ->
            b.stops.indices.minByOrNull { distance(s, b.stops[it]) }?.takeIf { distance(s, b.stops[it]) <= OPPOSITE_M }
        }
        val back = at.zipWithNext().count { (x, y) -> y < x }
        return back >= 2 && back > at.zipWithNext().count { (x, y) -> y > x }
    }

    /**
     * The way on from the stop we boarded at, by the timetable — for tracking
     * before a vehicle is identified, and for one never identified, reporting
     * no positions (step 2, the owner, 28 Oct 2026): the trips of the line due
     * at that stop ([poles]: the ids of its pole) from DUE_BEFORE_S before our
     * departure ([departedSec], as TodaysTrip.times) to DUE_AFTER_S after it,
     * when they all go on from there by the same stops — the first of them by
     * trip id, and where in it we boarded. Null when none is due, or they go
     * on different ways: the direction's route then, as before.
     *
     * By the timetable, not the predictions: for a vehicle reporting no
     * positions they know no more, and may not have its trip at all. The
     * window takes in a vehicle up to 20 min late or 5 min early; beyond, the
     * trip due may be another's. Every stop of every trip of the feed of
     * 28 Oct 2026 boarded once (tools/test-harness/run_route_tests.sh), on a
     * weekday, of the trips that run the direction's way (thisWay — the
     * caller's to give): on wrong stops 3892 → 1597, a run's end not known
     * 3939 → 2109; none worse than the direction's route from 5 min early to
     * 20 min late, nor with where we waited 25 m off the stop; about 0.4 %
     * worse beyond. Short runs and full ones every few minutes (111) are
     * mostly not told apart.
     */
    fun wayOn(trips: List<TodaysTrip>, poles: Set<String>, departedSec: Int): Pair<TodaysTrip, Int>? {
        val due = ArrayList<Pair<TodaysTrip, Int>>()
        for (t in trips) {
            if (t.times.size != t.stops.size) continue
            // Not where it ends: there is no way on.
            for (i in 0 until t.stops.lastIndex) {
                val at = t.times[i]
                if (at >= 0 && t.stops[i].id in poles && at in departedSec - DUE_BEFORE_S..departedSec + DUE_AFTER_S) due += t to i
            }
        }
        val ways = due.map { (t, i) -> t.stops.subList(i + 1, t.stops.size).map { it.id } }.toSet()
        return if (ways.size == 1) due.minByOrNull { it.first.tripId } else null
    }

    /**
     * The stop we boarded at, for the way on (wayOn), and its index in
     * [followed] (-1 when not of it): of its stops, the one [near] picks —
     * the stop nearest where we waited, within reach — else, the stop we
     * waited at not being on it, of the stops of the line's other routes
     * today that run its way (thisWay): 63's runs from ЗЛАТНИ МОСТОВЕ, whose
     * first 7 stops the route of the day to БУЛ. ЦАР БОРИС ІІІ does not have;
     * trams 21 and 22 to and from the depot. Its own stops first, as the
     * early rule by the boarding stop has it (JourneyService.findBoardingStop).
     * Null when neither.
     */
    fun boardedAt(followed: TodaysTrip, trips: List<TodaysTrip>, near: (List<Stop>) -> Int?): Pair<Stop, Int>? {
        near(followed.stops)?.let { return followed.stops[it] to it }
        val others = thisWay(trips, followed).flatMap { it.stops }.distinctBy { it.id }
        return near(others)?.let { others[it] to -1 }
    }

    /**
     * Of [trips], those not running the opposite way of [route] (opposite):
     * not the way back, whose stops are across the street from ours.
     */
    fun thisWay(trips: List<TodaysTrip>, route: TodaysTrip): List<TodaysTrip> {
        val verdict = HashMap<List<String>, Boolean>()
        return trips.filter { t -> verdict.getOrPut(t.stops.map { it.id }) { !opposite(t, route) } }
    }

    /** "H:MM:SS" (past 24:00 for after midnight) in seconds; -1 when not a time. */
    fun secondsOf(hms: String?): Int {
        val p = hms?.trim()?.split(":") ?: return -1
        if (p.size != 3) return -1
        val (h, m, s) = p.map { it.toIntOrNull() ?: return -1 }
        return if (h < 0 || m !in 0..59 || s !in 0..59) -1 else h * 3600 + m * 60 + s
    }

    private fun distance(a: Stop, b: Stop): Double =
        hypot((a.lat - b.lat) * M_PER_DEG, (a.lon - b.lon) * M_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2)))

    /** A stop of the way back is this near its counterpart of the way there, across the street, at most. */
    const val OPPOSITE_M = 150.0
    /** The trips due where we boarded: from this long before our departure… (wayOn) */
    const val DUE_BEFORE_S = 20 * 60
    /** …to this long after it. */
    const val DUE_AFTER_S = 5 * 60
    private const val M_PER_DEG = 111_195.0
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    /** A day, in seconds. */
    private const val DAY_S = 24 * 3600
}
