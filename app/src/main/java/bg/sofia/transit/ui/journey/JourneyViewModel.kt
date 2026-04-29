package bg.sofia.transit.ui.journey

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.location.Location
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RealtimeRepository
import bg.sofia.transit.data.repository.UpcomingTripInfo
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Journey screen state machine.
 *
 *  Idle               – waiting for first location fix.
 *  SelectUpcomingTrip – list of real-time arrivals at the 4 nearest stops,
 *                       deduplicated by (route, headsign), sorted by ETA.
 *  Active             – tracking the chosen trip, announcing stops via TTS.
 */
sealed class JourneyState {
    object Idle : JourneyState()

    data class SelectUpcomingTrip(
        val upcoming: List<UpcomingTripInfo>,
        val refreshing: Boolean = false
    ) : JourneyState()

    data class Active(
        val trip: UpcomingTripInfo,
        val stops: List<StopWithSequence>,
        val stopLatLons: List<Pair<Double, Double>>,
        val currentIdx: Int,
        val atStop: Boolean
    ) : JourneyState()
}

@HiltViewModel
class JourneyViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    private val realtimeRepo: RealtimeRepository,
    application: Application
) : AndroidViewModel(application) {

    companion object { private const val TAG = "JourneyVM" }

    private val _state = MutableStateFlow<JourneyState>(JourneyState.Idle)
    val state: StateFlow<JourneyState> = _state

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    // Service binding for active journey
    private var journeyService: JourneyService? = null
    private var bindingDeferred: kotlinx.coroutines.CompletableDeferred<JourneyService>? = null
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as JourneyService.LocalBinder).get()
            journeyService = svc
            svc.locationListener = { loc -> processLocation(loc) }
            // Resolve the awaiting coroutine if any
            bindingDeferred?.complete(svc)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            journeyService = null
        }
    }

    // Cache for nearest-stop IDs across refreshes (avoids re-querying DB constantly)
    private var lastNearbyStopIds: Set<String> = emptySet()

    // ── Loading upcoming trips at nearby stops ────────────────────────────
    fun loadUpcomingTrips(lat: Double, lon: Double) {
        // Don't disturb an already-active journey
        if (_state.value is JourneyState.Active) return

        val previous = (_state.value as? JourneyState.SelectUpcomingTrip)?.upcoming ?: emptyList()
        _state.value = JourneyState.SelectUpcomingTrip(previous, refreshing = true)

        viewModelScope.launch {
            try {
                // 1. Find 4 nearest stops (using clock-sector logic so we cover
                //    all directions, not just the closest one)
                val nearby      = gtfsRepo.getNearestStops(lat, lon, limit = 20)
                val clockStops  = LocationHelper.pickClockStops(nearby, lat, lon, 0.0)

                // Build map: stopId → (stopName, distanceMetres). Used for
                // both API filtering and proximity-based sorting downstream.
                val stopInfo = clockStops.associate { cs ->
                    cs.stop.stopId to Pair(cs.stop.stopName, cs.distanceMetres)
                }
                lastNearbyStopIds = stopInfo.keys

                if (stopInfo.isEmpty()) {
                    _state.value = JourneyState.SelectUpcomingTrip(emptyList(), refreshing = false)
                    return@launch
                }

                // 2. Fetch GTFS-RT trip updates for those stops
                val raw = realtimeRepo.getUpcomingTripsForStops(stopInfo.keys, withinMinutes = 30)

                // 3. Resolve headsigns + dedup by (route, headsign), sort by proximity
                val resolved = gtfsRepo.resolveUpcomingTrips(raw, stopInfo)

                _state.value = JourneyState.SelectUpcomingTrip(resolved, refreshing = false)
            } catch (e: Exception) {
                Log.e(TAG, "loadUpcomingTrips failed: ${e.message}")
                _error.emit("Грешка при зареждане: ${e.message ?: "няма връзка"}")
                _state.value = JourneyState.SelectUpcomingTrip(emptyList(), refreshing = false)
            }
        }
    }

    // ── Selecting a trip → start active journey ───────────────────────────
    fun selectUpcomingTrip(trip: UpcomingTripInfo) {
        viewModelScope.launch {
            try {
                // 1. Load the full ordered list of stops for THIS exact trip
                val stops = gtfsRepo.getRemainingStops(trip.tripId, fromSequence = 0)
                if (stops.isEmpty()) {
                    _error.emit("Няма данни за маршрута на това превозно средство")
                    return@launch
                }

                // 2. Pre-fetch each stop's lat/lon for proximity checks
                val latLons = stops.map { sw ->
                    val s: Stop? = gtfsRepo.getStopById(sw.stopId)
                    Pair(s?.stopLat ?: 0.0, s?.stopLon ?: 0.0)
                }

                // 3. Determine starting index — the boarding stop is the one
                //    referenced by the realtime arrival entry the user picked.
                val startIdx = stops.indexOfFirst { it.stopId == trip.stopId }
                                    .coerceAtLeast(0)

                // 4. Start the foreground service and wait for actual binding
                val ctx = getApplication<Application>()
                JourneyService.start(ctx)

                val deferred = kotlinx.coroutines.CompletableDeferred<JourneyService>()
                bindingDeferred = deferred
                ctx.bindService(
                    Intent(ctx, JourneyService::class.java),
                    serviceConn,
                    Context.BIND_AUTO_CREATE
                )

                // Wait up to 5 s for the service to actually bind (instead of
                // an arbitrary delay that may be too short on slow devices)
                val svc = try {
                    kotlinx.coroutines.withTimeout(5_000L) { deferred.await() }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                    _error.emit("Не може да се стартира услугата за пътуване")
                    return@launch
                } finally {
                    bindingDeferred = null
                }

                svc.beginJourney(stops, latLons)

                _state.value = JourneyState.Active(
                    trip        = trip,
                    stops       = stops,
                    stopLatLons = latLons,
                    currentIdx  = startIdx,
                    atStop      = false
                )
            } catch (e: Exception) {
                Log.e(TAG, "selectUpcomingTrip failed: ${e.message}")
                _error.emit("Грешка при стартиране: ${e.message}")
            }
        }
    }

    // ── Location-driven progress while on the bus ─────────────────────────
    private fun processLocation(location: Location) {
        val active = _state.value as? JourneyState.Active ?: return
        val svc    = journeyService ?: return

        val idx     = active.currentIdx
        val latLons = active.stopLatLons
        if (idx >= latLons.size) return

        val (stopLat, stopLon) = latLons[idx]
        val dist = LocationHelper.distanceMetres(
            location.latitude, location.longitude, stopLat, stopLon)

        if (!active.atStop && dist < JourneyService.ARRIVAL_RADIUS) {
            // Arrived at current stop
            svc.onArrival()
            _state.value = active.copy(atStop = true, currentIdx = idx)
        } else if (active.atStop && dist > JourneyService.ARRIVAL_RADIUS * 1.5) {
            // Left current stop → announce next
            val nextIdx = idx + 1
            svc.onDeparture()
            if (nextIdx < active.stops.size) {
                _state.value = active.copy(atStop = false, currentIdx = nextIdx)
            } else {
                endJourney()
            }
        }
    }

    fun endJourney() {
        journeyService?.endJourney()
        try {
            getApplication<Application>().unbindService(serviceConn)
        } catch (_: Exception) {}
        JourneyService.stop(getApplication())
        _state.value = JourneyState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        if (_state.value is JourneyState.Active) endJourney()
    }
}
