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
    @SuppressWarnings(RoomWarnings.CURSOR_MISMATCH)
    @Query("""
        SELECT *,
            ((:lat - stopLat) * (:lat - stopLat) +
             (:lon - stopLon) * (:lon - stopLon)) AS distSq
        FROM stops
        WHERE locationType = 0
          AND stopLat BETWEEN :minLat AND :maxLat
          AND stopLon BETWEEN :minLon AND :maxLon
        ORDER BY distSq ASC
        LIMIT :limit
    """)
    suspend fun getNearestStopsInBox(
        lat: Double, lon: Double,
        minLat: Double, maxLat: Double,
        minLon: Double, maxLon: Double,
        limit: Int = 20
    ): List<Stop>

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun count(): Int

    @Query("DELETE FROM stops")
    suspend fun deleteAll()
}
