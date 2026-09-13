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
         * Within this — after correcting for how stale the report is — a
         * vehicle is taken to be the one we are in, provided it is the only
         * one there.
         *
         * Narrowed from 60 m once a lone candidate became sufficient on its
         * own: a wide radius admits vehicles from the next carriageway, and
         * every extra one inside it is another reason to wait. Measured
         * sightings of the vehicle actually carrying the passenger read 0–1 m,
         * so 30 leaves ample room for a poor fix without inviting company.
         */
        private const val RIDING_WITH_RADIUS = 30.0


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
        /** True when another vehicle was also within range. */
        val contested: Boolean,
        /** Report time, so callers can insist on a second, fresher reading. */
        val timestamp: Long,
        /** How old that report was when taken, in seconds; -1 if unknown. */
        val reportAgeSec: Long
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
        val all = realtimeRepo.getAllVehicles()
            .filter { it.lat != 0.0 || it.lon != 0.0 }
            .map { v ->
                val age = if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else 0
                val raw = LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon)
                v to (raw - userSpeedMps * age).coerceAtLeast(0.0)
            }
            .sortedBy { it.second }

        val ranked = all.filter { (_, d) -> d <= RIDING_WITH_RADIUS }

        val (best, dist) = ranked.firstOrNull() ?: run {
            // Says how far the nearest one was, so a run of blank checks can
            // be read afterwards as "nothing was about" or "it was just
            // outside the radius" — two very different things.
            val nearestAny = all.minOfOrNull { (_, d) -> d }
            FileLogger.d(TAG, "No vehicle within riding radius " +
                "(nearest ${nearestAny?.toInt() ?: -1} m)")
            return null
        }
        val runnerUp = ranked.getOrNull(1)?.second

        // A lone candidate is reported as certain; company makes it
        // contested, and the caller then wants to see the same one twice.
        //
        // Alone, there is nothing it could be confused with: a vehicle
        // passing the other way would itself be a second candidate. But
        // refusing to answer at all while two are in range proved too blunt.
        // In heavy traffic two buses run nose to tail for minutes, and the
        // identification would wait the whole time — ending exactly when the
        // routes diverge and it is too late to act on.
        //
        // Reported as contested, the nearest is still a real signal: the
        // vehicle carrying the passenger reads a metre or so away every time,
        // while one merely alongside drifts by a few metres as the two jostle.
        // Seeing the same one twice tells them apart.
        val contested = runnerUp != null
        if (contested) {
            FileLogger.d(TAG, "Two in range (${dist.toInt()} m and " +
                "${runnerUp!!.toInt()} m) — nearest reported as contested")
        }

        val name = try {
            gtfsRepo.getRouteById(best.routeId)?.routeShortName
        } catch (e: Exception) { null } ?: return null
        val type = try {
            gtfsRepo.getRouteById(best.routeId)?.routeType ?: 3
        } catch (e: Exception) { 3 }
        val headsign = try {
            gtfsRepo.getHeadsignByTripIdPrefix(best.tripId)
        } catch (e: Exception) { null }

        // Age of the report is logged with every sighting: the pair of
        // observations required to identify a vehicle must come from
        // different reports, so how often CGM refresh them sets the pace at
        // which identification can possibly happen. Guessing at that pace has
        // already cost one round of changes.
        val ageSec = if (best.timestamp > 0) nowSec - best.timestamp else -1
        FileLogger.i(TAG, "Riding vehicle: $name → ${headsign ?: "?"} " +
            "at ${dist.toInt()} m, report ${ageSec}s old" +
            (if (contested) ", contested" else "") + " (trip=${best.tripId})")
        return RidingVehicle(
            best.routeId, name, type, best.tripId, headsign, dist,
            contested, best.timestamp, ageSec)
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
