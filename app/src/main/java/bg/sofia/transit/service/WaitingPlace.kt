package bg.sofia.transit.service

import kotlin.math.cos
import kotlin.math.hypot

/**
 * Where we waited before departure — for telling which stop we board at
 * (JourneyService.findBoardingStop), and which arrival passes unspoken as we
 * pull away from it: the last place we stood near a stop of the line before
 * the vehicle took us away (placeFor). Tracking is begun aboard or waiting
 * at a stop (the owner, 27 Oct 2026), so standing near a stop is waiting
 * there; on the way to it we may stand elsewhere too, at a crossing.
 *
 * Not where tracking began: begun on the way to МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ
 * (27 Oct 2026), the first settled fix was 122 m short of the stop, walking,
 * and no stop we boarded at was found, though we stood 20 m from it for 38 s
 * before 76 pulled away.
 *
 * Standing: under STANDING_MPS by the receiver's own measure (positions when
 * it gives none); fixes no more than STANDING_GAP_MS apart are one spell, its
 * place the middle of them, as standing fixes wander. A fix beyond walking
 * reach of the last spell (AWAY_M) — moving, or with a fix every 10 s already
 * standing at a light — says the vehicle has taken us away: standing after
 * that, at a light or in a queue, is aboard. Of the spells before, the last
 * near a stop, not simply the last: crawling
 * out of the stop in a queue, stand by stand, at walking pace, never goes
 * beyond walking reach (review of 27 Oct 2026: the place moved with the bus,
 * crawling 65 m on, and the stop was lost in every run; with a fix every
 * 5-10 s, a stand at a light 65-130 m on did the same). None near a stop —
 * never standing, aboard at a crawl — the first settled fix under vehicle
 * speed, as before.
 *
 * Fed every settled, accurate fix before departure on the main thread, asked
 * after a reading on another: synchronized. Pure Kotlin, so it can be tested
 * on its own (tools/test-harness/run_waiting_tests.sh).
 */
class WaitingPlace {
    private class Spell(var latSum: Double, var lonSum: Double, var fixes: Int, var endMs: Long) {
        val lat get() = latSum / fixes
        val lon get() = lonSum / fixes
    }

    private val spells = ArrayList<Spell>()
    /** The vehicle has taken us away: no more spells. */
    private var away = false
    private var firstLat = 0.0
    private var firstLon = 0.0
    private var firstKnown = false

    /**
     * One settled, accurate fix before departure, at [nowMs]: at [lat]/[lon],
     * moving at [ownMps] by its own measure (the receiver's; from positions
     * when it gives none; null when neither), [slowKmh] the short average
     * speed the first fix is judged by.
     */
    @Synchronized
    fun onFix(nowMs: Long, lat: Double, lon: Double, ownMps: Double?, slowKmh: Double?) {
        if (!firstKnown && (slowKmh ?: 0.0) < VEHICLE_KMH) {
            firstKnown = true
            firstLat = lat
            firstLon = lon
        }
        if (away) return
        val last = spells.lastOrNull()
        val standing = ownMps != null && ownMps < STANDING_MPS
        if (standing && last != null && nowMs - last.endMs <= STANDING_GAP_MS) {
            last.latSum += lat
            last.lonSum += lon
            last.fixes++
            last.endMs = nowMs
        } else if (last != null &&
            distance(last.lat, last.lon, lat, lon) > AWAY_M + WALKING_MPS * (nowMs - last.endMs) / 1000.0) {
            away = true
        } else if (standing) {
            spells += Spell(lat, lon, 1, nowMs)
            if (spells.size > MAX_SPELLS) spells.removeAt(0)
        }
    }

    /**
     * Where we waited, by the stops of the line ([stops], lat/lon): the middle
     * of the last spell before the vehicle took us away that lies within
     * [within] of one of them; else the first settled fix under vehicle speed.
     * Null before either is known.
     */
    @Synchronized
    fun placeFor(stops: List<Pair<Double, Double>>, within: Double): Pair<Double, Double>? {
        for (s in spells.asReversed()) {
            if (stops.any { (sLat, sLon) -> distance(s.lat, s.lon, sLat, sLon) <= within }) return s.lat to s.lon
        }
        return if (firstKnown) firstLat to firstLon else null
    }

    @Synchronized
    fun reset() {
        spells.clear()
        away = false
        firstKnown = false
    }

    companion object {
        /** Standing: under this, by the receiver's own measure. Walking is 1-2 m/s. */
        const val STANDING_MPS = 0.5
        /** Standing fixes this far apart, or nearer, are one spell. */
        const val STANDING_GAP_MS = 3_000L
        /** Walking pace, the most a passenger on foot is taken to cover (RideModel.WALK_MPS). */
        const val WALKING_MPS = 2.0
        /**
         * Taken away: this far beyond walking reach of where we last stood —
         * as far as departure is measured from (JourneyService
         * DEPARTURE_MIN_DISPLACEMENT), standing fixes wandering less.
         */
        const val AWAY_M = 30.0
        /** Vehicle speed, as departure is detected by (JourneyService.MIN_SPEED_FOR_FOREIGN_CHECK). */
        const val VEHICLE_KMH = 10.0
        /** Spells kept: walking a long way to the stop, the oldest go. */
        const val MAX_SPELLS = 50

        private fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            hypot((lat1 - lat2) * M_PER_DEG, (lon1 - lon2) * M_PER_DEG * cos(Math.toRadians((lat1 + lat2) / 2)))

        private const val M_PER_DEG = 111_195.0
    }
}
