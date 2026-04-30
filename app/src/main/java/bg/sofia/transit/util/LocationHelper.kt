package bg.sofia.transit.util

import bg.sofia.transit.data.db.entity.Stop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Distance and bearing helpers based on the equirectangular approximation —
 * fine for the dense urban scale we operate at (≤ 5 km).
 */
object LocationHelper {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Approximate distance in metres between two lat/lon points. */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLam = Math.toRadians(lon2 - lon1)
        val a = sin(dPhi / 2) * sin(dPhi / 2) +
                cos(phi1) * cos(phi2) * sin(dLam / 2) * sin(dLam / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_M * c
    }

    /** A stop together with its computed distance from the user (metres). */
    data class StopWithDistance(val stop: Stop, val distanceMetres: Double)

    /**
     * Returns the nearest [limit] stops from [stops], sorted by distance.
     * Used by the "Stops" tab to display a clean list of closest stops.
     */
    fun nearestStops(
        stops: List<Stop>,
        userLat: Double,
        userLon: Double,
        limit: Int = 10
    ): List<StopWithDistance> =
        stops.map { s ->
            StopWithDistance(s, distanceMetres(userLat, userLon, s.stopLat, s.stopLon))
        }
        .sortedBy { it.distanceMetres }
        .take(limit)

    /**
     * Picks one nearest stop per quadrant (N/E/S/W relative to the user).
     * Used by the "Journey" screen to find candidate boarding stops in
     * different geographic directions.
     *
     * Used to be used by the "Stops" tab too with clock-position labels,
     * but that UI was replaced with a simple sorted list.
     */
    data class ClockStop(
        val stop: Stop,
        val distanceMetres: Double,
        val clockLabel: String = ""
    )

    fun pickClockStops(
        stops: List<Stop>,
        userLat: Double,
        userLon: Double,
        @Suppress("UNUSED_PARAMETER") heading: Double = 0.0
    ): List<ClockStop> {
        // Group stops into 4 quadrants by absolute compass bearing
        val withBearing = stops.map { s ->
            val dist = distanceMetres(userLat, userLon, s.stopLat, s.stopLon)
            val brg  = bearingDegrees(userLat, userLon, s.stopLat, s.stopLon)
            Triple(s, dist, brg)
        }
        val quadrants = arrayOfNulls<Triple<Stop, Double, Double>>(4)
        for (entry in withBearing) {
            val (_, dist, brg) = entry
            val q = (((brg + 45) % 360) / 90).toInt().coerceIn(0, 3)
            val current = quadrants[q]
            if (current == null || dist < current.second) {
                quadrants[q] = entry
            }
        }
        return quadrants.filterNotNull().map {
            ClockStop(it.first, it.second)
        }
    }

    private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLam = Math.toRadians(lon2 - lon1)
        val y = sin(dLam) * cos(phi2)
        val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLam)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }
}
