package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.data.repository.GtfsRepository
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

    /**
     * UI state that must survive navigation away and back to the Lines
     * screen: the active category filter and the last route the user
     * opened. Kept here (shared, activity-scoped VM) because the
     * fragment's view is destroyed on forward navigation and local
     * fragment fields can't restore the screen reliably.
     */
    var currentFilter: TransportType? = null
    var lastSelectedRouteId: String? = null

    /**
     * The number typed in the screen's search field (the owner, 26 Nov
     * 2026): kept here, with the kind, while a line is opened and back;
     * cleared when another kind is chosen.
     */
    var lineQuery: String = ""

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

    /** The Lines screen's order of lines: natural, by short name (LineOrder). */
    private val shortNameComparator: Comparator<Route> = Comparator { a, b ->
        LineOrder.compare(a.routeShortName, b.routeShortName)
    }

}
