package android.location

/** The parts of android.location.Location that JourneyTrace reads. */
class Location(
    val latitude: Double,
    val longitude: Double,
    private val acc: Float? = null,
    private val spd: Float? = null,
    private val brg: Float? = null,
    val time: Long = 0L
) {
    val accuracy: Float get() = acc ?: 0f
    val speed: Float get() = spd ?: 0f
    val bearing: Float get() = brg ?: 0f
    fun hasAccuracy() = acc != null
    fun hasSpeed() = spd != null
    fun hasBearing() = brg != null
}
