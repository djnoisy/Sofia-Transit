package bg.sofia.transit.ui.nearby

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.util.LocationHelper
import bg.sofia.transit.util.LocationHelper.StopWithDistance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Provides the 10 nearest stops to the user.
 *
 * To avoid distracting screen-reader users with constant updates, the list
 * is recomputed only when the user has moved at least [MIN_MOVE_METRES] from
 * the position we last queried. Location callbacks fire freely, but we
 * silently discard those that don't meet the threshold.
 */
@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    application: Application
) : AndroidViewModel(application) {

    companion object { private const val MIN_MOVE_METRES = 20.0 }

    private val _nearestStops = MutableStateFlow<List<StopWithDistance>>(emptyList())
    val nearestStops: StateFlow<List<StopWithDistance>> = _nearestStops

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    private var lastQueryLat = 0.0
    private var lastQueryLon = 0.0
    private var hasQueried = false

    fun onLocationUpdate(location: Location) {
        val lat = location.latitude
        val lon = location.longitude

        if (hasQueried) {
            val moved = LocationHelper.distanceMetres(lastQueryLat, lastQueryLon, lat, lon)
            if (moved < MIN_MOVE_METRES) return     // tiny GPS jitter — skip
        }

        viewModelScope.launch {
            try {
                // SQL sorts by physical distance correctly (uses cos(lat) for
                // longitude scaling), so we ask for exactly the top 10 here.
                val nearest = gtfsRepo.getNearestStops(lat, lon, limit = 10)
                val withDistance = nearest.map { stop ->
                    StopWithDistance(
                        stop,
                        LocationHelper.distanceMetres(lat, lon, stop.stopLat, stop.stopLon)
                    )
                }
                _nearestStops.value = withDistance
                lastQueryLat = lat
                lastQueryLon = lon
                hasQueried = true
            } catch (e: Exception) {
                _error.emit("Грешка при зареждане на спирки: ${e.message}")
            }
        }
    }
}
