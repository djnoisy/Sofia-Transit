package bg.sofia.transit.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * A direction of the line to choose in its window (RouteStopsFragment): as
 * said — "към Ж.К. ЛЮЛИН-1,2", "към ГАРА ПОДУЯНЕ (от ДЕПО ИСКЪР)", "Посока
 * без име" — its headsign, and the trip whose stops it lists; null for a
 * main direction, listed by the route of the day (getStopsForDirection);
 * [further]: one more route of a direction listed already ("(от …)").
 */
data class RouteChoice(val label: String, val headsign: String, val tripId: String?, val further: Boolean = false)

/**
 * The line's window: the directions to choose from (null until known), the
 * one shown, its stops (null while loading) and the stop the travel times
 * are counted from.
 */
data class RouteStopsState(
    val choices: List<RouteChoice>? = null,
    val selected: Int = 0,
    val stops: List<StopWithSequence>? = null,
    val start: Int = 0
)

/**
 * A line's stops along a direction, the direction chosen in the window
 * itself (the owner, 27 Nov 2026; was a screen of its own in Линии): the
 * two main directions, then the others running today and the further routes
 * of each — "Други направления" before, one list now, no heading
 * (GtfsRepository.getDirectionsForRoute, getOtherDirectionsForRoute).
 * From a board's row: its direction chosen — of its routes, the one calling
 * at the board's stop; else by the route it takes through the stop
 * (GtfsRepository.lineAtStop) — and that stop counted from.
 */
@HiltViewModel
class RouteStopsViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(RouteStopsState())
    val state: StateFlow<RouteStopsState> = _state

    private var routeId = ""
    /** From a board: the stop (its pole), and the choice listing the route through it. */
    private var boardStop: String? = null
    private var boardChoice = -1
    private var boardTrip: String? = null
    private var loadJob: Job? = null
    private var stopsJob: Job? = null
    /** Stops read already, by choice, on opening from a board: not read again for the one shown. */
    private val read = HashMap<Int, List<StopWithSequence>>()

    /**
     * The directions of [routeId], once; from a board's row ([boardHeadsign],
     * the route through the stop [boardTripId], the stop [boardStopId]) its
     * direction shown, else the first.
     */
    fun load(routeId: String, boardHeadsign: String?, boardTripId: String?, boardStopId: String?) {
        if (loadJob != null) return
        this.routeId = routeId
        boardStop = boardStopId
        boardTrip = boardTripId
        loadJob = viewModelScope.launch {
            // An import empties the tables before refilling them (as StopArrivalsViewModel).
            if (!repo.dataReady.value) repo.dataReady.first { it }
            val main = attempt("Directions of $routeId") { repo.getDirectionsForRoute(routeId) }.orEmpty()
            // Apart: the others failing, the main two are still offered (review of 28 Nov 2026).
            val others = attempt("Other directions of $routeId") { repo.getOtherDirectionsForRoute(routeId, main) }.orEmpty()
            val choices = (main.map { RouteChoice(said(it.tripHeadsign, null), it.tripHeadsign.orEmpty(), null) } +
                others.map { o -> RouteChoice(said(o.trip.tripHeadsign, o.via), o.trip.tripHeadsign.orEmpty(), o.trip.tripId, o.via != null) })
                .toMutableList()
            var selected = 0
            if (boardTripId != null && boardStopId != null) {
                // The board row's direction: of its choices, the one whose own
                // route calls at the board's stop — a further route, when that
                // is what calls there (runs from ДЕПО ИСКЪР; review of 28 Nov
                // 2026: else the main one was shown with the depot run's stops,
                // and could not be shown with its own) — its own stops then.
                // None calling there: the first of the direction (not a further
                // route), with the route through the stop; none of the
                // direction at all: that route, first, on its own.
                val key = RouteOfTheDay.directionKey(boardHeadsign)
                val same = choices.indices.filter { RouteOfTheDay.directionKey(choices[it].headsign) == key }
                val calling = same.firstOrNull { i -> stopsOf(choices[i]).also { read[i] = it }.any { it.stopId == boardStopId } }
                when {
                    calling != null -> selected = calling
                    same.isNotEmpty() -> {
                        boardChoice = same.sortedBy { if (choices[it].further) 1 else 0 }.first()
                        selected = boardChoice
                    }
                    else -> {
                        choices.add(0, RouteChoice(said(boardHeadsign, null), boardHeadsign.orEmpty(), boardTripId))
                        boardChoice = 0
                    }
                }
            }
            FileLogger.i(TAG, "Directions of $routeId: ${choices.joinToString { it.label }}; shown ${choices.getOrNull(selected)?.label}")
            _state.value = RouteStopsState(choices = choices, selected = selected)
            showStops(selected)
        }
    }

    /** Another direction chosen: its stops, counted from the first — or the board's stop, when on its route. */
    fun select(index: Int) {
        val choices = _state.value.choices ?: return
        if (index == _state.value.selected || index !in choices.indices) return
        _state.value = _state.value.copy(selected = index, stops = null, start = 0)
        showStops(index)
    }

    /** The travel times counted from the stop at [index]. */
    fun startAt(index: Int) {
        val stops = _state.value.stops ?: return
        if (index in stops.indices) _state.value = _state.value.copy(start = index)
    }

    private fun showStops(index: Int) {
        val choice = _state.value.choices?.getOrNull(index)
            ?: run { _state.value = _state.value.copy(stops = emptyList()); return }
        stopsJob?.cancel()
        stopsJob = viewModelScope.launch {
            val stops = if (index == boardChoice) attempt("Stops of $boardTrip") { repo.getStopsOfTrip(boardTrip!!) }.orEmpty()
                else read.remove(index) ?: stopsOf(choice)
            val start = boardStop?.let { b -> stops.indexOfFirst { it.stopId == b } }?.takeIf { it >= 0 } ?: 0
            _state.value = _state.value.copy(stops = stops, start = start)
        }
    }

    /** The stops [choice] lists: a main direction's route of the day, another's trip; none when they cannot be read. */
    private suspend fun stopsOf(choice: RouteChoice): List<StopWithSequence> =
        attempt("Stops of $routeId towards '${choice.headsign}'") {
            choice.tripId?.let { repo.getStopsOfTrip(it) } ?: repo.getStopsForDirection(routeId, choice.headsign)
        }.orEmpty()

    /** [what] read, or null — said in the log — when it fails. */
    private suspend fun <T> attempt(what: String, read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "$what failed: ${e.message}")
        null
    }

    /** A stop's code ("0169"), for its window: its board gathers the stop's poles by it. */
    suspend fun stopCode(stopId: String): String? = repo.getStopById(stopId)?.stopCode

    private companion object {
        const val TAG = "RouteStopsVM"

        /** A direction as said: "към X", with what tells one more route apart ("(от ДЕПО ИСКЪР)"). */
        fun said(headsign: String?, via: String?): String {
            val hs = headsign?.trim().orEmpty()
            val name = if (hs.isEmpty() || hs == "—") "Посока без име" else "към $hs"
            return name + (via?.let { " ($it)" } ?: "")
        }
    }
}
