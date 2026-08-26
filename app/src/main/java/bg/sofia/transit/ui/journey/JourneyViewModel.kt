package bg.sofia.transit.ui.journey

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RealtimeRepository
import bg.sofia.transit.data.repository.UpcomingTripInfo
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

/**
 * State for the SELECTION phase only (list of arriving vehicles).
 * The ACTIVE journey lives entirely in JourneyService.trackingState —
 * this ViewModel never mirrors it, so the two can never diverge.
 */
data class SelectionState(
    val upcoming: List<UpcomingTripInfo> = emptyList(),
    val refreshing: Boolean = false,
    val hasLocation: Boolean = false
)

@HiltViewModel
class JourneyViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    private val realtimeRepo: RealtimeRepository,
    application: Application
) : AndroidViewModel(application) {

    companion object { private const val TAG = "JourneyVM" }

    private val _selection = MutableStateFlow(SelectionState())
    val selection: StateFlow<SelectionState> = _selection

    /** Journey state, straight from the single source of truth. */
    val tracking = JourneyService.trackingState

    /** One-shot journey events (e.g. destination reached → go to Stops). */
    val journeyEvents = JourneyService.events

    /** Trolley route set, so the list can label type=11 rows correctly. */
    private val _trolleyRouteIds = MutableStateFlow<Set<String>>(emptySet())
    val trolleyRouteIds: StateFlow<Set<String>> = _trolleyRouteIds

    /**
     * Sets the stop the user will get off at, by index into the trip's stop
     * list. Passing null clears it. Requires the service binding, which we
     * already hold while a journey is active.
     */
    fun setDestination(idx: Int?) {
        journeyService?.setDestination(idx) ?: run {
            // Binding was lost (process restart while tracking). Re-bind and
            // apply once connected, so the tap isn't silently dropped.
            bindToService()
            viewModelScope.launch {
                val svc = try {
                    withTimeout(3_000L) {
                        bindingDeferred = CompletableDeferred()
                        bindingDeferred!!.await()
                    }
                } catch (_: Exception) { null } finally { bindingDeferred = null }
                svc?.setDestination(idx)
                    ?: _error.emit("Изборът не може да бъде приложен")
            }
        }
    }

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    // ── Service binding (commands only; state flows via companion) ────────
    private var journeyService: JourneyService? = null
    private var bindingDeferred: CompletableDeferred<JourneyService>? = null
    private var bound = false
    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = (binder as JourneyService.LocalBinder).get()
            journeyService = svc
            bindingDeferred?.complete(svc)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            journeyService = null
        }
    }

    init {
        viewModelScope.launch {
            try { _trolleyRouteIds.value = gtfsRepo.trolleyRouteIds() }
            catch (e: Exception) {
                FileLogger.w(TAG, "Could not load trolley routes: ${e.message}")
            }
        }
        // If a journey is already active (user switched tabs and came back,
        // or the fragment was recreated), re-bind so the End button works.
        if (tracking.value is JourneyService.TrackingState.Tracking) {
            bindToService()
        }
    }

    private fun bindToService() {
        val ctx = getApplication<Application>()
        bound = ctx.bindService(
            Intent(ctx, JourneyService::class.java),
            serviceConn,
            0 // no AUTO_CREATE: bind only if the service is already running
        )
    }

    // ── Loading upcoming trips at nearby stops ────────────────────────────
    fun loadUpcomingTrips(lat: Double, lon: Double) {
        // Selection is irrelevant while a journey is active
        if (tracking.value is JourneyService.TrackingState.Tracking) return

        _selection.value = _selection.value.copy(refreshing = true, hasLocation = true)

        viewModelScope.launch {
            try {
                // Same battle-tested pipeline as the Stops tab: clock-sector
                // stop picking + trip resolution with all the phantom-stop
                // and dedup fixes.
                val nearby     = gtfsRepo.getNearestStops(lat, lon, limit = 20)
                val clockStops = LocationHelper.pickClockStops(nearby, lat, lon, 0.0)

                val stopInfo = clockStops.associate { cs ->
                    cs.stop.stopId to Pair(cs.stop.stopName, cs.distanceMetres)
                }
                if (stopInfo.isEmpty()) {
                    _selection.value = SelectionState(hasLocation = true)
                    return@launch
                }

                val raw = realtimeRepo.getUpcomingTripsForStops(
                    stopInfo.keys, withinMinutes = 30)
                val resolved = gtfsRepo.resolveUpcomingTrips(raw, stopInfo)

                _selection.value = SelectionState(
                    upcoming = resolved, refreshing = false, hasLocation = true)
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadUpcomingTrips failed: ${e.message}")
                _error.emit("Грешка при зареждане: ${e.message ?: "няма връзка"}")
                _selection.value = SelectionState(hasLocation = true)
            }
        }
    }

    // ── Selecting a trip → hand everything to the service ─────────────────
    fun selectUpcomingTrip(trip: UpcomingTripInfo) {
        viewModelScope.launch {
            try {
                // Full ordered stop list for THIS exact trip_id. Because the
                // trip comes from the realtime feed, this is the vehicle's
                // REAL path — a depot short-run yields its actual truncated
                // stop list, not the standard route.
                val stops = gtfsRepo.getRemainingStops(trip.tripId, fromSequence = 0)
                if (stops.isEmpty()) {
                    _error.emit("Няма данни за маршрута на това превозно средство")
                    return@launch
                }

                val latLons = stops.map { sw ->
                    val s: Stop? = gtfsRepo.getStopById(sw.stopId)
                    Pair(s?.stopLat ?: 0.0, s?.stopLon ?: 0.0)
                }

                // Boarding index = the stop whose arrival entry was tapped.
                // Only a SEED: the service snaps to the nearest upcoming stop
                // on its first GPS fix, so starting mid-journey also works.
                val boardingIdx = stops.indexOfFirst { it.stopId == trip.stopId }
                                       .coerceAtLeast(0)

                val ctx = getApplication<Application>()
                JourneyService.start(ctx)

                val deferred = CompletableDeferred<JourneyService>()
                bindingDeferred = deferred
                bound = ctx.bindService(
                    Intent(ctx, JourneyService::class.java),
                    serviceConn,
                    Context.BIND_AUTO_CREATE
                )

                val svc = try {
                    withTimeout(5_000L) { deferred.await() }
                } catch (_: TimeoutCancellationException) {
                    _error.emit("Не може да се стартира услугата за пътуване")
                    return@launch
                } finally {
                    bindingDeferred = null
                }

                val vehicle = bg.sofia.transit.util.VehicleLabels.singular(
                    trip.routeType, gtfsRepo.isTrolleyRoute(trip.routeId))
                svc.beginJourney(
                    label   = "$vehicle ${trip.routeShortName} → ${trip.headsign}",
                    tripId  = trip.tripId,
                    stops   = stops,
                    latLons = latLons,
                    boardingStopIdx = boardingIdx
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "selectUpcomingTrip failed: ${e.message}")
                _error.emit("Грешка при стартиране: ${e.message}")
            }
        }
    }

    fun endJourney() {
        journeyService?.endJourney()
        unbind()
        JourneyService.stop(getApplication())
    }

    private fun unbind() {
        if (bound) {
            try { getApplication<Application>().unbindService(serviceConn) }
            catch (_: Exception) {}
            bound = false
        }
        journeyService = null
    }

    override fun onCleared() {
        super.onCleared()
        // ONLY unbind. Never end the journey here — the old code did, which
        // meant switching bottom-nav tabs killed an active journey.
        unbind()
    }
}
