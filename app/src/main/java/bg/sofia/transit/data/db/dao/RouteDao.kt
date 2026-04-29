package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.Route
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routes: List<Route>)

    @Query("SELECT * FROM routes ORDER BY routeType, routeShortName")
    fun getAllRoutes(): Flow<List<Route>>

    @Query("SELECT * FROM routes ORDER BY routeType, routeShortName")
    suspend fun getAllRoutesOnce(): List<Route>

    @Query("SELECT * FROM routes WHERE routeType = :type ORDER BY routeShortName")
    suspend fun getByType(type: Int): List<Route>

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
