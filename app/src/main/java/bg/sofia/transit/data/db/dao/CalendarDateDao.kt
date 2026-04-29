package bg.sofia.transit.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import bg.sofia.transit.data.db.entity.CalendarDate

@Dao
interface CalendarDateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CalendarDate>)

    /**
     * Returns the service_ids active on a given date (YYYYMMDD).
     * Sofia's GTFS uses calendar_dates exclusively (no calendar.txt),
     * with exception_type = 1 meaning "service runs on this date".
     */
    @Query("""
        SELECT DISTINCT serviceId FROM calendar_dates
        WHERE date = :date AND exceptionType = 1
    """)
    suspend fun getActiveServicesForDate(date: String): List<String>

    /** Returns dates (YYYYMMDD) for which we have schedule data. */
    @Query("""
        SELECT DISTINCT date FROM calendar_dates
        WHERE exceptionType = 1
        ORDER BY date
    """)
    suspend fun getAllAvailableDates(): List<String>

    @Query("SELECT COUNT(*) FROM calendar_dates")
    suspend fun count(): Int

    @Query("DELETE FROM calendar_dates")
    suspend fun deleteAll()
}
