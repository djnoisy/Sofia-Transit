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
 * Pure Kotlin, so it can be tested on the feed itself
 * (tools/test-harness/run_route_tests.sh).
 */
object RouteOfTheDay {

    class Stop(val id: String, val lat: Double, val lon: Double)

    /** A trip that runs today: its headsign, trimmed, and its stops in order. */
    class TodaysTrip(val tripId: String, val headsign: String, val stops: List<Stop>)

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
    private fun busiest(own: List<TodaysTrip>): TodaysTrip? =
        own.sortedBy { it.tripId }.distinctBy { t -> t.stops.map { it.id } }
            .map { r -> r to own.count { along(it, r) } }
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
    fun along(trip: TodaysTrip, route: TodaysTrip): Boolean {
        var i = 0
        for (s in route.stops) {
            if (i == trip.stops.size) break
            if (trip.stops[i].id == s.id) i++
        }
        return i == trip.stops.size
    }

    /**
     * Whether routes [a] and [b] run the opposite ways: the stops of [a]
     * that have one of [b] near (OPPOSITE_M — across the street), taken in
     * order, meet those of [b] mostly backwards. So every line with two
     * directions, every day of the feeds of 5 and 28 Oct 2026.
     */
    private fun opposite(a: TodaysTrip, b: TodaysTrip): Boolean {
        val at = a.stops.mapNotNull { s ->
            b.stops.indices.minByOrNull { distance(s, b.stops[it]) }?.takeIf { distance(s, b.stops[it]) <= OPPOSITE_M }
        }
        val back = at.zipWithNext().count { (x, y) -> y < x }
        return back >= 2 && back > at.zipWithNext().count { (x, y) -> y > x }
    }

    private fun distance(a: Stop, b: Stop): Double =
        hypot((a.lat - b.lat) * M_PER_DEG, (a.lon - b.lon) * M_PER_DEG * cos(Math.toRadians((a.lat + b.lat) / 2)))

    /** A stop of the way back is this near its counterpart of the way there, across the street, at most. */
    const val OPPOSITE_M = 150.0
    private const val M_PER_DEG = 111_195.0
}
