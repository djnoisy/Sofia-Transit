package bg.sofia.transit.ui.lines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.util.DateHelper
import bg.sofia.transit.util.DateHelper.DayType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ScheduleState(
    val dayType: DayType = DayType.TODAY,
    val effectiveDate: String? = null,    // YYYYMMDD that we resolved to
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

    private var availableDates: Set<String> = emptySet()

    fun init(routeId: String, headsign: String, stopId: String) {
        this.routeId  = routeId
        this.headsign = headsign
        this.stopId   = stopId
        viewModelScope.launch {
            availableDates = repo.getAllAvailableScheduleDates().toSet()
            // Default selection: today's actual date
            selectDayType(DayType.TODAY)
        }
    }

    fun selectDayType(dayType: DayType) {
        _state.value = _state.value.copy(dayType = dayType, loading = true)
        viewModelScope.launch {
            val date = DateHelper.representativeDate(dayType, availableDates)
            if (date == null) {
                _state.value = ScheduleState(
                    dayType       = dayType,
                    effectiveDate = null,
                    times         = emptyList(),
                    loading       = false,
                    noDataReason  = "Няма данни за избрания вид ден"
                )
                return@launch
            }
            val times = repo.getScheduleForDirectionAtStop(
                routeId  = routeId,
                headsign = headsign,
                stopId   = stopId,
                date     = date
            )
            _state.value = ScheduleState(
                dayType       = dayType,
                effectiveDate = date,
                times         = times,
                loading       = false,
                noDataReason  = if (times.isEmpty()) "Няма пътувания за този ден" else null
            )
        }
    }
}
