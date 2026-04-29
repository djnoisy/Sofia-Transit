package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.Trip

/** Aggregated row used to find the two main directions of a route. */
data class HeadsignCount(val headsign: String, val tripCount: Int)

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(trips: List<Trip>)

    @Query("SELECT * FROM trips WHERE routeId = :routeId")
    suspend fun getByRoute(routeId: String): List<Trip>

    @Query("""
        SELECT DISTINCT t.* FROM trips t
        WHERE t.routeId = :routeId
        GROUP BY t.directionId, t.tripHeadsign
    """)
    suspend fun getDirectionsForRoute(routeId: String): List<Trip>

    /**
     * Returns the headsigns for a route together with how many trips each
     * has. Sorted by trip count descending — the first two entries are the
     * standard directions, anything beyond is a depot run / variant which
     * we deliberately hide from users.
     */
    @Query("""
        SELECT tripHeadsign AS headsign, COUNT(*) AS tripCount
        FROM trips
        WHERE routeId = :routeId AND tripHeadsign IS NOT NULL
        GROUP BY tripHeadsign
        ORDER BY tripCount DESC
    """)
    suspend fun getHeadsignCountsForRoute(routeId: String): List<HeadsignCount>

    @Query("SELECT * FROM trips WHERE tripId = :id")
    suspend fun getById(id: String): Trip?

    /**
     * Fetches multiple trips by ID in a single query — used to resolve
     * realtime trip_updates against the static schedule (so we can show
     * actual headsigns, including depot runs).
     */
    @Query("SELECT * FROM trips WHERE tripId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Trip>

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
