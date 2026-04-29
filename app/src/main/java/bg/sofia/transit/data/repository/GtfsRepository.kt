package bg.sofia.transit.data.repository

import android.content.Context
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.data.db.TransitDatabase
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.db.entity.*
import bg.sofia.transit.data.parser.GtfsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GtfsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: TransitDatabase
) {
    private val stopDao         = db.stopDao()
    private val routeDao        = db.routeDao()
    private val tripDao         = db.tripDao()
    private val stopTimeDao     = db.stopTimeDao()
    private val calendarDateDao = db.calendarDateDao()

    companion object {
        private const val TAG = "GtfsRepository"
        /** Directory where the weekly worker stores fresh GTFS files. */
        const val EXTERNAL_DIR_NAME = "gtfs"
    }

    /**
     * Returns the directory holding the latest downloaded GTFS files,
     * or null if the worker has never produced one yet (so we should
     * fall back to bundled assets).
     */
    fun getActiveDataDir(): File? {
        val dir = File(context.filesDir, EXTERNAL_DIR_NAME)
        // Check that at least the four core files we need are present
        val required = listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
        val allPresent = dir.isDirectory && required.all { File(dir, it).exists() }
        return if (allPresent) dir else null
    }

    // ── Initialisation ────────────────────────────────────────────────────
    /**
     * Returns true if the DB is already populated (skip re-import).
     */
    suspend fun isDatabaseReady(): Boolean =
        stopDao.count() > 0 && routeDao.count() > 0

    /**
     * Parses all GTFS CSVs and populates Room. Reads from the external
     * directory if it exists (fresh data downloaded by the worker), otherwise
     * from the bundled assets. Call from a coroutine.
     *
     * Renamed from `initialiseFromAssets` to reflect that it can use either
     * source — public API kept as alias below for backward compatibility.
     */
    suspend fun loadStaticData(onProgress: (String) -> Unit = {}) {
        withContext(Dispatchers.IO) {
            val dataDir = getActiveDataDir()
            val source = if (dataDir != null) "downloaded" else "bundled"
            FileLogger.i(TAG, "Loading static data from $source source")

            onProgress("Зареждане на спирки…")
            val stops = GtfsParser.parseStops(context, dataDir)
            stopDao.deleteAll()
            stopDao.insertAll(stops)

            onProgress("Зареждане на линии…")
            val routes = GtfsParser.parseRoutes(context, dataDir)
            routeDao.deleteAll()
            routeDao.insertAll(routes)

            onProgress("Зареждане на пътувания…")
            val trips = GtfsParser.parseTrips(context, dataDir)
            tripDao.deleteAll()
            tripDao.insertAll(trips)

            onProgress("Зареждане на разписания…")
            stopTimeDao.deleteAll()
            GtfsParser.parseStopTimes(context, dataDir) { batch ->
                stopTimeDao.insertAll(batch)
            }

            onProgress("Зареждане на работни дни…")
            val calendar = GtfsParser.parseCalendarDates(context, dataDir)
            calendarDateDao.deleteAll()
            // Insert in batches of 5000 to avoid hitting SQLite limits
            calendar.chunked(5_000).forEach { calendarDateDao.insertAll(it) }

            onProgress("Готово")
            FileLogger.i(TAG, "DB loaded ($source): stops=${stops.size} routes=${routes.size} " +
                       "trips=${trips.size} calendar=${calendar.size}")
        }
    }

    /** Backward-compatible alias for code that still calls the old name. */
    suspend fun initialiseFromAssets(onProgress: (String) -> Unit = {}) =
        loadStaticData(onProgress)


    // ── Stops ─────────────────────────────────────────────────────────────
    /**
     * Returns up to [limit] stops near the given coordinates. Uses a small
     * bounding-box pre-filter so SQLite can leverage its B-tree indexes on
     * stopLat/stopLon (≈ 30-100 candidate rows scanned instead of 4400+).
     *
     * @param boxKm Half-side of the bounding box in kilometres. Default 1.0
     *              means stops can be up to ~1 km from the user (the box is
     *              2 km × 2 km, diagonal ~1.4 km).
     */
    suspend fun getNearestStops(
        lat: Double,
        lon: Double,
        limit: Int = 20,
        boxKm: Double = 1.0
    ): List<bg.sofia.transit.data.db.entity.Stop> {
        // Latitude: 1° ≈ 111 km worldwide.
        // Longitude: 1° ≈ 111 km × cos(latitude). At Sofia (≈42.7°N) → 81.6 km.
        val dLat = boxKm / 111.0
        val dLon = boxKm / (111.0 * Math.cos(Math.toRadians(lat)))
        return stopDao.getNearestStopsInBox(
            lat = lat, lon = lon,
            minLat = lat - dLat, maxLat = lat + dLat,
            minLon = lon - dLon, maxLon = lon + dLon,
            limit  = limit
        )
    }

    suspend fun getStopById(id: String) = stopDao.getById(id)

    /** Bulk lookup of trips by ID — for diagnostics & realtime resolution. */
    suspend fun getTripsByIds(ids: List<String>) = tripDao.getByIds(ids)

    /** Bulk lookup of routes by ID. */
    suspend fun getRoutesByIds(ids: List<String>) =
        ids.distinct().mapNotNull { routeDao.getById(it) }

    /**
     * Returns real-time arrivals for a stop, with headsigns resolved from
     * the static DB. The realtime feed often returns empty trip_headsign,
     * so before calling the realtime helper we pre-build a map of
     * tripId → headsign from the static Trips table.
     *
     * To avoid a double network call, we use the realtime cache: the first
     * call to lookupStop populates the cache, and the subsequent
     * getArrivalsForStop reuses it.
     */
    suspend fun getArrivalsForStop(
        stopId: String,
        realtimeRepo: RealtimeRepository
    ): List<ArrivalInfo> {
        FileLogger.i("GtfsRepo", "getArrivalsForStop start, stopId=$stopId")

        // 1. routeId → shortName map (so UI shows "76" instead of "A81")
        val routes = routeDao.getRoutesForStops(listOf(stopId))
        val routeShortNames = routes.associate { it.routeId to it.routeShortName }
        FileLogger.i("GtfsRepo", "Routes serving $stopId: ${routes.size}")

        // 2. Get the trip IDs that touch this stop in the realtime feed.
        //    lookupStop returns up to 10 future arrivals — but we want every
        //    trip touching this stop, future-or-past, so we can resolve
        //    headsigns. The realtime layer will filter out past times.
        val rawTripIds = realtimeRepo.getTripIdsTouchingStop(stopId)
        FileLogger.i("GtfsRepo", "Realtime trips touching $stopId: ${rawTripIds.size}")

        // 3. Resolve headsigns from the static DB
        val staticTrips = tripDao.getByIds(rawTripIds).associateBy { it.tripId }
        val headsignsByTripId = rawTripIds.associateWith { tripId ->
            staticTrips[tripId]?.tripHeadsign ?: "—"
        }

        // 4. Now ask the realtime helper for the actual arrivals (only future ones)
        val arrivals = realtimeRepo.getArrivalsForStop(
            stopId          = stopId,
            routeShortNames = routeShortNames,
            routeHeadsigns  = headsignsByTripId
        )
        FileLogger.i("GtfsRepo", "getArrivalsForStop returning ${arrivals.size} ArrivalInfo records")
        return arrivals
    }

    // ── Diagnostics ───────────────────────────────────────────────────────
    /** Total stop count + a few example stop_ids (for diagnostic screen). */
    suspend fun getSampleStops(limit: Int = 5): SampleStops {
        val all = stopDao.getAllStops()
        return SampleStops(
            totalCount = all.size,
            samples    = all.take(limit).map { "${it.stopId}  (${it.stopName})" }
        )
    }

    /** All stop_ids known to the static feed — for cross-check vs realtime. */
    suspend fun getAllStopIds(): Set<String> =
        stopDao.getAllStops().map { it.stopId }.toSet()

    /** All route_ids known to the static feed — for cross-check vs realtime. */
    suspend fun getAllRouteIdsSet(): Set<String> =
        routeDao.getAllRoutesOnce().map { it.routeId }.toSet()

    // ── Routes ────────────────────────────────────────────────────────────
    fun getAllRoutes(): Flow<List<Route>> = routeDao.getAllRoutes()

    suspend fun getRoutesByType(type: Int) = routeDao.getByType(type)

    suspend fun getRouteById(id: String) = routeDao.getById(id)

    suspend fun getRoutesForStops(stopIds: List<String>) =
        routeDao.getRoutesForStops(stopIds)

    // ── Trips / Directions ────────────────────────────────────────────────
    /**
     * Returns at most TWO directions for the given route — the ones with the
     * most scheduled trips (i.e. the standard outbound and inbound).
     * Depot runs, partial routes and variants (typically 1–15 trips per day)
     * are deliberately filtered out to avoid confusing visually-impaired
     * users in the Lines screen. Their actual presence on the road is still
     * surfaced in the Journey screen via real-time trip_updates.
     */
    suspend fun getDirectionsForRoute(routeId: String): List<Trip> {
        // Pick the two most-trafficked headsigns
        val topHeadsigns = tripDao.getHeadsignCountsForRoute(routeId)
                                   .take(2)
                                   .map { it.headsign }
        if (topHeadsigns.isEmpty()) return emptyList()

        // Take one representative Trip per headsign so the UI has a
        // tripId/directionId to navigate further
        val allTrips = tripDao.getByRoute(routeId)
        return topHeadsigns.mapNotNull { hs ->
            allTrips.firstOrNull { it.tripHeadsign == hs }
        }
    }

    suspend fun getTripsForRoute(routeId: String) = tripDao.getByRoute(routeId)

    suspend fun getTripById(id: String) = tripDao.getById(id)

    /**
     * Returns the ordered list of stops for the *first* trip that matches
     * the given route + headsign. Sufficient for showing the route map.
     */
    suspend fun getStopsForDirection(routeId: String, headsign: String): List<StopWithSequence> {
        val trip = tripDao.getByRoute(routeId)
                         .firstOrNull { it.tripHeadsign == headsign }
                   ?: return emptyList()
        return stopTimeDao.getStopsForTrip(trip.tripId)
    }

    // ── Schedules ─────────────────────────────────────────────────────────

    /**
     * Returns the schedule for a specific direction at a specific stop, on
     * a specific date. The date is used to filter by service_id — so weekday
     * trips, weekend trips and holiday trips are correctly separated.
     *
     * If [date] is null, returns the unfiltered (all-day-types) schedule.
     */
    suspend fun getScheduleForDirectionAtStop(
        routeId: String,
        headsign: String,
        stopId: String,
        date: String? = null
    ): List<String> {
        if (date == null) {
            return stopTimeDao.getScheduleForDirectionAtStop(routeId, headsign, stopId)
        }
        val activeServices = calendarDateDao.getActiveServicesForDate(date)
        if (activeServices.isEmpty()) {
            // No services scheduled for this date — likely outside feed range
            return emptyList()
        }
        return stopTimeDao.getScheduleForDirectionAtStopFiltered(
            routeId, headsign, stopId, activeServices
        )
    }

    // ── Calendar / Service Days ──────────────────────────────────────────
    /**
     * Returns service_ids active on the given YYYYMMDD date.
     */
    suspend fun getActiveServicesForDate(date: String): List<String> =
        calendarDateDao.getActiveServicesForDate(date)

    /**
     * Returns YYYYMMDD strings for all dates the feed has schedule data for.
     * Useful for picking a representative weekday / Saturday / Sunday so
     * we can show a "type-of-day" schedule rather than asking the user to
     * pick an exact date.
     */
    suspend fun getAllAvailableScheduleDates(): List<String> =
        calendarDateDao.getAllAvailableDates()

    /**
     * Given a tripId and the current stop index, returns the remaining stops.
     */
    suspend fun getRemainingStops(tripId: String, fromSequence: Int): List<StopWithSequence> {
        return stopTimeDao.getStopsForTrip(tripId)
                          .filter { it.stopSequence >= fromSequence }
    }

    // ── Realtime Trip Resolution (Journey screen) ─────────────────────────
    /**
     * Resolves raw GTFS-RT upcoming trips into user-facing data with the
     * actual headsign for each trip (looked up from the static Trips table).
     *
     * Then deduplicates: if the same (route, headsign) pair appears at
     * multiple nearby stops, keeps only the entry with the SOONEST arrival.
     *
     * Finally sorts by stop proximity (closest stop first), with arrival
     * time as the secondary sort key.
     *
     * Trips whose tripId is not present in the local DB are dropped — we
     * cannot tell where they're going, so it's safer not to offer them.
     *
     * @param stopInfo Maps each stopId to (stopName, distanceMetres).
     *                 Only trips at stops in this map are kept.
     */
    suspend fun resolveUpcomingTrips(
        rawTrips: List<bg.sofia.transit.data.repository.UpcomingTripRaw>,
        stopInfo: Map<String, Pair<String, Double>>
    ): List<UpcomingTripInfo> {
        if (rawTrips.isEmpty()) return emptyList()

        // 1. Bulk-fetch trips and routes referenced by the realtime feed
        val tripsById  = tripDao.getByIds(rawTrips.map { it.tripId }.distinct())
                                 .associateBy { it.tripId }
        val routesById = rawTrips.map { it.routeId }.distinct()
                                 .mapNotNull { routeDao.getById(it) }
                                 .associateBy { it.routeId }

        // 2. Enrich + drop entries we cannot resolve
        val enriched = rawTrips.mapNotNull { raw ->
            val trip  = tripsById[raw.tripId]   ?: return@mapNotNull null
            val route = routesById[raw.routeId] ?: return@mapNotNull null
            val (stopName, dist) = stopInfo[raw.stopId] ?: return@mapNotNull null
            UpcomingTripInfo(
                tripId             = raw.tripId,
                routeId            = raw.routeId,
                routeShortName     = route.routeShortName,
                routeType          = route.routeType,
                headsign           = trip.tripHeadsign ?: "—",
                stopId             = raw.stopId,
                stopName           = stopName,
                stopDistanceMetres = dist,
                arrivalEpoch       = raw.arrivalEpoch
            )
        }

        // 3. Deduplicate by (routeId + headsign), keeping the SOONEST arrival.
        //    Use case: same line+direction visible at two nearby stops.
        val deduped = enriched
            .groupBy { "${it.routeId}|${it.headsign}" }
            .map { (_, candidates) -> candidates.minBy { it.arrivalEpoch } }

        // 4. Sort by proximity to user, then by arrival time as tie-breaker.
        return deduped.sortedWith(
            compareBy({ it.stopDistanceMetres }, { it.arrivalEpoch })
        )
    }
}

/**
 * Result of a sample-stops diagnostic query.
 */
data class SampleStops(
    val totalCount: Int,
    val samples: List<String>
)

/**
 * User-facing data for an upcoming arrival, displayed in the Journey screen
 * after resolving realtime trips against the static schedule.
 */
data class UpcomingTripInfo(
    val tripId: String,
    val routeId: String,
    val routeShortName: String,
    val routeType: Int,
    val headsign: String,
    val stopId: String,
    val stopName: String,
    val stopDistanceMetres: Double,
    val arrivalEpoch: Long
) {
    fun minutesUntilArrival(): Int {
        val now  = java.time.Instant.now().epochSecond
        val secs = arrivalEpoch - now
        return (secs / 60).coerceAtLeast(0).toInt()
    }
}
