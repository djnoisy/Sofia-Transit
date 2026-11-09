package bg.sofia.transit.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * When the lines near the passenger ("Пътуване", before a journey) are
 * loaded, and for which position (the owner, 9 Nov 2026).
 *
 * One load at a time. A position that comes while one runs used to be
 * dropped: the screen asks first with the phone's last known position —
 * possibly from somewhere else entirely — and a second later with a fresh
 * fix, and with the first still loading the fresh one was lost; standing at
 * the stop, no other came (a new fix only after 50 m), and the list stayed
 * for the other place until "Обнови". Now the newest such position is kept
 * and loaded once the running load is over — if it is [MIN_MOVE_M] or more
 * from the one just loaded: the list is not redone for a few metres. The
 * second load reuses the feed the first downloaded (kept 20 s), so it costs
 * no download, and the list is published once, after the last load.
 *
 * Likewise, a position that comes with no load running is skipped when it
 * is within [MIN_MOVE_M] of a position loaded less than [FRESH_MS] ago — the
 * last known position and the fresh fix a moment later — unless forced
 * ("Обнови").
 *
 * Called on one thread only (the main one): no locking.
 */
class NearbyLinesLoader(
    private val scope: CoroutineScope,
    private val distanceM: (Double, Double, Double, Double) -> Double,
    /** Before the first load of a run: the list is being refreshed. */
    private val onStart: () -> Unit,
    /** Loads for a position; false if it failed (then it does not count as loaded). */
    private val load: suspend (lat: Double, lon: Double) -> Boolean,
    /** After the last load of a run: the list is published. */
    private val onDone: () -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    companion object {
        /** Closer than this to the position loaded, a position changes nothing. */
        const val MIN_MOVE_M = 50.0
        /** A load younger than this is not redone for a position close to it. */
        const val FRESH_MS = 30_000L
        /**
         * The phone's last known position is used only if younger than this;
         * an older one may be from somewhere else (the metro, a ride with no
         * location asked for) — then the fresh fix is waited for instead.
         */
        const val LAST_KNOWN_MAX_AGE_MS = 120_000L

        fun lastKnownUsable(ageMs: Long): Boolean = ageMs in 0..LAST_KNOWN_MAX_AGE_MS
    }

    private class Loaded(val lat: Double, val lon: Double, val atMs: Long)

    private var job: Job? = null
    private var pending: Pair<Double, Double>? = null
    private var loaded: Loaded? = null

    fun request(lat: Double, lon: Double, force: Boolean = false) {
        if (job?.isActive == true) {
            pending = lat to lon
            return
        }
        val last = loaded
        if (!force && last != null && nowMs() - last.atMs < FRESH_MS &&
            distanceM(last.lat, last.lon, lat, lon) < MIN_MOVE_M) return
        job = scope.launch {
            onStart()
            var at = lat to lon
            while (true) {
                if (load(at.first, at.second)) loaded = Loaded(at.first, at.second, nowMs())
                val next = pending
                pending = null
                if (next == null ||
                    distanceM(at.first, at.second, next.first, next.second) < MIN_MOVE_M) break
                at = next
            }
            onDone()
        }
    }
}
