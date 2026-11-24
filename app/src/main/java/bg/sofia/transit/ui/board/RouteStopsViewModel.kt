package bg.sofia.transit.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.repository.GtfsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The stops of a line's route through a stop (RouteStopsFragment). */
@HiltViewModel
class RouteStopsViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    /** Null until loaded. */
    private val _stops = MutableStateFlow<List<StopWithSequence>?>(null)
    val stops: StateFlow<List<StopWithSequence>?> = _stops

    fun load(tripId: String) {
        if (_stops.value != null) return
        viewModelScope.launch {
            // An import empties the tables before refilling them (as StopArrivalsViewModel).
            if (!repo.dataReady.value) repo.dataReady.first { it }
            _stops.value = repo.getStopsOfTrip(tripId)
        }
    }

    /** For the header: "Автобус 111", as the Lines screen has it. */
    suspend fun getRouteMeta(routeId: String) = repo.getRouteMeta(routeId)

    /** A stop's code ("0169"), for its board: the board gathers the stop's rows by it. */
    suspend fun stopCode(stopId: String): String? = repo.getStopById(stopId)?.stopCode
}
