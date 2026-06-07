package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.Route
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routes: List<Route>)

    // Only routes that actually have trips — empty/placeholder route
    // definitions (duplicate numbers, seasonal/temporary lines CGM defines
    // but doesn't operate) have nothing to show in the Lines section, so we
    // hide them. A route "has trips" when at least one trip references it.
    @Query("""
        SELECT * FROM routes
        WHERE EXISTS (SELECT 1 FROM trips WHERE trips.routeId = routes.routeId)
        ORDER BY routeType, routeShortName
    """)
    fun getAllRoutes(): Flow<List<Route>>

    @Query("""
        SELECT * FROM routes
        WHERE EXISTS (SELECT 1 FROM trips WHERE trips.routeId = routes.routeId)
        ORDER BY routeType, routeShortName
    """)
    suspend fun getAllRoutesOnce(): List<Route>

    @Query("SELECT * FROM routes WHERE routeType = :type ORDER BY routeShortName")
    suspend fun getByType(type: Int): List<Route>

    /** All route_ids in the static feed, INCLUDING ones with no trips —
     *  used for realtime cross-check/diagnostics where we want the complete
     *  set (e.g. to recognise A241/M3 which has zero static trips). */
    @Query("SELECT routeId FROM routes")
    suspend fun getAllRouteIds(): List<String>

    @Query("SELECT * FROM routes WHERE routeId = :id")
    suspend fun getById(id: String): Route?

    @Query("""
        SELECT DISTINCT r.* FROM routes r
        JOIN trips t ON t.routeId = r.routeId
        JOIN stop_times st ON st.tripId = t.tripId
        WHERE st.stopId IN (:stopIds)
        ORDER BY r.routeType, r.routeShortName
    """)
    suspend fun getRoutesForStops(stopIds: List<String>): List<Route>

    @Query("SELECT COUNT(*) FROM routes")
    suspend fun count(): Int

    @Query("DELETE FROM routes")
    suspend fun deleteAll()
}
