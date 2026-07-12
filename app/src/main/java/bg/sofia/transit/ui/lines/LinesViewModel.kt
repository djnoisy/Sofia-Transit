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

    // Set of route_ids that are real trolleys — used to label rows in the
    // Lines list. Populated once when routes are loaded.
    private val _trolleyRouteIds = MutableStateFlow<Set<String>>(emptySet())
    val trolleyRouteIds: StateFlow<Set<String>> = _trolleyRouteIds

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
            _trolleyRouteIds.value = repo.getTrolleyRouteIdsSet()
            repo.getAllRoutes().collect { routes ->
                _groupedRoutes.value = routes
                    .groupBy { it.getTransportType() }
                    .mapValues { (_, list) -> list.sortedWith(shortNameComparator) }
                    .toSortedMap(compareBy { it.ordinal })
            }
        }
    }

    /**
     * Natural sort for route short names, so that "2" comes before "11" and
     * "11" before "111", instead of the lexical order "1, 11, 12, ..., 2, 21,
     * ...". Split each name into (letter prefix, number, letter suffix) and
     * compare tuple-wise. Names without a number (rare) fall back to text.
     * Ordering:
     *   plain numeric first, ascending (1, 2, 3, ..., 11, 12, ..., 280)
     *   then numeric with a letter suffix, grouped by their number (7, 7А)
     *   then names starting with a letter, alphabetically (E186, M1, X50, Д1)
     */
    private val shortNameComparator: Comparator<Route> = Comparator { a, b ->
        val ka = keyFor(a.routeShortName)
        val kb = keyFor(b.routeShortName)
        // First key: 0 = starts with a digit (plain numeric or numeric+suffix),
        // 1 = starts with a letter. Plain numeric group comes first.
        compareValuesBy(ka, kb, { it.group }, { it.number }, { it.prefix }, { it.suffix })
    }

    private data class SortKey(val group: Int, val prefix: String, val number: Int, val suffix: String)

    private fun keyFor(name: String): SortKey {
        val m = Regex("^([^0-9]*)(\\d+)(.*)$").matchEntire(name)
        return if (m != null) {
            val prefix = m.groupValues[1]
            val number = m.groupValues[2].toIntOrNull() ?: Int.MAX_VALUE
            val suffix = m.groupValues[3]
            val group = if (prefix.isEmpty()) 0 else 1
            SortKey(group, prefix, number, suffix)
        } else {
            // No digits at all: sort at the very end alphabetically.
            SortKey(2, name, Int.MAX_VALUE, "")
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
