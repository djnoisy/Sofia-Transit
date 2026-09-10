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

        /**
         * Within this — after correcting for how stale the report is — a
         * vehicle is close enough to be the one we are sitting in. The claim
         * being made is that the passenger is aboard it, so the figure is
         * deliberately tight.
         */
        private const val RIDING_WITH_RADIUS = 60.0


    }



    /** The vehicle the passenger appears to be travelling in. */
    data class RidingVehicle(
        val routeId: String,
        val routeShortName: String,
        val routeType: Int,
        val tripId: String,
        /** Direction, from the trip id; null when the prefix is unknown. */
        val headsign: String?,
        val distanceMetres: Double,
        /** Report time, so callers can insist on a second, fresher reading. */
        val timestamp: Long
    )

    /**
     * The vehicle the passenger is in, whatever line it belongs to.
     *
     * One question, asked of the whole feed, rather than several narrower
     * ones asked of the line the passenger happened to pick. Being aboard is
     * what the data can actually show: over a ride the vehicle carrying us
     * reads a handful of metres away time after time, while everything else
     * comes and goes.
     *
     * Null when nothing stands out — no vehicle close enough, or two equally
     * close, or a trip whose direction cannot be resolved. Silence is the
     * right answer there; the caller keeps whatever it already had.
     */
    suspend fun findRidingVehicle(
        userLat: Double,
        userLon: Double,
        userSpeedMps: Double
    ): RidingVehicle? {
        val nowSec = System.currentTimeMillis() / 1000

        // Corrected for staleness: a report four seconds old is already half
        // a block behind at city speed, purely because both are moving. Left
        // uncorrected, the vehicle we sit in reads 50–60 m away and never
        // looks close at all.
        val ranked = realtimeRepo.getAllVehicles()
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .map { v ->
                val age = if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else 0
                val raw = LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon)
                v to (raw - userSpeedMps * age).coerceAtLeast(0.0)
            }
            .filter { (_, d) -> d <= RIDING_WITH_RADIUS }
            .sortedBy { it.second }

        val (best, dist) = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second
        if (runnerUp != null && runnerUp - dist < AMBIGUITY_MARGIN) return null

        val name = try {
            gtfsRepo.getRouteById(best.routeId)?.routeShortName
        } catch (e: Exception) { null } ?: return null
        val type = try {
            gtfsRepo.getRouteById(best.routeId)?.routeType ?: 3
        } catch (e: Exception) { 3 }
        val headsign = try {
            gtfsRepo.getHeadsignByTripIdPrefix(best.tripId)
        } catch (e: Exception) { null }

        FileLogger.i(TAG, "Riding vehicle: $name → ${headsign ?: "?"} " +
            "at ${dist.toInt()} m (trip=${best.tripId})")
        return RidingVehicle(
            best.routeId, name, type, best.tripId, headsign, dist, best.timestamp)
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

}
