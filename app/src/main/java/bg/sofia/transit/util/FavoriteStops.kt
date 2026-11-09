package bg.sofia.transit.util

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** A stop in the favourites: what the virtual board needs to open it. */
data class FavoriteStop(val stopId: String, val stopName: String, val stopCode: String?)

/**
 * The favourite stops (the owner, 9 Nov 2026): added and removed on a stop's
 * virtual board, listed under Спирки → "Любими", in the order added. Kept on
 * the phone, in the app's own preferences, as a JSON list.
 */
@Singleton
class FavoriteStops @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _all = MutableStateFlow(read())

    /** The favourites, in the order they were added; updated as they change. */
    val all: StateFlow<List<FavoriteStop>> = _all

    fun contains(stopId: String): Boolean = _all.value.any { it.stopId == stopId }

    fun add(stop: FavoriteStop) {
        if (contains(stop.stopId)) return
        save(_all.value + stop)
    }

    fun remove(stopId: String) = save(_all.value.filterNot { it.stopId == stopId })

    @Synchronized
    private fun save(list: List<FavoriteStop>) {
        val json = JSONArray()
        for (s in list) json.put(JSONObject().apply {
            put(KEY_ID, s.stopId)
            put(KEY_NAME, s.stopName)
            if (s.stopCode != null) put(KEY_CODE, s.stopCode)
        })
        prefs.edit().putString(KEY_LIST, json.toString()).apply()
        _all.value = list
    }

    private fun read(): List<FavoriteStop> = try {
        val json = JSONArray(prefs.getString(KEY_LIST, "[]") ?: "[]")
        (0 until json.length()).mapNotNull { i ->
            val o = json.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString(KEY_ID).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            FavoriteStop(id, o.optString(KEY_NAME, id),
                if (o.has(KEY_CODE)) o.optString(KEY_CODE) else null)
        }
    } catch (e: Exception) {
        FileLogger.w("FavoriteStops", "Could not read favourites: ${e.message}")
        emptyList()
    }

    private companion object {
        const val PREFS = "favorite_stops"
        const val KEY_LIST = "list"
        const val KEY_ID = "id"
        const val KEY_NAME = "name"
        const val KEY_CODE = "code"
    }
}
