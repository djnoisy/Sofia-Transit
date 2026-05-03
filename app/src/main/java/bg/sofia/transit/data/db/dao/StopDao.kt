package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.Stop
@Dao
interface StopDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stops: List<Stop>)

    @Query("SELECT * FROM stops WHERE locationType = 0")
    suspend fun getAllStops(): List<Stop>

    @Query("SELECT * FROM stops WHERE stopId = :id")
    suspend fun getById(id: String): Stop?

    /**
     * Finds nearby stops using a bounding-box pre-filter to take advantage of
     * SQLite indexing on stop_lat / stop_lon, then sorts by squared Euclidean
     * distance (cheaper than haversine; sufficient for ranking at city scale).
     *
     * The caller passes a bounding box that comfortably surrounds the desired
     * search radius. ~0.01° latitude  ≈ 1.1 km, ~0.014° longitude in Sofia
     * ≈ 1.1 km — so 0.01/0.014 covers a square of roughly 2 km × 2 km.
     */
    /**
     * Returns the nearest [limit] stops within a bounding box around the
     * user, only including stops that are actually served by at least one
     * trip (stop_times row).
     *
     * The distance formula uses an "equirectangular" approximation where
     * longitude differences are scaled by cos(latitude) so that 1° of
     * longitude has roughly the same physical length as 1° of latitude
     * around Sofia (~42.7° N, where cos ≈ 0.735). Without this scaling,
     * stops slightly displaced in longitude appear deceptively close.
     */
    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query("""
        SELECT s.*,
            ((:lat - s.stopLat) * (:lat - s.stopLat) +
             (:lon - s.stopLon) * (:lon - s.stopLon) * :lonScale * :lonScale) AS distSq
        FROM stops s
        WHERE s.locationType = 0
          AND s.stopLat BETWEEN :minLat AND :maxLat
          AND s.stopLon BETWEEN :minLon AND :maxLon
          AND EXISTS (SELECT 1 FROM stop_times st WHERE st.stopId = s.stopId LIMIT 1)
        ORDER BY distSq ASC
        LIMIT :limit
    """)
    suspend fun getNearestStopsInBox(
        lat: Double,
        lon: Double,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        lonScale: Double,
        limit: Int
    ): List<Stop>

    /**
     * Diagnostic query that returns the same data as getNearestStopsInBox
     * but exposes the distSq value, so we can verify SQL is computing it
     * correctly. Returns a flat row class.
     */
    @Query("""
        SELECT s.stopId AS stopId,
               s.stopCode AS stopCode,
               s.stopName AS stopName,
               s.stopLat AS stopLat,
               s.stopLon AS stopLon,
               ((:lat - s.stopLat) * (:lat - s.stopLat) +
                (:lon - s.stopLon) * (:lon - s.stopLon) * :lonScale * :lonScale) AS distSq
        FROM stops s
        WHERE s.locationType = 0
          AND s.stopLat BETWEEN :minLat AND :maxLat
          AND s.stopLon BETWEEN :minLon AND :maxLon
          AND EXISTS (SELECT 1 FROM stop_times st WHERE st.stopId = s.stopId LIMIT 1)
        ORDER BY distSq ASC
        LIMIT :limit
    """)
    suspend fun getNearestStopsDiagnostic(
        lat: Double,
        lon: Double,
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double,
        lonScale: Double,
        limit: Int
    ): List<NearestStopRow>

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun count(): Int

    @Query("DELETE FROM stops")
    suspend fun deleteAll()
}

/** Diagnostic row with distSq — used by Diagnostics screen to verify ordering. */
data class NearestStopRow(
    val stopId: String,
    val stopCode: String?,
    val stopName: String,
    val stopLat: Double,
    val stopLon: Double,
    val distSq: Double
)
