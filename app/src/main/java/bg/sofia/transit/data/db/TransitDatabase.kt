package bg.sofia.transit.data.db

import android.content.Context
import androidx.room.*
import bg.sofia.transit.data.db.dao.*
import bg.sofia.transit.data.db.entity.*

@Database(
    entities = [Stop::class, Route::class, Trip::class, StopTime::class, CalendarDate::class,
                Shape::class],
    // 6: shapes. The destructive migration below empties the database, and
    // the next start installs the data afresh — shapes included.
    version = 6,
    exportSchema = false
)
abstract class TransitDatabase : RoomDatabase() {
    abstract fun stopDao(): StopDao
    abstract fun routeDao(): RouteDao
    abstract fun tripDao(): TripDao
    abstract fun stopTimeDao(): StopTimeDao
    abstract fun calendarDateDao(): CalendarDateDao
    abstract fun shapeDao(): ShapeDao

    companion object {
        @Volatile private var INSTANCE: TransitDatabase? = null

        fun getInstance(context: Context): TransitDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TransitDatabase::class.java,
                    "sofia_transit.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
            }
    }
}
