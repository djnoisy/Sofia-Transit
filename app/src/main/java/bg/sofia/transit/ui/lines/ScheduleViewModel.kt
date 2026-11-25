package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.data.repository.RouteOfTheDay
import bg.sofia.transit.data.repository.ScheduledTime
import bg.sofia.transit.util.DateHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

@HiltViewModel
class ScheduleViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ScheduleState())
    val state: StateFlow<ScheduleState> = _state

    private var routeId: String = ""
    private var headsign: String = ""
    private var stopId: String = ""
    private var loadJob: Job? = null

    fun init(routeId: String, headsign: String, stopId: String) {
        // The view made again (rotation, a theme change): as it was — the day
        // chosen kept, the strip the same (review of 25 Nov 2026).
        if (routeId == this.routeId && headsign == this.headsign && stopId == this.stopId &&
            _state.value.days.isNotEmpty()) return
        this.routeId  = routeId
        this.headsign = headsign
        this.stopId   = stopId

        // Opens on the first day, from yesterday, with a time not yet passed
        // (RouteOfTheDay.openOn; the owner, 25 Nov 2026): a day line's
        // yesterday after midnight while its last runs are to come — the day
        // strip then begins with it — a night line's tomorrow in the evening;
        // else today.
        _state.value = _state.value.copy(loading = true, noDataReason = null)
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // One moment, in Sofia: the dates and the time of day agree, at
            // midnight too.
            val moment = ZonedDateTime.now(DateHelper.SOFIA)
            val now = moment.toLocalTime().toSecondOfDay()
            val dates = DateHelper.aroundToday(moment.toLocalDate())
            val timetables = dates.map { repo.getScheduleForDirectionAtStop(routeId, headsign, stopId, it) }
            val open = RouteOfTheDay.openOn(timetables.map { t -> t.map { it.seconds } }, 1, now) ?: 1
            val times = timetables[open]
            _state.value = ScheduleState(
                days         = DateHelper.upcomingDays(7, fromYesterday = open == 0, today = moment.toLocalDate()),
                selectedDate = dates[open],
                times        = times,
                loading      = false,
                noDataReason = if (times.isEmpty()) "Няма курсове за тази дата" else null,
                focusOn      = RouteOfTheDay.firstToCome(times.map { it.seconds }, open - 1, now)
            )
        }
    }

    /** Per-route metadata for the screen header — same source as the
     *  Lines list, so "Автобус 84" / "Тролей 2" appear consistently. */
    suspend fun getRouteMeta(routeId: String) = repo.getRouteMeta(routeId)

    fun selectDate(date: String) {
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
                date     = date
            )
            _state.value = _state.value.copy(
                selectedDate = date,
                times        = times,
                loading      = false,
                noDataReason = if (times.isEmpty()) "Няма курсове за тази дата" else null
            )
        }
    }
}
