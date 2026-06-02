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

    /**
     * Like [getHeadsignCountsForRoute] but only counts trips that actually
     * pass through the given stop. This is how we determine the correct
     * direction(s) for a route AT A SPECIFIC STOP: a stop is served by only
     * one direction of a line (the opposite direction uses the stop across
     * the street), so this returns just the headsign(s) relevant here.
     * Used as a headsign fallback when the realtime feed omits one.
     */
    @Query("""
        SELECT t.tripHeadsign AS headsign, COUNT(*) AS tripCount
        FROM trips t
        WHERE t.routeId = :routeId
          AND t.tripHeadsign IS NOT NULL
          AND EXISTS (
              SELECT 1 FROM stop_times st
              WHERE st.tripId = t.tripId AND st.stopId = :stopId
          )
        GROUP BY t.tripHeadsign
        ORDER BY tripCount DESC
    """)
    suspend fun getHeadsignCountsForRouteAtStop(routeId: String, stopId: String): List<HeadsignCount>

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
