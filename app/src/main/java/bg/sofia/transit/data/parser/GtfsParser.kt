package bg.sofia.transit.data.parser

import android.content.Context
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.data.db.entity.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses GTFS CSV files. Two sources are supported, in order of preference:
 *
 *   1. An external directory (e.g. /data/data/<pkg>/files/gtfs/) — used after
 *      the weekly background update has successfully downloaded fresh data.
 *   2. The bundled assets/gtfs/ folder — used on first launch, or as fallback
 *      if the external directory does not contain the requested file.
 *
 * All public methods accept an optional `dataDir`. Pass `null` to read only
 * from assets; pass a directory to read updated files from there with assets
 * as fallback.
 */
object GtfsParser {

    private const val TAG = "GtfsParser"

    /** Opens [name] from [dataDir] if it exists there, otherwise from assets. */
    private fun openFile(context: Context, dataDir: File?, name: String): InputStream {
        if (dataDir != null) {
            val f = File(dataDir, name)
            if (f.exists() && f.length() > 0) {
                FileLogger.d(TAG, "Reading $name from external dir")
                return f.inputStream()
            }
        }
        FileLogger.d(TAG, "Reading $name from assets")
        return context.assets.open("gtfs/$name")
    }

    // ── Stops ────────────────────────────────────────────────────────────────
    suspend fun parseStops(
        context: Context,
        dataDir: File? = null
    ): List<Stop> = withContext(Dispatchers.IO) {
        val stops = mutableListOf<Stop>()
        openFile(context, dataDir, "stops.txt").use { input ->
            val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
            val header = reader.readLine()?.split(",") ?: return@withContext emptyList<Stop>()
            val idx = header.mapIndexed { i, col -> col.trim().removeSurrounding("\"") to i }.toMap()

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val cols = parseCsvLine(line)
                try {
                    val locType = cols.getOrNull(idx["location_type"] ?: -1)?.toIntOrNull() ?: 0
                    stops.add(
                        Stop(
                            stopId       = cols[idx["stop_id"]!!].trim(),
                            stopCode     = cols.getOrNull(idx["stop_code"] ?: -1)?.takeIf { it.isNotBlank() },
                            stopName     = cols.getOrNull(idx["stop_name"] ?: -1)?.trim() ?: "",
                            stopLat      = cols.getOrNull(idx["stop_lat"] ?: -1)?.toDoubleOrNull() ?: 0.0,
                            stopLon      = cols.getOrNull(idx["stop_lon"] ?: -1)?.toDoubleOrNull() ?: 0.0,
                            locationType = locType,
                            parentStation = cols.getOrNull(idx["parent_station"] ?: -1)?.takeIf { it.isNotBlank() }
                        )
                    )
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Skip stop line: ${e.message}")
                }
            }
        }
        FileLogger.i(TAG, "Parsed ${stops.size} stops")
        stops
    }

    // ── Routes ───────────────────────────────────────────────────────────────
    suspend fun parseRoutes(
        context: Context,
        dataDir: File? = null
    ): List<Route> = withContext(Dispatchers.IO) {
        val routes = mutableListOf<Route>()
        openFile(context, dataDir, "routes.txt").use { input ->
            val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
            val header = reader.readLine()?.split(",") ?: return@withContext emptyList<Route>()
            val idx = header.mapIndexed { i, col -> col.trim().removeSurrounding("\"") to i }.toMap()

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val cols = parseCsvLine(line)
                try {
                    routes.add(
                        Route(
                            routeId       = cols[idx["route_id"]!!].trim(),
                            agencyId      = cols.getOrNull(idx["agency_id"] ?: -1)?.trim() ?: "A",
                            routeShortName = cols.getOrNull(idx["route_short_name"] ?: -1)?.trim() ?: "",
                            routeLongName  = cols.getOrNull(idx["route_long_name"] ?: -1)?.trim() ?: "",
                            routeType     = cols.getOrNull(idx["route_type"] ?: -1)?.toIntOrNull() ?: 3,
                            routeColor    = cols.getOrNull(idx["route_color"] ?: -1)?.takeIf { it.isNotBlank() },
                            routeTextColor = cols.getOrNull(idx["route_text_color"] ?: -1)?.takeIf { it.isNotBlank() },
                            routeSortOrder = cols.getOrNull(idx["route_sort_order"] ?: -1)?.toIntOrNull()
                        )
                    )
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Skip route line: ${e.message}")
                }
            }
        }
        FileLogger.i(TAG, "Parsed ${routes.size} routes")
        routes
    }

    // ── Trips ────────────────────────────────────────────────────────────────
    suspend fun parseTrips(
        context: Context,
        dataDir: File? = null
    ): List<Trip> = withContext(Dispatchers.IO) {
        val trips = mutableListOf<Trip>()
        openFile(context, dataDir, "trips.txt").use { input ->
            val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
            val header = reader.readLine()?.split(",") ?: return@withContext emptyList<Trip>()
            val idx = header.mapIndexed { i, col -> col.trim().removeSurrounding("\"") to i }.toMap()

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val cols = parseCsvLine(line)
                try {
                    trips.add(
                        Trip(
                            tripId       = cols[idx["trip_id"]!!].trim(),
                            routeId      = cols[idx["route_id"]!!].trim(),
                            serviceId    = cols[idx["service_id"]!!].trim(),
                            tripHeadsign = cols.getOrNull(idx["trip_headsign"] ?: -1)?.trim()?.takeIf { it.isNotBlank() },
                            directionId  = cols.getOrNull(idx["direction_id"] ?: -1)?.toIntOrNull(),
                            shapeId      = cols.getOrNull(idx["shape_id"] ?: -1)?.trim()?.takeIf { it.isNotBlank() }
                        )
                    )
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Skip trip line: ${e.message}")
                }
            }
        }
        FileLogger.i(TAG, "Parsed ${trips.size} trips")
        trips
    }

    // ── Stop Times (streamed in batches to avoid OOM) ─────────────────────
    suspend fun parseStopTimes(
        context: Context,
        dataDir: File? = null,
        onBatch: suspend (List<StopTime>) -> Unit
    ) = withContext(Dispatchers.IO) {
        val batch = mutableListOf<StopTime>()
        val batchSize = 5_000
        var totalCount = 0

        openFile(context, dataDir, "stop_times.txt").use { input ->
            val reader = BufferedReader(InputStreamReader(input, "UTF-8"), 1024 * 64)
            val header = reader.readLine()?.split(",") ?: return@withContext
            val idx = header.mapIndexed { i, col -> col.trim().removeSurrounding("\"") to i }.toMap()

            reader.forEachLine { line ->
                if (line.isBlank()) return@forEachLine
                val cols = parseCsvLine(line)
                try {
                    batch.add(
                        StopTime(
                            tripId       = cols[idx["trip_id"]!!].trim(),
                            arrivalTime  = cols.getOrNull(idx["arrival_time"] ?: -1)?.trim() ?: "00:00:00",
                            departureTime = cols.getOrNull(idx["departure_time"] ?: -1)?.trim() ?: "00:00:00",
                            stopId       = cols[idx["stop_id"]!!].trim(),
                            stopSequence = cols.getOrNull(idx["stop_sequence"] ?: -1)?.toIntOrNull() ?: 0,
                            timepoint    = cols.getOrNull(idx["timepoint"] ?: -1)?.toIntOrNull() ?: 0
                        )
                    )
                    totalCount++
                    if (batch.size >= batchSize) {
                        ensureActive()
                        kotlinx.coroutines.runBlocking { onBatch(batch.toList()) }
                        batch.clear()
                    }
                } catch (e: Exception) {
                    // Skip malformed line silently to keep parser robust
                }
            }
            if (batch.isNotEmpty()) {
                kotlinx.coroutines.runBlocking { onBatch(batch.toList()) }
            }
        }
        FileLogger.i(TAG, "Parsed $totalCount stop times")
    }

    // ── Calendar Dates ───────────────────────────────────────────────────────
    suspend fun parseCalendarDates(
        context: Context,
        dataDir: File? = null
    ): List<CalendarDate> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<CalendarDate>()
        try {
            openFile(context, dataDir, "calendar_dates.txt").use { input ->
                val reader = BufferedReader(InputStreamReader(input, "UTF-8"))
                val header = reader.readLine()?.split(",") ?: return@withContext emptyList<CalendarDate>()
                val idx = header.mapIndexed { i, col -> col.trim() to i }.toMap()

                reader.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val cols = parseCsvLine(line)
                    try {
                        entries.add(
                            CalendarDate(
                                serviceId     = cols[idx["service_id"]!!].trim(),
                                date          = cols[idx["date"]!!].trim(),
                                exceptionType = cols[idx["exception_type"]!!].trim().toInt()
                            )
                        )
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "calendar_dates.txt not found or error: ${e.message}")
        }
        entries
    }

    // ── CSV line parser (handles quoted fields with commas) ──────────────────
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes  -> {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ }
                    else inQuotes = false
                }
                c == ',' && !inQuotes -> { result.add(sb.toString()); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        result.add(sb.toString())
        return result
    }
}
