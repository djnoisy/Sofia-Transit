package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.OtherDirection
import bg.sofia.transit.util.LineOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinesViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    // All routes grouped by transport type
    private val _groupedRoutes = MutableStateFlow<Map<TransportType, List<Route>>>(emptyMap())
    val groupedRoutes: StateFlow<Map<TransportType, List<Route>>> = _groupedRoutes

    // Subtitle per routeId, built from the top-2 trip headsigns (e.g.
    // "Ж.К. ОВЧА КУПЕЛ-2 - СТУДЕНТСКИ ГРАД"). Shown instead of the
    // unreliable route_long_name so the list matches the directions screen.
    private val _routeSubtitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val routeSubtitles: StateFlow<Map<String, String>> = _routeSubtitles

    // Set of route_ids that are real trolleys — used to label rows in the
    // Lines list. Populated once when routes are loaded.
    private val _trolleyRouteIds = MutableStateFlow<Set<String>>(emptySet())
    val trolleyRouteIds: StateFlow<Set<String>> = _trolleyRouteIds

    // Directions for selected route: the two main ones, and the others
    // running today, under "Други направления" (the owner, 24 Nov 2026) —
    // together, so the list is drawn once
    private val _directions = MutableStateFlow(LineDirections())
    val directions: StateFlow<LineDirections> = _directions

    // Stops for selected direction
    private val _directionStops = MutableStateFlow<List<StopWithSequence>>(emptyList())
    val directionStops: StateFlow<List<StopWithSequence>> = _directionStops

    private var selectedRoute: Route? = null
    private var selectedDirection: Trip? = null

    /**
     * UI state that must survive navigation away and back to the Lines
     * screen: the active category filter and the last route the user
     * opened. Kept here (shared, activity-scoped VM) because the
     * fragment's view is destroyed on forward navigation and local
     * fragment fields can't restore the screen reliably.
     */
    var currentFilter: TransportType? = null
    var lastSelectedRouteId: String? = null

    fun loadRoutes() {
        viewModelScope.launch {
            // An import empties the tables before refilling them, so a read
            // that lands mid-way returns nothing and the screen shows
            // identifiers where names belong.
            if (!repo.dataReady.value) repo.dataReady.first { it }

            _routeSubtitles.value = repo.getRouteSubtitles()
            _trolleyRouteIds.value = repo.getTrolleyRouteIdsSet()
            repo.getAllRoutes().collect { routes ->
                _groupedRoutes.value = routes
                    .groupBy { it.getTransportType() }
                    .mapValues { (_, list) -> list.sortedWith(shortNameComparator) }
                    .toSortedMap(compareBy { it.ordinal })
            }
        }
    }

    /** Per-route metadata for the Directions screen header. */
    suspend fun getRouteMeta(routeId: String) = repo.getRouteMeta(routeId)

    /** The Lines screen's order of lines: natural, by short name (LineOrder). */
    private val shortNameComparator: Comparator<Route> = Comparator { a, b ->
        LineOrder.compare(a.routeShortName, b.routeShortName)
    }

    fun selectRoute(route: Route) {
        selectedRoute = route
        viewModelScope.launch {
            val main = repo.getDirectionsForRoute(route.routeId)
            _directions.value = LineDirections(main, repo.getOtherDirectionsForRoute(route.routeId, main))
        }
    }

    /**
     * The stops of a direction: of a main one, its route (getStopsForDirection,
     * as before); of one under "Други направления" ([other]), the route of the
     * trip standing for it (RouteOfTheDay.othersListed).
     */
    fun selectDirection(trip: Trip, other: Boolean = false) {
        selectedDirection = trip
        viewModelScope.launch {
            _directionStops.value = if (other) repo.getStopsOfTrip(trip.tripId)
            else repo.getStopsForDirection(trip.routeId, trip.tripHeadsign ?: "")
        }
    }

    /** A stop's code ("0169"), for its window: its board gathers the stop's poles by it. */
    suspend fun stopCode(stopId: String): String? = repo.getStopById(stopId)?.stopCode

    fun clearDirections() { _directions.value = LineDirections() }
    fun clearStops()      { _directionStops.value = emptyList() }
}

/**
 * A line's directions: the two main ones, and under "Други направления" the
 * others running today and the further routes of each (GtfsRepository.getOtherDirectionsForRoute).
 */
data class LineDirections(val main: List<Trip> = emptyList(), val others: List<OtherDirection> = emptyList())
