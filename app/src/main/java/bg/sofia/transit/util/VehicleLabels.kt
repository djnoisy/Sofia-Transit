package bg.sofia.transit.util

import bg.sofia.transit.data.db.entity.TransportType

/**
 * Single source for the Bulgarian name of a vehicle.
 *
 * Existed in three places before (Lines list, Journey list, Journey
 * service), each with its own idea of how to render route_type — one of
 * which derived the singular by trimming a letter and announced "Троле".
 * Everything now resolves here, so the Lines, Stops and Journey screens
 * cannot drift apart again.
 */
object VehicleLabels {

    /**
     * "Автобус", "Тролей", "Електробус", "Трамвай", "Метро".
     *
     * CGM's route_type 11 lumps trolleybuses together with electric buses;
     * they are told apart by whether the route belongs to the real trolley
     * network, which the repository determines. Pass [isTrolley] from
     * `GtfsRepository.isTrolleyRoute`; when it is unknown, false yields the
     * safer, more general "Електробус".
     */
    fun singular(routeType: Int, isTrolley: Boolean = false): String = when (routeType) {
        0    -> "Трамвай"
        1    -> "Метро"
        3    -> "Автобус"
        11   -> if (isTrolley) "Тролей" else "Електробус"
        else -> "Превозно средство"
    }

    /** Plural, for headings and filter chips. */
    fun plural(type: TransportType): String = type.labelBg

    /** Maps a GTFS route_type to the coarse category used for icons/colours. */
    fun typeOf(routeType: Int): TransportType = when (routeType) {
        0    -> TransportType.TRAM
        1    -> TransportType.METRO
        11   -> TransportType.TROLLEYBUS
        else -> TransportType.BUS
    }
}
