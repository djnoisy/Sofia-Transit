package bg.sofia.transit.ui.nearby

import bg.sofia.transit.util.FileLogger
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RealtimeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StopArrivalsState(
    val arrivals: List<ArrivalInfo> = emptyList(),
    val loading: Boolean = false
)

@HiltViewModel
class StopArrivalsViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    private val realtimeRepo: RealtimeRepository
) : ViewModel() {

    companion object { private const val TAG = "StopArrivalsVM" }

    private val _state = MutableStateFlow(StopArrivalsState())
    val state: StateFlow<StopArrivalsState> = _state

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    fun loadArrivals(stopId: String) {
        FileLogger.i(TAG, "loadArrivals called for stopId='$stopId'")
        _state.value = _state.value.copy(loading = true)

        viewModelScope.launch {
            try {
                val arrivals = gtfsRepo.getArrivalsForStop(stopId, realtimeRepo)
                FileLogger.i(TAG, "Got ${arrivals.size} arrivals for $stopId")
                if (arrivals.isNotEmpty()) {
                    arrivals.take(3).forEachIndexed { i, a ->
                        FileLogger.i(TAG, "  [$i] line=${a.routeShortName} → ${a.headsign}: ${a.arrivals.joinToString()}")
                    }
                }
                _state.value = StopArrivalsState(
                    arrivals = arrivals,
                    loading  = false
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "Error loading arrivals: ${e.message}", e)
                _state.value = _state.value.copy(loading = false)
                _error.emit("Грешка при зареждане: ${e.message ?: "няма връзка"}")
            }
        }
    }
}
