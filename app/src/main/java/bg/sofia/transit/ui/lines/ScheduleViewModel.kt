package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.data.repository.ScheduledTime
import bg.sofia.transit.data.repository.StopLine
import bg.sofia.transit.util.DateHelper
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import javax.inject.Inject

data class ScheduleState(
    val days: List<DateHelper.DayChip> = emptyList(),
    val selectedDate: String = "",        // YYYYMMDD currently shown
    /** By time; the line's short runs of other directions marked with where they end. */
    val times: List<ScheduledTime> = emptyList(),
    val loading: Boolean = false,
    val noDataReason: String? = null,     // null when data is present
    /**
     * On opening: the first time not yet passed (ScheduledTime.seconds), its
     * hour to get TalkBack's focus (RouteOfTheDay.firstToCome; the owner,
     * 25 Nov 2026); null otherwise — a day tapped, or none to come.
     */
    val focusOn: Int? = null
)

/**
 * The "Разписание" tab of a stop's window (ScheduleFragment; the owner,
 * 26 Nov 2026): the lines calling at the stop to choose from
 * (GtfsRepository.linesAtStop) — [lines], null until known — the one shown
 * ([selected]), and its timetable there ([state]), as Линии showed it.
 */
@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state

    private val _lines = MutableStateFlow<List<StopLine>?>(null)
    val lines: StateFlow<List<StopLine>?> = _lines

    /** The index in [lines] of the line shown. */
    var selected = 0
        private set

    private var linesJob: Job? = null
    private var routeId: String = ""
    private var headsign: String = ""
    private var stopId: String = ""
    private var way: List<RouteOfTheDay.Stop>? = null
    private var loadJob: Job? = null

    /** The date, in Sofia, the timetable was last opened on (init). */
    private var openedOn: java.time.LocalDate? = null

    /**
     * The lines calling at the stop [stopId], once: shown, the one asked for
     * — [routeId] towards [headsign] at that pole, from a stop of a direction
     * in Линии — else the first (the owner, 26 Nov 2026), its timetable
     * opened.
     */
    fun loadLines(stopId: String, routeId: String?, headsign: String?) {
        if (linesJob != null) return
        _state.value = ScheduleState(loading = true)
        linesJob = viewModelScope.launch {
            val lines = try {
                // An import empties the tables before refilling them (as StopArrivalsViewModel).
                if (!repo.dataReady.value) repo.dataReady.first { it }
                repo.linesAtStop(stopId, routeId?.let { it to headsign.orEmpty() })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "Lines at $stopId failed: ${e.message}")
                emptyList()
            }
            selected = routeId?.let { r -> lines.indexOfFirst { it.matches(r, headsign.orEmpty(), stopId) } }
                ?.takeIf { it >= 0 } ?: 0
            FileLogger.i(TAG, "Lines at $stopId: ${lines.size} (${lines.joinToString { "${it.routeShortName} → ${it.headsign} at ${it.stopId}" }}); " +
                "shown ${lines.getOrNull(selected)?.let { "${it.routeShortName} → ${it.headsign}" }}")
            _lines.value = lines
            if (lines.isEmpty()) _state.value = ScheduleState(noDataReason = "Няма линии с разписание на тази спирка")
            else open()
        }
    }

    /** The line at [index] in [lines] chosen: its timetable opened, as on opening. */
    fun select(index: Int) {
        if (index == selected || _lines.value?.getOrNull(index) == null) return
        selected = index
        open()
    }

    /**
     * The timetable of the line shown opened afresh — each time it is shown
     * again (the owner, 25 Nov 2026) — none while the lines are not known.
     */
    fun open() {
        val line = _lines.value?.getOrNull(selected) ?: return
        init(line.routeId, line.headsign, line.stopId, line.way.takeIf { it.isNotEmpty() })
    }

    /**
     * Whether it is another day, in Sofia, than the one the timetable was
     * opened on — the screen left open overnight, its "днес" and its focus of
     * yesterday: to be opened afresh (ScheduleFragment.onResume).
     */
    fun openedBeforeToday(): Boolean =
        openedOn?.let { it != java.time.LocalDate.now(DateHelper.SOFIA) } ?: false

    private fun init(routeId: String, headsign: String, stopId: String, way: List<RouteOfTheDay.Stop>?) {
        // Each time the screen is shown again — a tab switched back, a
        // rotation, the next day — opened afresh, on the time it is then
        // (review of 25 Nov 2026: kept, it focused a time long passed).
        this.routeId  = routeId
        this.headsign = headsign
        this.stopId   = stopId
        this.way      = way

        // Opens on the first day, from yesterday, with a time not yet passed
        // (RouteOfTheDay.openOn; the owner, 25 Nov 2026): a day line's
        // yesterday after midnight while its last runs are to come — the day
        // strip then begins with it — a night line's tomorrow in the evening;
        // else today.
        // Nothing of before shown meanwhile: its days and "днес" may be of
        // yesterday (review of 25 Nov 2026).
        _state.value = ScheduleState(loading = true)
        loadJob?.cancel()
        // One moment, in Sofia: the dates and the time of day agree, at
        // midnight too.
        val moment = ZonedDateTime.now(DateHelper.SOFIA)
        openedOn = moment.toLocalDate()
        loadJob = viewModelScope.launch {
            val now = moment.toLocalTime().toSecondOfDay()
            val dates = DateHelper.aroundToday(moment.toLocalDate())
            val timetables = dates.map { repo.getScheduleForDirectionAtStop(routeId, headsign, stopId, it, way) }
            val open = RouteOfTheDay.openOn(timetables.map { t -> t.map { it.seconds } }, 1, now) ?: 1
            val times = timetables[open]
            val focusOn = RouteOfTheDay.firstToCome(times.map { it.seconds }, open - 1, now)
            _state.value = ScheduleState(
                days         = DateHelper.upcomingDays(7, fromYesterday = open == 0, today = moment.toLocalDate()),
                selectedDate = dates[open],
                times        = times,
                loading      = false,
                noDataReason = if (times.isEmpty()) "Няма курсове за тази дата" else null,
                focusOn      = focusOn
            )
        }
    }

    fun selectDate(date: String) {
        if (_lines.value.isNullOrEmpty()) return
        _state.value = _state.value.copy(
            selectedDate = date,
            loading = true,
            noDataReason = null,
            focusOn = null
        )
        // The day chosen last is the one shown: a load for a day tapped
        // before is dropped (each reads all the line's trips of its day).
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val times = repo.getScheduleForDirectionAtStop(
                routeId  = routeId,
                headsign = headsign,
                stopId   = stopId,
                date     = date,
                way      = way
            )
            _state.value = _state.value.copy(
                selectedDate = date,
                times        = times,
                loading      = false,
                noDataReason = if (times.isEmpty()) "Няма курсове за тази дата" else null
            )
        }
    }

    private companion object {
        const val TAG = "ScheduleVM"
    }
}
