package bg.sofia.transit.data.repository
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Test double of the real repository: same API surface the worker/activity use. */
class GtfsRepository(val context: Context) {
    companion object { const val EXTERNAL_DIR_NAME = "gtfs" }
    val mutex = Mutex()
    val _initialLoadDone = MutableStateFlow(false)
    val initialLoadDone: StateFlow<Boolean> = _initialLoadDone
    @Volatile var db: String? = null
    @Volatile var busy = false
    var loadCount = 0
    var loadDelayMs = 0L
    /** Number of following loads that fail (to simulate a failing rollback). */
    var failLoads = 0
    var retried = 0
    var startCalls = 0
    fun isImportBusy() = busy || mutex.isLocked
    suspend fun isDatabaseReady(): Boolean { val r = db != null; if (r) _initialLoadDone.value = true; return r }
    fun getActiveDataDir(): File? {
        val dir = File(context.filesDir, EXTERNAL_DIR_NAME)
        val req = listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
        return if (dir.isDirectory && req.all { File(dir, it).exists() }) dir else null
    }
    suspend fun loadStaticData() = mutex.withLock {
        loadCount++
        if (loadDelayMs > 0) kotlinx.coroutines.delay(loadDelayMs)
        val dir = getActiveDataDir()
        db = null
        if (failLoads > 0) { failLoads--; throw Exception("simulated load failure") }
        val stops = if (dir != null) File(dir, "stops.txt").readText()
                    else File(context.assetsRoot, "gtfs/stops.txt").readText()
        if (stops.contains("BROKEN")) throw Exception("parse error in stops.txt")
        db = (if (dir != null) "downloaded:" else "bundled:") + stops
        _initialLoadDone.value = true
    }
    fun retryInitialLoad() { retried++ }
    fun startInitialLoadIfNeeded() { startCalls++ }
}
