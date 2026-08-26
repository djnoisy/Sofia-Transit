package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.Trip

/** Aggregated row used to find the two main directions of a route. */
data class HeadsignCount(val headsign: String, val tripCount: Int)

data class RouteHeadsignCount(val routeId: String, val headsign: String, val tripCount: Int)

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
     * Headsign counts for ALL routes in one query — used to build the
     * "DIRECTION1 - DIRECTION2" subtitle for every line in the Lines list
     * without issuing a per-route query. Grouped by (routeId, headsign),
     * ordered so the repository can pick the top-2 per route by iterating.
     */
    @Query("""
        SELECT routeId, tripHeadsign AS headsign, COUNT(*) AS tripCount
        FROM trips
        WHERE tripHeadsign IS NOT NULL AND tripHeadsign != ''
        GROUP BY routeId, tripHeadsign
        ORDER BY routeId, tripCount DESC
    """)
    suspend fun getHeadsignCountsAllRoutes(): List<RouteHeadsignCount>

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

    /**
     * Finds the most common headsign among static trips whose trip_id starts
     * with the given prefix (typically "ROUTE-SEGMENT-", e.g. "TB9-TB12-").
     * The second segment of a CGM trip_id encodes the direction/variant, so
     * this resolves the correct headsign for a realtime trip whose exact
     * trip_id isn't in our static data (the service-day suffix differs).
     * Returns null if no trips match the prefix.
     */
    @Query("""
        SELECT tripHeadsign FROM trips
        WHERE tripId LIKE :prefix || '%' AND tripHeadsign IS NOT NULL AND tripHeadsign != ''
        GROUP BY tripHeadsign
        ORDER BY COUNT(*) DESC
        LIMIT 1
    """)
    suspend fun getHeadsignByTripIdPrefix(prefix: String): String?

    @Query("SELECT * FROM trips WHERE tripId = :id")
    suspend fun getById(id: String): Trip?

    /**
     * Fetches multiple trips by ID in a single query — used to resolve
     * realtime trip_updates against the static schedule (so we can show
     * actual headsigns, including depot runs).
     */
    @Query("SELECT * FROM trips WHERE tripId IN (:ids)")
    suspend fun getByIds(ids: List<String>): List<Trip>

    /** Distinct headsigns of a route — its directions, for the picker. */
    @Query("""
        SELECT DISTINCT tripHeadsign FROM trips
        WHERE routeId = :routeId AND tripHeadsign IS NOT NULL
    """)
    suspend fun getHeadsignsForRoute(routeId: String): List<String>

    /**
     * Any one trip of a route in a given direction. All trips of a route in
     * one direction visit the same stops in the same order, so one is enough
     * to describe the path when the concrete vehicle is not yet known.
     */
    @Query("""
        SELECT tripId FROM trips
        WHERE routeId = :routeId AND tripHeadsign = :headsign
        LIMIT 1
    """)
    suspend fun getRepresentativeTrip(routeId: String, headsign: String): String?

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun count(): Int

    @Query("DELETE FROM trips")
    suspend fun deleteAll()
}
