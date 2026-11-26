package bg.sofia.transit.ui.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import bg.sofia.transit.data.db.entity.Stop
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Спирки → "Търсене" (SearchTabFragment): the stops what is typed finds
 * (GtfsRepository.searchStops) — null while nothing is looked for: nothing
 * typed, or too little (StopSearch: a name from two letters, a code from one
 * digit). The last typed counts: a search still running for what was typed
 * before is dropped.
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: GtfsRepository
) : ViewModel() {

    private val _found = MutableStateFlow<List<Stop>?>(null)
    val found: StateFlow<List<Stop>?> = _found

    private var job: Job? = null
    private var asked: String? = null

    fun search(text: String) {
        val query = text.trim()
        if (query == asked) return
        asked = query
        job?.cancel()
        if (query.isEmpty()) { _found.value = null; return }
        job = viewModelScope.launch {
            // A little while after the last letter typed, not on each.
            delay(TYPING_MS)
            try {
                // An import empties the tables before refilling them (as StopArrivalsViewModel).
                if (!repo.dataReady.value) repo.dataReady.first { it }
                val stops = repo.searchStops(query)
                val tooLittle = stops.isEmpty() && !query.all { it.isDigit() } && query.count { it.isLetterOrDigit() } < 2
                _found.value = if (tooLittle) null else stops
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "Search for '$query' failed: ${e.message}")
                _found.value = emptyList()
            }
        }
    }

    private companion object {
        const val TAG = "StopSearchVM"
        const val TYPING_MS = 250L
    }
}
