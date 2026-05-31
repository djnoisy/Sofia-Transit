package bg.sofia.transit.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.data.repository.GtfsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(
    val stops: List<Stop> = emptyList(),
    val lastQuery: String = ""
)

@HiltViewModel
class SearchStopsViewModel @Inject constructor(
    private val gtfsRepo: GtfsRepository
) : ViewModel() {

    private val _results = MutableStateFlow(SearchState())
    val results: StateFlow<SearchState> = _results

    private val _error = MutableSharedFlow<String>()
    val error: SharedFlow<String> = _error

    /**
     * Searches the static DB for stops matching [query] (code or name).
     * The query is trimmed; an empty query clears the result list.
     * Up to 10 results are returned.
     */
    fun search(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            _results.value = SearchState()
            return
        }
        viewModelScope.launch {
            try {
                val stops = gtfsRepo.searchStops(trimmed, limit = 10)
                _results.value = SearchState(stops = stops, lastQuery = trimmed)
            } catch (e: Exception) {
                _error.emit("Грешка при търсене: ${e.message}")
            }
        }
    }
}
