package bg.sofia.transit.data.repository

import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.LocationHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Works out which physical vehicle the passenger is on.
 *
 * Why this exists: a journey is tracked by trip_id, and the trip determines
 * both the stop list and the arrival prediction at the alighting stop. Taking
 * the trip_id from the arrivals list assumes the passenger boarded exactly
 * the vehicle whose arrival they tapped — which fails when they board the one
 * behind it, when they start tracking mid-journey, or when the vehicle is
 * running a shortened depot trip with a different stop list.
 *
 * Matching by position avoids all three. Field measurements from the vehicle
 * feed support it: positions are fresh (97% under 30 s) and while riding, the
 * vehicle we are in reported 10 m away while the next nearest was 91 m — a
 * gap wide enough to decide on.
 */
@Singleton
class VehicleMatcher @Inject constructor(
    private val realtimeRepo: RealtimeRepository,
    private val gtfsRepo: GtfsRepository
) {

    companion object {
        private const val TAG = "VehicleMatcher"

        /** Beyond this, a vehicle is not one we could plausibly be sitting in. */
        private const val MAX_MATCH_DISTANCE = 120.0

        /**
         * A position older than this is not trustworthy for matching: a bus
         * covers hundreds of metres in that time, so an old fix says little
         * about where it is now.
         */
        private const val MAX_POSITION_AGE_SEC = 90L

        /**
         * The runner-up must be at least this much farther away before we
         * treat a match as certain. Two vehicles of one line often stand near
         * each other; without a margin we would flip between them.
         */
        private const val AMBIGUITY_MARGIN = 40.0
    }

    /** A vehicle we believe the passenger could be travelling in. */
    data class Match(
        val vehicle: VehicleInfo,
        val distanceMetres: Double,
        /** True when clearly nearer than any other candidate. */
        val unambiguous: Boolean
    )

    /**
     * Finds the vehicle of [routeId] nearest the passenger, optionally
     * restricted to those heading towards [headsign].
     *
     * Direction is resolved from the trip_id prefix, because CGM leaves
     * direction_id and the headsign empty in the vehicle feed. Vehicles whose
     * prefix cannot be resolved are kept rather than dropped: an unresolved
     * direction is usually a trip too new to be in the static data, and the
     * distance test will discard it anyway if it is going the other way.
     */
    suspend fun findVehicle(
        routeId: String,
        headsign: String?,
        userLat: Double,
        userLon: Double
    ): Match? {
        val nowSec = System.currentTimeMillis() / 1000

        val candidates = realtimeRepo.getVehiclesForRoute(routeId)
            .filter { v ->
                v.lat != 0.0 && v.lon != 0.0 &&
                    (v.timestamp <= 0 || nowSec - v.timestamp <= MAX_POSITION_AGE_SEC)
            }
            .filter { v -> headsign == null || matchesDirection(v.tripId, headsign) }

        if (candidates.isEmpty()) {
            FileLogger.i(TAG, "No usable vehicles for route=$routeId")
            return null
        }

        val ranked = candidates
            .map { it to LocationHelper.distanceMetres(userLat, userLon, it.lat, it.lon) }
            .sortedBy { it.second }

        val (best, bestDist) = ranked.first()
        if (bestDist > MAX_MATCH_DISTANCE) {
            FileLogger.i(TAG, "Nearest $routeId vehicle is ${bestDist.toInt()} m — too far")
            return null
        }

        val runnerUp = ranked.getOrNull(1)?.second
        val unambiguous = runnerUp == null || runnerUp - bestDist >= AMBIGUITY_MARGIN

        FileLogger.i(TAG, "Matched $routeId: ${bestDist.toInt()} m, " +
            "trip=${best.tripId}, unambiguous=$unambiguous")
        return Match(best, bestDist, unambiguous)
    }

    /**
     * How far the tracked vehicle is from the passenger right now, or null
     * when the feed has nothing usable for it. Used both to confirm we are
     * following the right vehicle and to notice that the passenger has got
     * off — once they have, the gap grows and keeps growing.
     */
    suspend fun distanceToTrackedVehicle(
        tripId: String,
        userLat: Double,
        userLon: Double
    ): Double? {
        val v = realtimeRepo.getVehicleForTrip(tripId) ?: return null
        if (v.lat == 0.0 && v.lon == 0.0) return null
        val nowSec = System.currentTimeMillis() / 1000
        if (v.timestamp > 0 && nowSec - v.timestamp > MAX_POSITION_AGE_SEC) return null
        return LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon)
    }

    /**
     * True if the trip runs towards [headsign], decided from the trip_id
     * prefix. Returns true when the prefix is unknown, so unmapped trips stay
     * in contention instead of being silently excluded.
     */
    private suspend fun matchesDirection(tripId: String, headsign: String): Boolean {
        val resolved = gtfsRepo.getHeadsignByTripIdPrefix(tripId) ?: return true
        return resolved.equals(headsign, ignoreCase = true)
    }
}
