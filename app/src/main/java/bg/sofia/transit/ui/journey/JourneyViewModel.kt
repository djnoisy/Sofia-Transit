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
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.LineSearch
import bg.sofia.transit.util.LocationHelper
import bg.sofia.transit.util.VehicleLabels
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
/**
 * One row in the picker. Either a nearby arrival (line + direction known) or
 * a search hit for a line with no vehicle nearby, in which case the direction
 * is asked for on selection.
 */
data class LineChoice(
    val routeId: String,
    val routeShortName: String,
    val routeType: Int,
    /** Known for nearby arrivals; null when the direction is asked for on tap. */
    val headsign: String?,
    /**
     * The line's two endpoints, e.g. "Ж.К. Младост-1 - Бул. Никола Петков",
     * shown instead of a direction. Same source as the Lines screen.
     */
    val routeSubtitle: String = "",
    /** Concrete trip when known — only a hint; the vehicle is matched by
     *  position at journey start. */
    val tripId: String? = null,
    val boardingStopId: String? = null,
    val isNearby: Boolean = true
)

data class SelectionState(
    val choices: List<LineChoice> = emptyList(),
    val refreshing: Boolean = false,
    val hasLocation: Boolean = false,
    val query: String = "",
    /** True when showing search results rather than nearby arrivals. */
    val searching: Boolean = false
)

@HiltViewModel
class JourneyViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository,
    private val realtimeRepo: RealtimeRepository,
    private val vehicleMatcher: bg.sofia.transit.data.repository.VehicleMatcher,
    application: Application
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "JourneyVM"
        /** Stops farther than this are not plausible boarding points. */
        private const val NEARBY_RADIUS_M = 400.0
        /**
         * Effectively "whatever the feed knows about". A tight window used to
         * hide a line whose last vehicle had just passed and whose next one
         * was predicted far out — yet in reality the next one may be about to
         * arrive, and the user wanting to board it found nothing to tap.
         * Ordering is by stop distance, so a distant prediction simply sits
         * lower rather than being dropped.
         */
        private const val WITHIN_MINUTES = 180
        /** Cap on the nearby list, however many lines are in range. */
        private const val MAX_LINES = 10
        /** Nearest stops considered, mirroring the Stops tab's limit of 10. */
        private const val MAX_STOPS = 10
        private const val MAX_SEARCH_RESULTS = 25
    }

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

    // ── Nearby arrivals and search ────────────────────────────────────────

    /** Last nearby result, reused when the search box is cleared. */
    private var nearbyChoices: List<LineChoice> = emptyList()
    private var lastLat = 0.0
    private var lastLon = 0.0

    fun loadUpcomingTrips(lat: Double, lon: Double) {
        if (tracking.value is JourneyService.TrackingState.Tracking) return
        lastLat = lat
        lastLon = lon

        _selection.value = _selection.value.copy(refreshing = true, hasLocation = true)

        viewModelScope.launch {
            try {
                // Every stop within NEARBY_RADIUS_M, using the same distance
                // calculation as the Stops tab.
                //
                // Deliberately NOT pickClockStops: that keeps one stop per
                // compass sector, which is right for the Stops tab, where the
                // point is to orient the user around them. Here it silently
                // dropped lines — around a metro station most stops share a
                // bearing, so the sector filter discarded all but a couple and
                // the list showed 7 lines when 10 stops were in range.
                // Exactly the Stops tab's method: getNearestStops ordered by
                // distance, then distances computed with LocationHelper — no
                // sector filtering, which the Stops tab does not do either.
                // The only addition is the radius: a line whose stop is half a
                // kilometre away is not one you are about to board.
                val stopInfo = gtfsRepo.getNearestStops(lat, lon, limit = MAX_STOPS)
                    .map { stop ->
                        stop to LocationHelper.distanceMetres(
                            lat, lon, stop.stopLat, stop.stopLon)
                    }
                    .filter { (_, metres) -> metres <= NEARBY_RADIUS_M }
                    .associate { (stop, metres) ->
                        stop.stopId to Pair(stop.stopName, metres)
                    }
                if (stopInfo.isEmpty()) {
                    nearbyChoices = emptyList()
                    _selection.value = SelectionState(hasLocation = true)
                    return@launch
                }

                val raw = realtimeRepo.getUpcomingTripsForStops(
                    stopInfo.keys, withinMinutes = WITHIN_MINUTES)
                val resolved = gtfsRepo.resolveUpcomingTrips(raw, stopInfo)

                // The same endpoint pairs the Lines screen shows: the two
                // busiest headsigns of the route, joined. Preferred over
                // route_long_name, which repeats the start at the end
                // ("Ж.К. Младост-1 - Бул. Никола Петков - Ж.К. Младост-1").
                val subtitles = gtfsRepo.getRouteSubtitles()

                // Nearby lines come from the realtime feed, which also carries
                // routes that have no static stop_times. They cannot be
                // tracked, so they are dropped here as well as in search.
                val trackable = gtfsRepo.trackableRouteIds()

                // One row per LINE, not per direction. Two reasons: a line
                // with both directions in range used to eat two of the ten
                // slots, and — more importantly — if only one direction
                // happened to be in the feed, the other was unreachable even
                // though the user might want exactly it. The direction is
                // asked for on tap, as it already is for search results.
                //
                // tripId and boardingStopId are deliberately dropped: they
                // belong to one particular arrival, possibly in the other
                // direction, and the vehicle is matched by position at start
                // anyway.
                nearbyChoices = resolved
                    .filter { it.routeId in trackable }
                    .groupBy { it.routeId }
                    .map { (_, arrivals) -> arrivals.minBy { it.stopDistanceMetres } }
                    .sortedBy { it.stopDistanceMetres }
                    .take(MAX_LINES)
                    .map { t ->
                        LineChoice(
                            routeId        = t.routeId,
                            routeShortName = t.routeShortName,
                            routeType      = t.routeType,
                            headsign       = null,
                            routeSubtitle  = subtitles[t.routeId].orEmpty(),
                            isNearby       = true
                        )
                    }

                // Keep whatever the user has typed applied to the fresh data.
                applyQuery(_selection.value.query, refreshing = false)
            } catch (e: Exception) {
                FileLogger.e(TAG, "loadUpcomingTrips failed: ${e.message}")
                _error.emit("Грешка при зареждане: ${e.message ?: "няма връзка"}")
                _selection.value = SelectionState(hasLocation = true)
            }
        }
    }

    /** Called on every keystroke in the search box. */
    fun onQueryChanged(query: String) {
        applyQuery(query, refreshing = _selection.value.refreshing)
    }

    private fun applyQuery(query: String, refreshing: Boolean) {
        if (query.isBlank()) {
            _selection.value = SelectionState(
                choices    = nearbyChoices,
                refreshing = refreshing,
                hasLocation = true,
                query      = "",
                searching  = false
            )
            return
        }

        viewModelScope.launch {
            try {
                // Search covers the WHOLE network, not just what is nearby —
                // the case the search box exists for is being already aboard,
                // where your line is by definition not waiting at a stop near
                // you. Lines that do have a vehicle nearby still rank first.
                val all = gtfsRepo.getTrackableRoutes()
                val searchSubtitles = gtfsRepo.getRouteSubtitles()
                val nearbyIds = nearbyChoices.map { it.routeId }.toSet()

                val ranked = LineSearch.rank(
                    items = all,
                    query = query,
                    nameOf = { it.routeShortName },
                    isNearby = { it.routeId in nearbyIds }
                ).take(MAX_SEARCH_RESULTS)

                val choices = ranked.map { route ->
                    // Reuse the nearby entry when we have one: it already
                    // knows the direction, so no picker is needed.
                    nearbyChoices.firstOrNull { it.routeId == route.routeId }
                        ?: LineChoice(
                            routeId        = route.routeId,
                            routeShortName = route.routeShortName,
                            routeType      = route.routeType,
                            headsign       = null,
                            // No fallback to route_long_name on purpose: CGM
                            // sometimes names a line after a shortened
                            // variant — line 111 is officially "Ж.К.
                            // Младост-1 - Бул. Никола Петков - Ж.К.
                            // Младост-1" although it actually runs to Люлин.
                            // An empty second line is better than a wrong one.
                            routeSubtitle  = searchSubtitles[route.routeId].orEmpty(),
                            isNearby       = false
                        )
                }

                _selection.value = SelectionState(
                    choices     = choices,
                    refreshing  = refreshing,
                    hasLocation = true,
                    query       = query,
                    searching   = true
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "search failed: ${e.message}")
            }
        }
    }

    /** Directions of a line, for the picker shown on a search hit. */
    suspend fun directionsFor(routeId: String): List<String> =
        try { gtfsRepo.getDirectionHeadsigns(routeId) }
        catch (e: Exception) {
            FileLogger.e(TAG, "directionsFor failed: ${e.message}"); emptyList()
        }

    // ── Starting a journey ────────────────────────────────────────────────

    /**
     * Starts tracking the chosen line in the chosen direction.
     *
     * The concrete vehicle is resolved by POSITION, not taken from the
     * arrival the user tapped. That covers boarding the vehicle behind the
     * one you tapped, starting mid-journey, and shortened depot runs whose
     * stop list differs. When no vehicle can be matched we still start: the
     * stop list comes from the line and direction, and announcements are
     * driven by the passenger's own GPS. Only the arrival prediction at the
     * alighting stop needs a specific vehicle.
     */
    fun startJourney(choice: LineChoice, headsign: String) {
        viewModelScope.launch {
            try {
                val match = if (lastLat != 0.0 || lastLon != 0.0) {
                    vehicleMatcher.findVehicle(
                        choice.routeId, headsign, lastLat, lastLon)
                } else null

                // Prefer the matched vehicle's own trip; fall back to the
                // tapped arrival's trip, then to any trip of this direction.
                val tripId = match?.vehicle?.tripId ?: choice.tripId

                val stops = when {
                    tripId != null -> gtfsRepo.getRemainingStops(tripId, fromSequence = 0)
                    else -> gtfsRepo.getStopsForRouteDirection(choice.routeId, headsign)
                }
                if (stops.isEmpty()) {
                    _error.emit("Няма данни за маршрута на тази линия")
                    return@launch
                }

                val latLons = stops.map { sw ->
                    val st: Stop? = gtfsRepo.getStopById(sw.stopId)
                    Pair(st?.stopLat ?: 0.0, st?.stopLon ?: 0.0)
                }

                // Only a seed: the service snaps to the nearest upcoming stop
                // on its first fix, which is what makes starting mid-journey
                // work.
                val boardingIdx = choice.boardingStopId
                    ?.let { id -> stops.indexOfFirst { it.stopId == id } }
                    ?.coerceAtLeast(0) ?: 0

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

                val vehicle = VehicleLabels.singular(
                    choice.routeType, gtfsRepo.isTrolleyRoute(choice.routeId))

                svc.beginJourney(
                    label    = "$vehicle ${choice.routeShortName} → $headsign",
                    tripId   = tripId ?: "",
                    routeId  = choice.routeId,
                    headsign = headsign,
                    stops    = stops,
                    latLons  = latLons,
                    boardingStopIdx = boardingIdx
                )
            } catch (e: Exception) {
                FileLogger.e(TAG, "startJourney failed: ${e.message}")
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
