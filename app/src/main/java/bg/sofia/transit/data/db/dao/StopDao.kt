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
        GROUP BY s.stopCode
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
     * Returns all stop_ids that share the same stop_code. In the Sofia GTFS
     * data, the same physical stop is sometimes represented by two rows
     * with different prefixes — e.g. A1903 (bus) and TB1903 (trolley) both
     * have stop_code 1903 at identical coordinates. Each row carries the
     * stop_times only for vehicles of its own type, so to show every
     * arrival at the physical stop we must query all of them.
     */
    @Query("SELECT stopId FROM stops WHERE stopCode = :code")
    suspend fun getStopIdsByCode(code: String): List<String>

    /**
     * Searches for stops by code or name. Returns up to [limit] results.
     * - `query` is matched as a prefix against stop_code (case-insensitive)
     * - AND/OR as a substring against stop_name (case-insensitive)
     * Phantom stops (those with no stop_times rows) are excluded.
     * Ordered: code matches first, then name matches, alphabetical within each group.
     */
    @Query("""
        SELECT s.* FROM stops s
        WHERE s.locationType = 0
          AND EXISTS (SELECT 1 FROM stop_times st WHERE st.stopId = s.stopId LIMIT 1)
          AND (
              s.stopCode LIKE :prefix || '%'
              OR UPPER(s.stopName) LIKE '%' || UPPER(:query) || '%'
          )
        GROUP BY s.stopCode
        ORDER BY
            CASE WHEN s.stopCode LIKE :prefix || '%' THEN 0 ELSE 1 END,
            s.stopName
        LIMIT :limit
    """)
    suspend fun searchStops(query: String, prefix: String, limit: Int): List<Stop>

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun count(): Int

    @Query("DELETE FROM stops")
    suspend fun deleteAll()
}
