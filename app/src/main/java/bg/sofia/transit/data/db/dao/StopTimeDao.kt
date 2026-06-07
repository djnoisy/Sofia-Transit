package bg.sofia.transit.data.db.dao

import androidx.room.*
import bg.sofia.transit.data.db.entity.StopTime

data class StopWithSequence(
    val stopId: String,
    val stopName: String,
    val stopSequence: Int,
    val arrivalTime: String,
    val departureTime: String,
    /**
     * Minutes of travel from the FIRST stop on this route. 0 for the first
     * stop. Computed in the repository, not stored in the DB — exact
     * wall-clock times are not shown in the UI because they would imply
     * this is one specific run.
     */
    val minutesFromStart: Int? = null
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

    /**
     * For a given stop, returns route + headsign + arrival_time triples
     * where the trip's service is active on the given date AND the arrival
     * time is >= the given current_time. Used as a fallback for routes that
     * don't appear in the realtime feed (e.g. metro).
     *
     * Note: arrival_time may be in HH:MM:SS or H:MM:SS format. The string
     * comparison works for both because we use lex order.
     */
    @Query("""
        SELECT t.routeId AS routeId,
               t.tripHeadsign AS headsign,
               st.arrivalTime AS arrivalTime
        FROM stop_times st
        JOIN trips t ON t.tripId = st.tripId
        WHERE st.stopId = :stopId
          AND t.serviceId IN (:serviceIds)
          AND st.arrivalTime >= :currentTime
        ORDER BY t.routeId, t.tripHeadsign, st.arrivalTime
    """)
    suspend fun getScheduledArrivalsAtStop(
        stopId: String,
        serviceIds: List<String>,
        currentTime: String
    ): List<ScheduledArrival>

    @Query("SELECT COUNT(*) FROM stop_times")
    suspend fun count(): Int

    /**
     * For a given stop, returns one row per trip passing through it, telling
     * whether that stop is the LAST stop of the trip (isTerminal = 1) or not.
     * We strip the trip_id down to its "ROUTE-SEGMENT" prefix (the part that
     * encodes direction) and aggregate in the repository, so we can decide
     * per direction whether this stop is a terminal (drop-off only) or a
     * normal departure/through stop.
     *
     * isTerminal is 1 when the stop's sequence equals the max sequence of
     * that trip — i.e. it's the final stop on that run.
     */
    @Query("""
        SELECT st.tripId AS tripId,
               CASE WHEN st.stopSequence = (
                   SELECT MAX(st2.stopSequence) FROM stop_times st2
                   WHERE st2.tripId = st.tripId
               ) THEN 1 ELSE 0 END AS isTerminal
        FROM stop_times st
        WHERE st.stopId = :stopId
    """)
    suspend fun getTerminalFlagsAtStop(stopId: String): List<TripTerminalFlag>

    @Query("DELETE FROM stop_times")
    suspend fun deleteAll()
}

/** Result row for getTerminalFlagsAtStop. */
data class TripTerminalFlag(
    val tripId: String,
    val isTerminal: Int
)

/** Result row for getScheduledArrivalsAtStop. */
data class ScheduledArrival(
    val routeId: String,
    val headsign: String?,
    val arrivalTime: String
)
