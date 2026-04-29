package bg.sofia.transit.data.repository

import bg.sofia.transit.util.FileLogger
import com.google.transit.realtime.GtfsRealtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class ArrivalInfo(
    val routeId: String,
    val routeShortName: String,
    val headsign: String,
    val arrivals: List<String>   // HH:MM formatted times (up to 3)
)

data class VehicleInfo(
    val vehicleId: String,
    val tripId: String,
    val routeId: String,
    val lat: Double,
    val lon: Double,
    val bearing: Float?,
    val currentStopSequence: Int?,
    val currentStopId: String?,
    val timestamp: Long
)

/**
 * Raw upcoming-trip data straight from GTFS-RT, before headsign lookup.
 * The repository layer resolves these against the static Trips table to
 * produce user-facing data with real direction names.
 */
data class UpcomingTripRaw(
    val tripId: String,
    val routeId: String,
    val stopId: String,         // which nearby stop this arrival is for
    val arrivalEpoch: Long,     // seconds since epoch
    val directionId: Int?       // fallback if trip_id can't be resolved
)

@Singleton
class RealtimeRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TAG = "RealtimeRepo"
        private const val BASE_URL = "https://gtfs.sofiatraffic.bg/api/v1"
        private const val CACHE_TTL_MS = 20_000L      // 20 seconds
        private val SOFIA_ZONE = ZoneId.of("Europe/Sofia")
    }

    // ── Trip Updates ─────────────────────────────────────────────────────
    /**
     * Fetches GTFS-RT TripUpdates and returns real-time arrival times
     * for the given stop, grouped by route short name and headsign.
     */
    suspend fun getArrivalsForStop(
        stopId: String,
        routeShortNames: Map<String, String>,  // routeId -> shortName
        routeHeadsigns: Map<String, String>    // tripId  -> headsign (fallback)
    ): List<ArrivalInfo> = withContext(Dispatchers.IO) {
        try {
            FileLogger.i(TAG, "getArrivalsForStop: stopId=$stopId, knownRoutes=${routeShortNames.size}")

            val feed = fetchTripUpdates()
            if (feed == null) {
                FileLogger.w(TAG, "  ↳ feed is null (network/parse error)")
                return@withContext emptyList()
            }
            FileLogger.i(TAG, "  ↳ feed has ${feed.entityCount} entities")

            val now = Instant.now().epochSecond
            val formatter = DateTimeFormatter.ofPattern("HH:mm")
            val arrivals = mutableMapOf<String, MutableList<String>>()

            var tripUpdateCount = 0
            var matchingStopCount = 0
            var futureArrivalCount = 0

            for (entity in feed.entityList) {
                if (!entity.hasTripUpdate()) continue
                tripUpdateCount++
                val tu = entity.tripUpdate
                val routeId = tu.trip.routeId

                for (stu in tu.stopTimeUpdateList) {
                    if (stu.stopId != stopId) continue
                    matchingStopCount++

                    val arrTime = when {
                        stu.hasArrival() && stu.arrival.hasTime() -> stu.arrival.time
                        stu.hasDeparture() && stu.departure.hasTime() -> stu.departure.time
                        else -> continue
                    }
                    if (arrTime < now) continue
                    futureArrivalCount++

                    val rawHeadsign = tu.trip.tripHeadsign
                    val headsign = when {
                        rawHeadsign.isNotEmpty() -> rawHeadsign
                        else -> routeHeadsigns[tu.trip.tripId] ?: "—"
                    }
                    val key = "$routeId|$headsign"
                    val timeStr = Instant.ofEpochSecond(arrTime)
                        .atZone(SOFIA_ZONE)
                        .format(formatter)
                    arrivals.getOrPut(key) { mutableListOf() }.add(timeStr)
                }
            }

            FileLogger.i(TAG, "  ↳ tripUpdates=$tripUpdateCount, " +
                       "matchingStop=$matchingStopCount, " +
                       "futureArrivals=$futureArrivalCount, " +
                       "groupedArrivals=${arrivals.size}")

            arrivals.map { (key, times) ->
                val (routeId, headsign) = key.split("|", limit = 2)
                ArrivalInfo(
                    routeId       = routeId,
                    routeShortName = routeShortNames[routeId] ?: routeId,
                    headsign      = headsign,
                    arrivals      = times.sorted().take(3)
                )
            }.sortedWith(compareBy({ it.routeShortName }, { it.headsign }))

        } catch (e: Exception) {
            FileLogger.e(TAG, "Error fetching arrivals: ${e.message}")
            emptyList()
        }
    }

    // ── Upcoming Trips at Nearby Stops (for Journey screen) ───────────────
    /**
     * Returns all real-time upcoming trips arriving at any of the given stops
     * within the next [withinMinutes] minutes.
     *
     * The result is RAW — caller is responsible for resolving trip_id against
     * the static Trips table to obtain headsigns and dedupe by (route, headsign).
     */
    suspend fun getUpcomingTripsForStops(
        stopIds: Set<String>,
        withinMinutes: Int = 30
    ): List<UpcomingTripRaw> = withContext(Dispatchers.IO) {
        try {
            val feed = fetchTripUpdates() ?: return@withContext emptyList()
            val now    = Instant.now().epochSecond
            val cutoff = now + withinMinutes * 60
            val result = mutableListOf<UpcomingTripRaw>()

            for (entity in feed.entityList) {
                if (!entity.hasTripUpdate()) continue
                val tu = entity.tripUpdate
                val tripId  = tu.trip.tripId.takeIf { it.isNotEmpty() } ?: continue
                val routeId = tu.trip.routeId

                for (stu in tu.stopTimeUpdateList) {
                    if (stu.stopId !in stopIds) continue
                    val arr = when {
                        stu.hasArrival()   && stu.arrival.hasTime()   -> stu.arrival.time
                        stu.hasDeparture() && stu.departure.hasTime() -> stu.departure.time
                        else -> continue
                    }
                    if (arr < now || arr > cutoff) continue

                    result.add(
                        UpcomingTripRaw(
                            tripId        = tripId,
                            routeId       = routeId,
                            stopId        = stu.stopId,
                            arrivalEpoch  = arr,
                            directionId   = if (tu.trip.hasDirectionId()) tu.trip.directionId else null
                        )
                    )
                }
            }
            result
        } catch (e: Exception) {
            FileLogger.e(TAG, "Error fetching upcoming trips: ${e.message}")
            emptyList()
        }
    }

    // ── Vehicle Positions ─────────────────────────────────────────────────
    suspend fun getVehiclesForRoute(routeId: String): List<VehicleInfo> =
        withContext(Dispatchers.IO) {
            try {
                val feed = fetchVehiclePositions() ?: return@withContext emptyList()
                feed.entityList
                    .filter { it.hasVehicle() && it.vehicle.trip.routeId == routeId }
                    .map { entity ->
                        val v = entity.vehicle
                        VehicleInfo(
                            vehicleId          = v.vehicle.id ?: entity.id,
                            tripId             = v.trip.tripId,
                            routeId            = v.trip.routeId,
                            lat                = v.position.latitude.toDouble(),
                            lon                = v.position.longitude.toDouble(),
                            bearing            = if (v.position.hasBearing()) v.position.bearing else null,
                            currentStopSequence = if (v.hasCurrentStopSequence()) v.currentStopSequence else null,
                            currentStopId      = if (v.stopId.isNotEmpty()) v.stopId else null,
                            timestamp          = v.timestamp
                        )
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error fetching vehicles: ${e.message}")
                emptyList()
            }
        }

    /**
     * Finds which stop (by sequence) the vehicle on a given trip is at/approaching.
     */
    suspend fun getVehicleForTrip(tripId: String): VehicleInfo? =
        withContext(Dispatchers.IO) {
            try {
                val feed = fetchVehiclePositions() ?: return@withContext null
                feed.entityList
                    .firstOrNull { it.hasVehicle() && it.vehicle.trip.tripId == tripId }
                    ?.let { entity ->
                        val v = entity.vehicle
                        VehicleInfo(
                            vehicleId           = v.vehicle.id ?: entity.id,
                            tripId              = v.trip.tripId,
                            routeId             = v.trip.routeId,
                            lat                 = v.position.latitude.toDouble(),
                            lon                 = v.position.longitude.toDouble(),
                            bearing             = if (v.position.hasBearing()) v.position.bearing else null,
                            currentStopSequence = if (v.hasCurrentStopSequence()) v.currentStopSequence else null,
                            currentStopId       = if (v.stopId.isNotEmpty()) v.stopId else null,
                            timestamp           = v.timestamp
                        )
                    }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error fetching vehicle for trip: ${e.message}")
                null
            }
        }

    // ── Raw Fetchers (with short-lived cache) ─────────────────────────────
    /**
     * Cache for the binary protobuf responses. Realtime data is updated
     * every ~10–30 seconds upstream, so caching for [CACHE_TTL_MS] avoids
     * pointless duplicate network calls when the user opens/closes panels
     * quickly or when location updates fire rapidly.
     */
    private data class CachedFeed(val feed: GtfsRealtime.FeedMessage, val fetchedAtMs: Long)

    private val feedCache = java.util.concurrent.ConcurrentHashMap<String, CachedFeed>()

    private fun fetchTripUpdates(): GtfsRealtime.FeedMessage? =
        fetchProtoCached("$BASE_URL/trip-updates")

    private fun fetchVehiclePositions(): GtfsRealtime.FeedMessage? =
        fetchProtoCached("$BASE_URL/vehicle-positions")

    private fun fetchProtoCached(url: String): GtfsRealtime.FeedMessage? {
        val now    = System.currentTimeMillis()
        val cached = feedCache[url]
        if (cached != null && now - cached.fetchedAtMs < CACHE_TTL_MS) {
            return cached.feed
        }
        val fresh = fetchProto(url) ?: return cached?.feed   // serve stale on error
        feedCache[url] = CachedFeed(fresh, now)
        return fresh
    }

    private fun fetchProto(url: String): GtfsRealtime.FeedMessage? {
        return try {
            FileLogger.i(TAG, "Fetching $url")
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    FileLogger.w(TAG, "  ↳ HTTP ${resp.code} ${resp.message}")
                    null
                } else {
                    val bytes = resp.body?.bytes()
                    if (bytes == null) {
                        FileLogger.w(TAG, "  ↳ empty body")
                        null
                    } else {
                        FileLogger.i(TAG, "  ↳ received ${bytes.size} bytes")
                        GtfsRealtime.FeedMessage.parseFrom(bytes).also {
                            FileLogger.i(TAG, "  ↳ parsed feed: ${it.entityCount} entities")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "fetchProto failed for $url: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── Static GTFS download URL (for weekly update) ──────────────────────
    fun getStaticFeedUrl() = "$BASE_URL/static"

    // ── Diagnostics ───────────────────────────────────────────────────────
    /**
     * Fetches the trip-updates feed and returns a structured summary —
     * sample IDs from the feed plus error details. Used by the diagnostic
     * screen to verify that the API is reachable and that ID formats match
     * the static feed.
     */
    suspend fun diagnose(): RealtimeDiagnostics = withContext(Dispatchers.IO) {
        // Bypass the cache so the user can re-run after fixes
        feedCache.remove("$BASE_URL/trip-updates")
        try {
            val req = Request.Builder().url("$BASE_URL/trip-updates").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext RealtimeDiagnostics(
                        success = false,
                        errorMessage = "HTTP ${resp.code} ${resp.message}"
                    )
                }
                val bytes = resp.body?.bytes()
                    ?: return@withContext RealtimeDiagnostics(
                        success = false,
                        errorMessage = "Празно тяло на отговора"
                    )
                val feed = GtfsRealtime.FeedMessage.parseFrom(bytes)

                val stopIds  = mutableSetOf<String>()
                val routeIds = mutableSetOf<String>()
                val tripIds  = mutableSetOf<String>()
                var tripUpdateCount = 0

                for (entity in feed.entityList) {
                    if (!entity.hasTripUpdate()) continue
                    tripUpdateCount++
                    val tu = entity.tripUpdate
                    if (tu.trip.routeId.isNotEmpty()) routeIds.add(tu.trip.routeId)
                    if (tu.trip.tripId.isNotEmpty())  tripIds.add(tu.trip.tripId)
                    for (stu in tu.stopTimeUpdateList) {
                        if (stu.stopId.isNotEmpty()) stopIds.add(stu.stopId)
                    }
                }

                // Group by prefix (everything before the first digit)
                fun prefixOf(id: String): String =
                    id.takeWhile { !it.isDigit() }.ifEmpty { "?" }

                val stopsByPrefix  = stopIds.groupingBy(::prefixOf).eachCount()
                val routesByPrefix = routeIds.groupingBy(::prefixOf).eachCount()

                RealtimeDiagnostics(
                    success         = true,
                    bytesReceived   = bytes.size,
                    entityCount     = feed.entityCount,
                    tripUpdateCount = tripUpdateCount,
                    uniqueStopIds   = stopIds.size,
                    stopsByPrefix   = stopsByPrefix,
                    routesByPrefix  = routesByPrefix,
                    sampleStopIds   = stopIds.take(10),
                    sampleRouteIds  = routeIds.take(10),
                    sampleTripIds   = tripIds.take(5)
                )
            }
        } catch (e: Exception) {
            RealtimeDiagnostics(
                success = false,
                errorMessage = "${e.javaClass.simpleName}: ${e.message}"
            )
        }
    }

    /**
     * Compares the IDs from the realtime feed against those in the static
     * Room DB to detect ID-format mismatches (e.g. "A0170" vs "0170").
     */
    suspend fun compareWithStatic(gtfsRepo: GtfsRepository): RealtimeStaticMatch =
        withContext(Dispatchers.IO) {
            val staticStopIds  = gtfsRepo.getAllStopIds()
            val staticRouteIds = gtfsRepo.getAllRouteIdsSet()

            val feed = fetchTripUpdates()
                ?: return@withContext RealtimeStaticMatch(0, 0, 0, 0)

            val rtStopIds  = mutableSetOf<String>()
            val rtRouteIds = mutableSetOf<String>()
            for (entity in feed.entityList) {
                if (!entity.hasTripUpdate()) continue
                val tu = entity.tripUpdate
                if (tu.trip.routeId.isNotEmpty()) rtRouteIds.add(tu.trip.routeId)
                for (stu in tu.stopTimeUpdateList) {
                    if (stu.stopId.isNotEmpty()) rtStopIds.add(stu.stopId)
                }
            }

            RealtimeStaticMatch(
                totalStopIdsInFeed   = rtStopIds.size,
                matchingStopIds      = rtStopIds.count { it in staticStopIds },
                totalRouteIdsInFeed  = rtRouteIds.size,
                matchingRouteIds     = rtRouteIds.count { it in staticRouteIds }
            )
        }
    /**
     * Returns the set of trip_ids that have at least one stop_time_update
     * for the given stop_id in the realtime feed (regardless of past/future).
     * Used by GtfsRepository to bulk-resolve headsigns.
     */
    suspend fun getTripIdsTouchingStop(stopId: String): List<String> = withContext(Dispatchers.IO) {
        val feed = fetchTripUpdates() ?: return@withContext emptyList()
        val tripIds = mutableSetOf<String>()
        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate
            for (stu in tu.stopTimeUpdateList) {
                if (stu.stopId == stopId) {
                    if (tu.trip.tripId.isNotEmpty()) tripIds.add(tu.trip.tripId)
                    break
                }
            }
        }
        tripIds.toList()
    }

    /**
     * Inspects the realtime feed for arrivals at a specific stop_id.
     * Returns up to 10 raw arrival entries with route + headsign + ETA.
     * Used by diagnostics to verify a specific stop is reachable.
     */
    suspend fun lookupStop(stopId: String): StopLookupResult = withContext(Dispatchers.IO) {
        val feed = fetchTripUpdates()
            ?: return@withContext StopLookupResult(stopId, false, "Не може да се извлече feed", emptyList())

        val now = java.time.Instant.now().epochSecond
        val matches = mutableListOf<StopLookupEntry>()
        var totalForStop = 0
        var pastForStop  = 0

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate
            for (stu in tu.stopTimeUpdateList) {
                if (stu.stopId != stopId) continue
                totalForStop++

                val arrTime = when {
                    stu.hasArrival()   && stu.arrival.hasTime()   -> stu.arrival.time
                    stu.hasDeparture() && stu.departure.hasTime() -> stu.departure.time
                    else -> 0L
                }
                if (arrTime == 0L) continue
                if (arrTime < now) { pastForStop++; continue }

                matches.add(
                    StopLookupEntry(
                        routeId      = tu.trip.routeId,
                        tripId       = tu.trip.tripId,
                        headsign     = tu.trip.tripHeadsign.takeIf { it.isNotEmpty() },
                        secondsUntil = arrTime - now
                    )
                )
            }
        }

        StopLookupResult(
            stopId       = stopId,
            found        = totalForStop > 0,
            errorMessage = null,
            entries      = matches.sortedBy { it.secondsUntil }.take(10),
            totalForStop = totalForStop,
            pastForStop  = pastForStop
        )
    }
}

data class StopLookupEntry(
    val routeId: String,
    val tripId: String,
    val headsign: String?,
    val secondsUntil: Long
)

data class StopLookupResult(
    val stopId: String,
    val found: Boolean,
    val errorMessage: String?,
    val entries: List<StopLookupEntry>,
    val totalForStop: Int = 0,
    val pastForStop: Int = 0
)

/** Diagnostic result from a single realtime fetch. */
data class RealtimeDiagnostics(
    val success: Boolean,
    val errorMessage: String? = null,
    val bytesReceived: Int = 0,
    val entityCount: Int = 0,
    val tripUpdateCount: Int = 0,
    val uniqueStopIds: Int = 0,
    val stopsByPrefix: Map<String, Int> = emptyMap(),
    val routesByPrefix: Map<String, Int> = emptyMap(),
    val sampleStopIds: List<String> = emptyList(),
    val sampleRouteIds: List<String> = emptyList(),
    val sampleTripIds: List<String> = emptyList()
)

/** Cross-check between realtime feed IDs and static-DB IDs. */
data class RealtimeStaticMatch(
    val totalStopIdsInFeed: Int,
    val matchingStopIds: Int,
    val totalRouteIdsInFeed: Int,
    val matchingRouteIds: Int
)
