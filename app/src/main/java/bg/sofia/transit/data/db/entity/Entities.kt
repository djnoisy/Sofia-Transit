package bg.sofia.transit.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "stops",
    indices = [Index(value = ["stopLat", "stopLon"])]
)
data class Stop(
    @PrimaryKey val stopId: String,
    val stopCode: String?,
    val stopName: String,
    val stopLat: Double,
    val stopLon: Double,
    val locationType: Int = 0,
    val parentStation: String? = null
)

@Entity(tableName = "routes")
data class Route(
    @PrimaryKey val routeId: String,
    val agencyId: String,
    val routeShortName: String,
    val routeLongName: String,
    val routeType: Int,
    val routeColor: String?,
    val routeTextColor: String?,
    val routeSortOrder: Int?
) {
    fun getTransportType(): TransportType = when (routeType) {
        0    -> TransportType.TRAM
        1    -> TransportType.METRO
        11   -> TransportType.TROLLEYBUS
        else -> TransportType.BUS
    }
}

enum class TransportType(val labelBg: String, val emoji: String) {
    BUS("Автобуси", "🚌"),
    TRAM("Трамваи", "🚊"),
    TROLLEYBUS("Тролеи", "🚎"),
    METRO("Метро", "🚇")
}

@Entity(
    tableName = "trips",
    foreignKeys = [ForeignKey(Route::class, ["routeId"], ["routeId"], ForeignKey.CASCADE)],
    indices = [Index("routeId")]
)
data class Trip(
    @PrimaryKey val tripId: String,
    val routeId: String,
    val serviceId: String,
    val tripHeadsign: String?,
    val directionId: Int?,
    val shapeId: String?
)

@Entity(
    tableName = "stop_times",
    primaryKeys = ["tripId", "stopSequence"],
    foreignKeys = [
        ForeignKey(Trip::class, ["tripId"], ["tripId"], ForeignKey.CASCADE),
        ForeignKey(Stop::class, ["stopId"], ["stopId"], ForeignKey.CASCADE)
    ],
    indices = [Index("tripId"), Index("stopId")]
)
data class StopTime(
    val tripId: String,
    val arrivalTime: String,
    val departureTime: String,
    val stopId: String,
    val stopSequence: Int,
    val timepoint: Int = 0
)

@Entity(tableName = "calendar_dates")
data class CalendarDate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serviceId: String,
    val date: String,
    val exceptionType: Int
)
