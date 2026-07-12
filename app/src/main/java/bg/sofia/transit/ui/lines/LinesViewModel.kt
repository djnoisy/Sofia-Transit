package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.data.repository.GtfsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
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

    // Directions for selected route
    private val _directions = MutableStateFlow<List<Trip>>(emptyList())
    val directions: StateFlow<List<Trip>> = _directions

    // Stops for selected direction
    private val _directionStops = MutableStateFlow<List<StopWithSequence>>(emptyList())
    val directionStops: StateFlow<List<StopWithSequence>> = _directionStops

    private var selectedRoute: Route? = null
    private var selectedDirection: Trip? = null

    fun loadRoutes() {
        viewModelScope.launch {
            _routeSubtitles.value = repo.getRouteSubtitles()
            repo.getAllRoutes().collect { routes ->
                _groupedRoutes.value = routes
                    .groupBy { it.getTransportType() }
                    .toSortedMap(compareBy { it.ordinal })
            }
        }
    }

    fun selectRoute(route: Route) {
        selectedRoute = route
        viewModelScope.launch {
            _directions.value = repo.getDirectionsForRoute(route.routeId)
        }
    }

    fun selectDirection(trip: Trip) {
        selectedDirection = trip
        viewModelScope.launch {
            _directionStops.value = repo.getStopsForDirection(
                trip.routeId, trip.tripHeadsign ?: ""
            )
        }
    }

    /** Used by ScheduleFragment to look up the direction it was opened for. */
    fun getSelectedDirection(): Trip? = selectedDirection

    fun clearDirections() { _directions.value = emptyList() }
    fun clearStops()      { _directionStops.value = emptyList() }
}
