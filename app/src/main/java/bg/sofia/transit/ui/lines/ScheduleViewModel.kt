package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.util.DateHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduleState(
    val days: List<DateHelper.DayChip> = emptyList(),
    val selectedDate: String = "",        // YYYYMMDD currently shown
    val times: List<String> = emptyList(),
    val loading: Boolean = false,
    val noDataReason: String? = null      // null when data is present
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

    fun init(routeId: String, headsign: String, stopId: String) {
        this.routeId  = routeId
        this.headsign = headsign
        this.stopId   = stopId

        val days = DateHelper.upcomingDays(7)
        _state.value = _state.value.copy(days = days)
        // Default selection: today (first chip).
        days.firstOrNull()?.let { selectDate(it.date) }
    }

    /** Per-route metadata for the screen header — same source as the
     *  Lines list, so "Автобус 84" / "Тролей 2" appear consistently. */
    suspend fun getRouteMeta(routeId: String) = repo.getRouteMeta(routeId)

    fun selectDate(date: String) {
        _state.value = _state.value.copy(
            selectedDate = date,
            loading = true,
            noDataReason = null
        )
        viewModelScope.launch {
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
