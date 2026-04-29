package bg.sofia.transit.ui.nearby

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.util.CompassHelper
import bg.sofia.transit.util.LocationHelper
import bg.sofia.transit.util.LocationHelper.ClockStop
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    application: Application
) : AndroidViewModel(application) {

    private val _clockStops = MutableStateFlow<List<ClockStop>>(emptyList())
    val clockStops: StateFlow<List<ClockStop>> = _clockStops

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    // Current user state
    private var userLat = 0.0
    private var userLon = 0.0
    private var userHeading = 0f
    private var movementHeading: Float? = null

    // Cache of nearby stops (re-sorted on heading change without re-querying DB)
    private var nearbyCache: List<Stop> = emptyList()

    private var headingJob: Job? = null

    fun startCompass() {
        headingJob?.cancel()
        headingJob = viewModelScope.launch {
            CompassHelper.headingFlow(getApplication())
                .collect { h ->
                    userHeading = h
                    recomputeClockStops()
                }
        }
    }

    fun stopCompass() {
        headingJob?.cancel()
    }

    fun onLocationUpdate(location: Location) {
        userLat = location.latitude
        userLon = location.longitude

        // Use movement bearing if the user is actually walking, else fall
        // back to compass heading
        movementHeading = if (location.hasBearing() && location.speed > 0.5f)
            location.bearing
        else
            null

        viewModelScope.launch {
            try {
                nearbyCache = gtfsRepo.getNearestStops(userLat, userLon, limit = 20)
                recomputeClockStops()
            } catch (e: Exception) {
                _error.emit("Грешка при зареждане на спирки: ${e.message}")
            }
        }
    }

    private fun recomputeClockStops() {
        if (nearbyCache.isEmpty()) return
        val heading = movementHeading ?: userHeading
        _clockStops.value = LocationHelper.pickClockStops(
            nearbyCache, userLat, userLon, heading.toDouble()
        )
    }
}
