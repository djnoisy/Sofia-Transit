package bg.sofia.transit.util

import bg.sofia.transit.data.db.entity.Stop
import kotlin.math.*

object LocationHelper {

    /**
     * Haversine distance in metres between two lat/lon points.
     */
    fun distanceMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)
        return R * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * Bearing in degrees (0–360) from point 1 → point 2 (clockwise from North).
     */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(Math.toRadians(lat2))
        val x = cos(Math.toRadians(lat1)) * sin(Math.toRadians(lat2)) -
                sin(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /**
     * Converts an absolute bearing to a clock-sector label relative to the
     * user's current heading (from movement or compass).
     *
     * Sectors (each 90°):
     *   330–30  → "12 часа"
     *    30–120 → "3 часа"
     *   120–210 → "6 часа"
     *   210–330 → "9 часа"
     */
    fun clockLabel(absoluteBearingToStop: Double, userHeading: Double): String {
        val relative = ((absoluteBearingToStop - userHeading) + 360) % 360
        return when {
            relative < 30 || relative >= 330 -> "12 часа"
            relative < 120 -> "3 часа"
            relative < 210 -> "6 часа"
            else           -> "9 часа"
        }
    }

    /**
     * Returns the clock sector index (0=12, 1=3, 2=6, 3=9) for a relative bearing.
     */
    fun clockSector(absoluteBearingToStop: Double, userHeading: Double): Int {
        val relative = ((absoluteBearingToStop - userHeading) + 360) % 360
        return when {
            relative < 30 || relative >= 330 -> 0
            relative < 120 -> 1
            relative < 210 -> 2
            else           -> 3
        }
    }

    data class ClockStop(
        val stop: Stop,
        val distanceMetres: Double,
        val bearingToStop: Double,
        val sector: Int,           // 0=12ч, 1=3ч, 2=6ч, 3=9ч
        val clockLabel: String
    )

    /**
     * Given a list of nearby stops, user position and heading, returns
     * the single closest stop for each of the 4 clock sectors.
     * Result list has exactly up to 4 entries (missing if no stops in that sector).
     */
    fun pickClockStops(
        stops: List<Stop>,
        userLat: Double,
        userLon: Double,
        userHeading: Double
    ): List<ClockStop> {
        val labels = listOf("12 часа", "3 часа", "6 часа", "9 часа")
        val bestBySector = Array<ClockStop?>(4) { null }

        for (stop in stops) {
            val dist = distanceMetres(userLat, userLon, stop.stopLat, stop.stopLon)
            val bearing = bearingDegrees(userLat, userLon, stop.stopLat, stop.stopLon)
            val sector = clockSector(bearing, userHeading)
            val current = bestBySector[sector]
            if (current == null || dist < current.distanceMetres) {
                bestBySector[sector] = ClockStop(
                    stop          = stop,
                    distanceMetres = dist,
                    bearingToStop = bearing,
                    sector        = sector,
                    clockLabel    = labels[sector]
                )
            }
        }
        return bestBySector.filterNotNull()
    }
}
