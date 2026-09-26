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
         * vehicle may be the one we are in. Whether a reading settles which
         * one is decided against DECISIVE_MARGIN, and a candidate still has
         * to be confirmed across readings in JourneyService.
         *
         * Narrowed from 60 m once a lone candidate became sufficient on its
         * own: a wide radius admits vehicles from the next carriageway, and
         * every extra one inside it is another reason to wait. Measured
         * sightings of the vehicle actually carrying the passenger read 0–1 m,
         * so 30 leaves ample room for a poor fix without inviting company.
         */
        const val RIDING_WITH_RADIUS = 30.0

        /**
         * How much nearer than every other vehicle in range the nearest must
         * be for a reading to count as decisive.
         *
         * Two vehicles running together read 0 m and 4 m, or 0 m and 0 m —
         * differences well inside the scatter of a phone's position. Picking
         * the nearer of such a pair is a coin toss, and two coin tosses that
         * happened to agree once confirmed the wrong line in principle and
         * the right one only by luck. A reading like that is recorded, but it
         * votes for nobody.
         */
        const val DECISIVE_MARGIN = 15.0
    }



    /** One vehicle as seen in one reading of the feed. */
    data class Sighting(
        val tripId: String,
        val routeId: String,
        /** Distance from us, corrected for the age of the report. */
        val distanceMetres: Double,
        /** Report time, epoch seconds; 0 if the feed gave none. */
        val timestamp: Long,
        /** Age of the report when read, in seconds; -1 if unknown. */
        val ageSec: Long,
        /** Reported position, as given by the feed (not corrected). */
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        /**
         * Compass bearing of travel as the feed gives it, degrees; null when
         * the feed carries none. Whether it can be trusted is the caller's
         * judgement — see JourneyService.vehicleHeading.
         */
        val bearing: Float? = null
    )

    /** The vehicle the passenger appears to be travelling in. */
    data class RidingVehicle(
        val routeId: String,
        val routeShortName: String,
        val routeType: Int,
        val tripId: String,
        /** Direction, from the trip id; null when the prefix is unknown. */
        val headsign: String?,
        val distanceMetres: Double,
        /** True when another vehicle was also within range — whether that
         *  leaves the reading undecided is judged against DECISIVE_MARGIN. */
        val contested: Boolean,
        /** Report time, so callers can insist on a second, fresher reading. */
        val timestamp: Long,
        /** How old that report was when taken, in seconds; -1 if unknown. */
        val reportAgeSec: Long,
        /**
         * Every vehicle within riding range, nearest first — the nearest
         * included. Kept whole rather than as a single runner-up distance:
         * which vehicles were beside us is what lets the next reading tell
         * that the pair has separated, and which of them stayed.
         */
        val inRange: List<Sighting>,
        /**
         * The vehicles asked about in [findRidingVehicle]'s watch list, as
         * they appear in this same reading, wherever they are. Absent from
         * the map means absent from the feed.
         */
        val watched: Map<String, Sighting>
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
     * Null when no vehicle is within range at all, or when the nearest one's
     * line cannot be looked up. When several are in range the nearest is
     * still returned, with all of them listed in [RidingVehicle.inRange]:
     * whether such a reading is decisive is the caller's judgement, made
     * against [DECISIVE_MARGIN].
     *
     * [watchTripIds] are vehicles seen beside us at the previous reading;
     * their positions in this reading come back in [RidingVehicle.watched],
     * so the caller can tell a pair that has separated from one whose other
     * half merely failed to report.
     */
    suspend fun findRidingVehicle(
        userLat: Double,
        userLon: Double,
        userSpeedMps: Double,
        watchTripIds: Collection<String> = emptyList()
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
                // No ceiling on the allowance.
                //
                // One was tried and removed. Any figure chosen for it — in
                // metres or in seconds — is a guess at what happened during
                // the interval the data does not cover, and a guess cannot be
                // made accurate by tuning. The error here does not grow with
                // the age of the report as such, but with how far the other
                // vehicle's movement may have differed from ours: over three
                // seconds nothing can differ much, over thirty a bus in a
                // clear lane may cover three hundred metres while we crawl
                // fifty.
                //
                // What guards against adopting the wrong vehicle is not a
                // better estimate of a single moment but watching how a
                // candidate behaves — see the confirmation rule in
                // JourneyService, which asks for the same vehicle twice on
                // separate reports.
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

        // Company no longer suppresses the answer, nor settles it: every
        // vehicle in range is reported, and the caller decides whether the
        // nearest stands far enough apart to count. See DECISIVE_MARGIN.
        val contested = runnerUp != null
        if (contested) {
            FileLogger.d(TAG, "In range: " + ranked.joinToString(", ") { (v, d) ->
                "${v.routeId}/${v.tripId}@${d.toInt()} m" })
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
        fun sighting(v: VehicleInfo, d: Double) = Sighting(
            v.tripId, v.routeId, d, v.timestamp,
            if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else -1,
            v.lat, v.lon, v.bearing)

        val inRange = ranked.map { (v, d) -> sighting(v, d) }
        val watchSet = watchTripIds.toSet()
        val watched = if (watchSet.isEmpty()) emptyMap() else
            all.filter { (v, _) -> v.tripId in watchSet }
               .associate { (v, d) -> v.tripId to sighting(v, d) }

        return RidingVehicle(
            best.routeId, name, type, best.tripId, headsign, dist,
            contested, best.timestamp, ageSec, inRange, watched)
    }



    /**
     * The same reading, told from the point of view of another vehicle in it.
     *
     * Needed when a tie among vehicles in range is broken in favour of the
     * line the passenger chose and that vehicle is not the nearest, so the
     * reading as returned names a different one.
     */
    suspend fun describe(s: Sighting, reading: RidingVehicle): RidingVehicle? {
        val route = try { gtfsRepo.getRouteById(s.routeId) } catch (e: Exception) { null }
            ?: return null
        val headsign = try {
            gtfsRepo.getHeadsignByTripIdPrefix(s.tripId)
        } catch (e: Exception) { null }
        FileLogger.i(TAG, "Riding vehicle (tie broken): ${route.routeShortName} → " +
            "${headsign ?: "?"} at ${s.distanceMetres.toInt()} m, report ${s.ageSec}s old " +
            "(trip=${s.tripId})")
        return reading.copy(
            routeId = s.routeId,
            routeShortName = route.routeShortName,
            routeType = route.routeType,
            tripId = s.tripId,
            headsign = headsign,
            distanceMetres = s.distanceMetres,
            timestamp = s.timestamp,
            reportAgeSec = s.ageSec)
    }

    /**
     * Where the tracked vehicle is relative to the passenger right now, or
     * null when the feed has nothing usable for it. Used both to confirm we
     * are following the right vehicle and to notice that we have parted from
     * it — once we have, the gap grows and keeps growing.
     *
     * Corrected for the age of the report exactly as in [findRidingVehicle].
     * It used not to be: a report thirty seconds old put the bus we were
     * sitting in over three hundred metres away at city speed, which is
     * further than the parting radius — the tracked vehicle and a candidate
     * were being measured by two different rulers.
     */
    suspend fun sightTrackedVehicle(
        tripId: String,
        userLat: Double,
        userLon: Double,
        userSpeedMps: Double
    ): Sighting? {
        val v = realtimeRepo.getVehicleForTrip(tripId) ?: return null
        if (v.lat == 0.0 && v.lon == 0.0) return null
        val nowSec = System.currentTimeMillis() / 1000
        val age = if (v.timestamp > 0) (nowSec - v.timestamp).coerceAtLeast(0) else -1
        if (age > MAX_POSITION_AGE_SEC) return null
        val raw = LocationHelper.distanceMetres(userLat, userLon, v.lat, v.lon)
        val corrected = (raw - userSpeedMps * age.coerceAtLeast(0)).coerceAtLeast(0.0)
        return Sighting(v.tripId, v.routeId, corrected, v.timestamp, age,
            v.lat, v.lon, v.bearing)
    }

}
