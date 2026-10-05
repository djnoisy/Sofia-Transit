package bg.sofia.transit.service

import kotlin.math.cos
import kotlin.math.hypot

/**
 * The road a trip follows (its shape from shapes.txt), with the trip's stops
 * placed on it — for measuring how far a stop is BY ROAD rather than in a
 * straight line.
 *
 * The straight line misleads where the road bends away: after ХМС the road
 * to ДЪРЖАВНА ПЕЧАТНИЦА passes 35–42 m from the stop with 360 m still to go,
 * and "Спирка, ДЪРЖАВНА ПЕЧАТНИЦА" was said there two minutes early.
 *
 * Pure Kotlin, no Android types, so it can be tested on its own. Positions
 * are worked out on a flat local plane around the shape's first point;
 * across the city the error of that is well under a metre per kilometre.
 */
class RouteGeometry private constructor(
    private val x: DoubleArray,
    private val y: DoubleArray,
    /** Distance along the road from the first point to point i. */
    private val cum: DoubleArray,
    /** Distance along the road of each stop, in the order given to [fit]. */
    val stopAlong: DoubleArray,
    private val lat0: Double,
    private val kx: Double
) {
    /** Where a position lies on the road: how far along, and how far off it. */
    data class Position(val along: Double, val offset: Double)

    val length: Double get() = cum.last()

    /**
     * The nearest point of the road to [lat]/[lon] among the stretches
     * between [fromAlong] and [toAlong] metres along it, or null if that
     * range holds no stretch. Searching a window rather than the whole road
     * is what keeps a position on its own stretch where the road passes
     * close to itself — as it does around ДЪРЖАВНА ПЕЧАТНИЦА.
     */
    fun locate(lat: Double, lon: Double, fromAlong: Double, toAlong: Double): Position? {
        val px = (lon) * kx
        val py = (lat - lat0) * KY
        var best: Position? = null
        for (i in 0 until cum.size - 1) {
            if (cum[i + 1] < fromAlong) continue
            if (cum[i] > toAlong) break
            val p = project(i, px, py)
            if (best == null || p.offset < best.offset) best = p
        }
        return best
    }

    /**
     * Places a fix on the road and returns how far along it is, or null when
     * it lies more than [MAX_OFFSET] off the road (a poor fix, or a detour).
     *
     * With [last] — the previous fix's place, [secsSinceLast] ago — only
     * the stretch it could have reached is searched: no further back than
     * GPS scatter, no further ahead than any vehicle could have gone. That
     * keeps a fix on its own stretch where the road passes close to itself;
     * after ХМС it runs 35 m from ДЪРЖАВНА ПЕЧАТНИЦА with 360 m still to go.
     * Without [last], between [from] and [to].
     */
    fun place(
        lat: Double, lon: Double,
        last: Double?, secsSinceLast: Double,
        from: Double, to: Double
    ): Double? = nearest(lat, lon, last, secsSinceLast, from, to)
        ?.takeIf { it.offset <= MAX_OFFSET }?.along

    /**
     * The nearest point of the stretch [place] searches, however far off the
     * fix is — how far from the road we are, for [OffRoadWatch].
     */
    fun nearest(
        lat: Double, lon: Double,
        last: Double?, secsSinceLast: Double,
        from: Double, to: Double
    ): Position? = if (last != null)
        locate(lat, lon, last - BACK_MARGIN, last + BACK_MARGIN + MAX_SPEED_MPS * secsSinceLast)
    else locate(lat, lon, from, to)

    /**
     * The distance to stop [idx] for announcing it, from [pos] on the road
     * (null: not placed) — by road while the stop lies ahead, otherwise
     * [straight]. Never less than [straight].
     */
    fun distanceToStop(pos: Double?, idx: Int, straight: Double): Double {
        if (pos == null) return straight
        val road = (stopAlong.getOrNull(idx) ?: return straight) - pos
        return if (road >= 0) maxOf(road, straight) else straight
    }

    /**
     * The stop at or ahead of [pos], from [fromIdx] on: the first not more
     * than [margin] behind it, so a rider standing at a stop is placed on
     * that stop. Null when every stop is behind.
     */
    fun nextStop(pos: Double, fromIdx: Int, margin: Double): Int? =
        (fromIdx.coerceAtLeast(0) until stopAlong.size).firstOrNull { stopAlong[it] >= pos - margin }

    private fun project(i: Int, px: Double, py: Double): Position {
        val ax = x[i]; val ay = y[i]
        val dx = x[i + 1] - ax; val dy = y[i + 1] - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0
                else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val qx = ax + t * dx; val qy = ay + t * dy
        return Position(cum[i] + t * (cum[i + 1] - cum[i]), hypot(px - qx, py - qy))
    }

    companion object {
        /**
         * Metres per degree of latitude, on the same sphere as the app's
         * straight-line distances (LocationHelper, R = 6 371 000 m), so road
         * and straight-line distances agree where the road is straight.
         */
        private const val KY = 6_371_000.0 * Math.PI / 180.0

        /** A fix further than this from the road is not placed on it. */
        const val MAX_OFFSET = 60.0
        /** How far back along the road the next fix may land (GPS scatter). */
        const val BACK_MARGIN = 60.0
        /** How far ahead per second it may land: faster than any bus or tram. */
        const val MAX_SPEED_MPS = 35.0

        /** A stop further than this from the road means the shape is not this trip's. */
        const val STOP_MAX_OFFSET = 30.0
        /**
         * The first and last stop may lie further off: a terminus or depot
         * stop is sometimes set apart from where the road begins or ends —
         * 33 m at ЦЕНТРАЛНА ГАРА for bus 213, 46 m at ДЕПО ИСКЪР (feed of
         * 5 Oct 2026). The stops between still have to be on the road.
         */
        const val TERMINUS_MAX_OFFSET = 100.0

        /**
         * Among several passes of the road near a stop, the earliest one
         * within this much of the nearest is taken: a stop lies on the road
         * where the trip calls at it, not on a later pass by the same place.
         */
        private const val STOP_PASS_TOLERANCE = 3.0

        /**
         * Builds the geometry from [latLon] (lat, lon pairs, in order) and
         * places [stops] on it in their order, each at or after the one
         * before. Null when the shape has fewer than two points or any stop
         * lies more than [STOP_MAX_OFFSET] from it ([TERMINUS_MAX_OFFSET] for
         * the first and last) — the shape is then not
         * the road these stops are on, and the straight line is used instead.
         */
        fun fit(latLon: FloatArray, stops: List<Pair<Double, Double>>): RouteGeometry? {
            val n = latLon.size / 2
            if (n < 2 || stops.isEmpty()) return null
            val lat0 = latLon[0].toDouble()
            val kx = KY * cos(Math.toRadians(lat0))
            val x = DoubleArray(n) { latLon[2 * it + 1] * kx }
            val y = DoubleArray(n) { (latLon[2 * it] - lat0) * KY }
            val cum = DoubleArray(n)
            for (i in 1 until n) cum[i] = cum[i - 1] + hypot(x[i] - x[i - 1], y[i] - y[i - 1])

            val g = RouteGeometry(x, y, cum, DoubleArray(stops.size), lat0, kx)
            var from = 0.0
            for ((k, s) in stops.withIndex()) {
                val px = s.second * kx
                val py = (s.first - lat0) * KY
                // Nearest pass at or after the previous stop…
                var nearest = Double.MAX_VALUE
                for (i in 0 until n - 1) {
                    if (cum[i + 1] < from) continue
                    val p = g.project(i, px, py)
                    if (p.along >= from && p.offset < nearest) nearest = p.offset
                }
                val limit = if (k == 0 || k == stops.lastIndex) TERMINUS_MAX_OFFSET else STOP_MAX_OFFSET
                if (nearest > limit) return null
                // …then the earliest pass about as near as that.
                var along = -1.0
                for (i in 0 until n - 1) {
                    if (cum[i + 1] < from) continue
                    val p = g.project(i, px, py)
                    if (p.along >= from && p.offset <= nearest + STOP_PASS_TOLERANCE) {
                        along = p.along
                        break
                    }
                }
                g.stopAlong[k] = along
                from = along
            }
            return g
        }
    }
}

/**
 * Notices travel along a different road from the one the followed line takes:
 * accurate fixes, at vehicle speed, more than [OFFSET] from its road, until we
 * are [TRAVEL] from where that began.
 *
 * Lines often share a road and part where one turns into a parallel street.
 * Measured against the stops ahead, that shows only once every one of them is
 * 300 m further than it was — which may not happen at all before the roads
 * meet again. Towards ЦЕНТРАЛНА ГАРА, 213 and 305 part 1.3 km after ХОТЕЛ
 * ПЛИСКА and meet again at УЛ. БЯЛО МОРЕ: by the stops, riding one while
 * following the other went unnoticed all the way; by the road it is noticed
 * 160–170 m after the parting (feed of 5 Oct 2026, tools/test-harness).
 *
 * A moment of poor GPS is not enough, nor is a short loop: before ХОТЕЛ
 * ПЛИСКА 305 turns 80 m aside to ПЛОЩАД НА АВИАЦИЯТА and back, at the limit
 * of [TRAVEL] — counted in some rides, rightly then, as it is not 213's road.
 * Being back within [OFFSET] starts the count afresh. Slow or inaccurate
 * fixes neither count nor reset it.
 *
 * Pure Kotlin, so it can be tested on its own.
 */
class OffRoadWatch {
    private var fromLat = 0.0
    private var fromLon = 0.0
    private var counting = false

    /** How far from the road the count began, in metres; 0 when not counting. */
    var gone = 0.0
        private set

    /**
     * One fix: [offset] from the road (null: no road to measure against),
     * whether it is [accurate], and whether we are [moving] at vehicle speed.
     * True when the off-road travel has just reached [TRAVEL]; the count then
     * starts afresh.
     */
    fun update(offset: Double?, lat: Double, lon: Double, accurate: Boolean, moving: Boolean): Boolean {
        if (offset == null) { reset(); return false }
        if (!accurate) return false
        if (offset <= OFFSET) { reset(); return false }
        if (!moving) return false
        if (!counting) {
            counting = true
            fromLat = lat
            fromLon = lon
            gone = 0.0
            return false
        }
        val kx = KY * cos(Math.toRadians(lat))
        gone = hypot((lat - fromLat) * KY, (lon - fromLon) * kx)
        if (gone < TRAVEL) return false
        reset()
        return true
    }

    fun reset() {
        counting = false
        gone = 0.0
    }

    companion object {
        private const val KY = 6_371_000.0 * Math.PI / 180.0
        /**
         * Further than this from the road is off it: beyond GPS scatter on an
         * accurate fix (≤ 30 m), and beyond the width of a wide boulevard.
         */
        const val OFFSET = 50.0
        /** How far we must have gone off the road before it counts. */
        const val TRAVEL = 150.0
    }
}
