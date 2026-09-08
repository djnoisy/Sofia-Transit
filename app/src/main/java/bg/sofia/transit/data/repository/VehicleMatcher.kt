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

        /**
         * Within this, a vehicle is close enough to be the one we are sitting
         * in. Tighter than MAX_MATCH_DISTANCE: here we are asserting that the
         * passenger is aboard, not merely that the vehicle is a candidate.
         */
        private const val RIDING_WITH_RADIUS = 60.0

        /**
         * Beyond this, no vehicle of the line can be the one we are in.
         *
         * Deliberately generous — several times the riding radius. The point
         * is not to decide which vehicle we are aboard but to rule the line
         * out altogether, and that judgement must survive stale reports,
         * a poor fix and a bend in the road. What it does catch is the case
         * that matters: the chosen line's nearest vehicle being streets away.
         */
        private const val NOT_ABOARD_RADIUS = 250.0

        /** Gap to the next candidate that makes an identification clear-cut. */
        private const val UNCONTESTED_MARGIN = 120.0
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

    /** A vehicle of a different line, riding alongside the passenger. */
    data class ForeignVehicle(
        val routeId: String,
        val routeShortName: String,
        val distanceMetres: Double,
        /**
         * True when this vehicle stands clearly apart from anything else
         * nearby, so fewer confirmations are needed before acting on it.
         */
        val uncontested: Boolean,
        /** The vehicle's own trip, so tracking can switch to it. */
        val tripId: String,
        val routeType: Int,
        /** Direction, resolved from the trip id; null when unknown. */
        val headsign: String?
    )

    /**
     * Looks for a vehicle of a DIFFERENT line right next to the passenger.
     *
     * This is the direct evidence of having boarded the wrong bus, and it
     * arrives far sooner than the alternative: waiting for the tracked
     * vehicle to drift away takes minutes of travelling in the wrong
     * direction, while the vehicle actually being ridden is a few metres away
     * from the first check onwards.
     *
     * Only reported when a single vehicle is convincingly nearest — at a busy
     * stop several vehicles pass within metres of each other, and naming one
     * of them would be a guess.
     */
    suspend fun findForeignVehicle(
        trackedRouteId: String,
        userLat: Double,
        userLon: Double,
        userSpeedMps: Double = 0.0
    ): ForeignVehicle? {
        val nowSec = System.currentTimeMillis() / 1000

        // The measured distance is to where the vehicle WAS when it last
        // reported, not where it is now. On a ride tested, the vehicle the
        // passenger was sitting in read 52, 57, 55, 58 and 54 m away — always
        // just outside a fixed 60 m radius — because at 45 km/h a report a few
        // seconds old is already half a block behind. Only when the bus stood
        // still did it read 7 m.
        //
        // So the radius is widened by however far we ourselves have travelled
        // since the report was made. Stationary, it stays at the base value.
        val nearby = realtimeRepo.getAllVehicles()
            .map { v ->
                val age = if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else 0
                val allowance = userSpeedMps * age
                val d = LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon)
                Triple(v, d, d - allowance)      // third = staleness-corrected
            }
            .filter { (_, _, corrected) -> corrected <= RIDING_WITH_RADIUS }
            .sortedBy { it.third }
            .map { it.first to it.third }

        if (nearby.isEmpty()) return null

        val (best, bestDist) = nearby.first()
        if (best.routeId == trackedRouteId) return null      // our own line

        // Anything else close by makes the identification a guess.
        val runnerUp = nearby.getOrNull(1)
        if (runnerUp != null && runnerUp.second - bestDist < AMBIGUITY_MARGIN) return null
        // No second candidate at all, or one far behind: the identification is
        // as clear as this data can be.
        val uncontested = runnerUp == null ||
            runnerUp.second - bestDist >= UNCONTESTED_MARGIN

        val name = try {
            gtfsRepo.getRouteById(best.routeId)?.routeShortName
        } catch (e: Exception) { null } ?: return null

        val headsign = try {
            gtfsRepo.getHeadsignByTripIdPrefix(best.tripId)
        } catch (e: Exception) { null }
        val type = try {
            gtfsRepo.getRouteById(best.routeId)?.routeType ?: 3
        } catch (e: Exception) { 3 }

        FileLogger.i(TAG, "Foreign vehicle ${bestDist.toInt()} m away: route $name" +
            (headsign?.let { " → $it" } ?: " (direction unknown)"))
        return ForeignVehicle(
            best.routeId, name, bestDist, uncontested, best.tripId, type, headsign)
    }

    /**
     * Whether ANY vehicle of [routeId] is plausibly near the passenger.
     *
     * Answers the cheaper of the two questions. Establishing which line
     * someone is on needs several agreeing observations, because one
     * coincidence proves nothing; establishing that they are NOT on a
     * particular line needs only to see that none of its vehicles is
     * anywhere near — and a vehicle a kilometre away cannot be explained by a
     * few seconds of stale reporting.
     *
     * Returns null when the feed has no vehicles for the route at all, which
     * is not the same as "far away": the line may simply not be reporting,
     * and no conclusion should be drawn from that.
     */
    suspend fun isAnyVehicleOfRouteNear(
        routeId: String,
        userLat: Double,
        userLon: Double,
        userSpeedMps: Double = 0.0
    ): Boolean? {
        val vehicles = realtimeRepo.getVehiclesForRoute(routeId)
            .filter { it.lat != 0.0 || it.lon != 0.0 }
        if (vehicles.isEmpty()) return null

        val nowSec = System.currentTimeMillis() / 1000
        val nearest = vehicles.minOf { v ->
            val age = if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else 0
            LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon) - userSpeedMps * age
        }
        FileLogger.i(TAG, "Nearest $routeId vehicle: ${nearest.toInt()} m (corrected)")
        return nearest <= NOT_ABOARD_RADIUS
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
