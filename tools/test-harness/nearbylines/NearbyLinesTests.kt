// When the lines near the passenger are loaded, and for which position
// (util/NearbyLinesLoader.kt). Positions on a line, in metres: (x, 0).
import bg.sofia.transit.util.NearbyLinesLoader
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

var fails = 0
fun eq(name: String, want: Any?, got: Any?) { if (want != got) { fails++; println("FAIL $name: want <$want> got <$got>") } }

/** A loader whose loads wait until finished by the test. */
class Rig(scope: CoroutineScope) {
    var now = 1_000_000L
    val loads = mutableListOf<Double>()
    private val gates = ArrayDeque<CompletableDeferred<Boolean>>()
    var starts = 0; var dones = 0
    /** Whether the loader said it was running, at each start and each publish. */
    val runningAtStart = mutableListOf<Boolean>(); val runningAtDone = mutableListOf<Boolean>()
    lateinit var loader: NearbyLinesLoader
    init {
        loader = NearbyLinesLoader(
            scope = scope,
            distanceM = { a, _, b, _ -> kotlin.math.abs(a - b) },
            onStart = { starts++; runningAtStart += loader.isRunning },
            load = { x, _ -> loads += x; CompletableDeferred<Boolean>().also { gates.addLast(it) }.await() },
            onDone = { dones++; runningAtDone += loader.isRunning },
            nowMs = { now })
    }
    /** The load running ends (ok or failed), and what follows runs. */
    suspend fun finish(ok: Boolean = true) {
        val gate = gates.removeFirstOrNull() ?: run { fails++; println("FAIL no load running to finish"); return }
        gate.complete(ok); repeat(3) { yield() }
    }
    suspend fun at(x: Double, force: Boolean = false) { loader.request(x, 0.0, force); repeat(3) { yield() } }
}

fun main(): Unit = runBlocking {
    // L1 — the case of the bug: the last known position, then a fresh fix far
    // from it while the first still loads. Before, the fix was dropped.
    Rig(this).run {
        at(0.0); at(1000.0); finish(); finish()
        eq("L1 loads", listOf(0.0, 1000.0), loads); eq("L1 published once", 1, dones); eq("L1 started once", 1, starts)
    }
    // L2 — the fix within 50 m of the position loading: nothing redone.
    Rig(this).run {
        at(0.0); at(30.0); finish()
        eq("L2 loads", listOf(0.0), loads); eq("L2 published", 1, dones)
    }
    // L3 — several positions during one load: only the newest after it.
    Rig(this).run {
        at(0.0); at(400.0); at(900.0); finish(); finish()
        eq("L3 loads", listOf(0.0, 900.0), loads)
    }
    // L4 — no load running: a position close to one loaded moments ago is
    // skipped; later, farther, or "Обнови" — loaded.
    Rig(this).run {
        at(0.0); finish()
        at(20.0); eq("L4 close and recent: skipped", listOf(0.0), loads)
        at(20.0, force = true); finish(); eq("L4 Обнови", listOf(0.0, 20.0), loads)
        at(80.0); finish(); eq("L4 50 m or more", listOf(0.0, 20.0, 80.0), loads)
        now += NearbyLinesLoader.FRESH_MS; at(80.0); finish()
        eq("L4 30 s later, the same place", listOf(0.0, 20.0, 80.0, 80.0), loads)
        eq("L4 published each time", 4, dones)
    }
    // L5 — a failed load does not count as loaded: the same place again loads.
    Rig(this).run {
        at(0.0); finish(ok = false); at(0.0); finish()
        eq("L5 loads", listOf(0.0, 0.0), loads)
    }
    // L7 — a failed load, and a position within 50 m came meanwhile: tried
    // (nothing was loaded to compare it with).
    Rig(this).run {
        at(0.0); at(30.0); finish(ok = false); finish()
        eq("L7 loads", listOf(0.0, 30.0), loads); eq("L7 published once", 1, dones)
    }
    // L8 — "refreshing": on while loads run, off when the list is published
    // (a search published later asks it rather than carrying it on).
    Rig(this).run {
        at(0.0); eq("L8 running during the load", true, loader.isRunning)
        at(1000.0); finish(); eq("L8 still running for the follow-up", true, loader.isRunning)
        finish()
        eq("L8 on at start", listOf(true), runningAtStart); eq("L8 off at publish", listOf(false), runningAtDone)
        eq("L8 off after", false, loader.isRunning)
    }
    // L6 — the phone's last known position: used only if at most 2 minutes old.
    eq("L6 now", true, NearbyLinesLoader.lastKnownUsable(0))
    eq("L6 2 min", true, NearbyLinesLoader.lastKnownUsable(120_000))
    eq("L6 older", false, NearbyLinesLoader.lastKnownUsable(120_001))
    eq("L6 from the future (clock)", false, NearbyLinesLoader.lastKnownUsable(-1))
    println(if (fails == 0) "ALL PASS" else "$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
