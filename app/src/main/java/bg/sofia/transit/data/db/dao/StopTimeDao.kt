package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.StopTime

data class StopWithSequence(
    val stopId: String,
    val stopName: String,
    val stopSequence: Int,
    val arrivalTime: String,
    val departureTime: String
)

@Dao
interface StopTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stopTimes: List<StopTime>)

    @Query("SELECT * FROM stop_times WHERE tripId = :tripId ORDER BY stopSequence")
    suspend fun getForTrip(tripId: String): List<StopTime>

    @Query("""
        SELECT st.stopId, s.stopName, st.stopSequence, st.arrivalTime, st.departureTime
        FROM stop_times st
        JOIN stops s ON s.stopId = st.stopId
        WHERE st.tripId = :tripId
        ORDER BY st.stopSequence
    """)
    suspend fun getStopsForTrip(tripId: String): List<StopWithSequence>

    @Query("""
        SELECT st.arrivalTime FROM stop_times st
        WHERE st.tripId = :tripId AND st.stopId = :stopId
        LIMIT 1
    """)
    suspend fun getArrivalForTripAndStop(tripId: String, stopId: String): String?

    @Query("""
        SELECT DISTINCT st.arrivalTime FROM stop_times st
        JOIN trips t ON t.tripId = st.tripId
        WHERE t.routeId = :routeId AND st.stopId = :stopId
        ORDER BY st.arrivalTime
    """)
    suspend fun getScheduleForRouteAtStop(routeId: String, stopId: String): List<String>

    @Query("""
        SELECT st.arrivalTime FROM stop_times st
        JOIN trips t ON t.tripId = st.tripId
        WHERE t.routeId = :routeId 
          AND t.tripHeadsign = :headsign
          AND st.stopId = :stopId
        ORDER BY st.arrivalTime
    """)
    suspend fun getScheduleForDirectionAtStop(
        routeId: String,
        headsign: String,
        stopId: String
    ): List<String>

    /**
     * Same as getScheduleForDirectionAtStop, but filters by service_ids
     * (e.g. only those active on a given calendar date). Used when the user
     * selects "weekday" / "weekend" / a specific date in the schedule view.
     */
    @Query("""
        SELECT st.arrivalTime FROM stop_times st
        JOIN trips t ON t.tripId = st.tripId
        WHERE t.routeId = :routeId 
          AND t.tripHeadsign = :headsign
          AND st.stopId = :stopId
          AND t.serviceId IN (:serviceIds)
        ORDER BY st.arrivalTime
    """)
    suspend fun getScheduleForDirectionAtStopFiltered(
        routeId: String,
        headsign: String,
        stopId: String,
        serviceIds: List<String>
    ): List<String>

    @Query("SELECT COUNT(*) FROM stop_times")
    suspend fun count(): Int

    @Query("DELETE FROM stop_times")
    suspend fun deleteAll()
}
