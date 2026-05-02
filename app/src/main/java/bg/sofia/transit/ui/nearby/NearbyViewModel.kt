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
 * Location callbacks fire freely, but we silently skip updates that don't
 * meet [MIN_MOVE_METRES] from the last-queried position to avoid redundant
 * DB hits. If GTFS data isn't ready yet (first-run import still in progress)
 * the update is buffered and replayed automatically once data is available.
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

    /** Holds the most recent location received while data was still loading. */
    private var pendingLocation: Location? = null

    init {
        // When the GTFS import finishes (or is confirmed already loaded),
        // replay any location update that arrived during the loading window.
        // Also covers the weekly-worker reload while the user is stationary:
        // re-query against the last known position so the list refreshes
        // against the new data even without a new GPS callback.
        viewModelScope.launch {
            gtfsRepo.dataReady.collect { ready ->
                if (!ready) return@collect
                val pending = pendingLocation
                if (pending != null) {
                    pendingLocation = null
                    doQuery(pending.latitude, pending.longitude)
                } else if (hasQueried) {
                    doQuery(lastQueryLat, lastQueryLon)
                }
            }
        }
    }

    fun onLocationUpdate(location: Location) {
        // Don't query with partial data — buffer and wait for the data-ready signal.
        if (!gtfsRepo.dataReady.value) {
            pendingLocation = location
            return
        }

        val lat = location.latitude
        val lon = location.longitude

        if (hasQueried) {
            val moved = LocationHelper.distanceMetres(lastQueryLat, lastQueryLon, lat, lon)
            if (moved < MIN_MOVE_METRES) return
        }

        viewModelScope.launch { doQuery(lat, lon) }
    }

    private suspend fun doQuery(lat: Double, lon: Double) {
        try {
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
