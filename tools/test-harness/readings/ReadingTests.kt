// When the vehicles around us are read, and from what (JourneyService:
// readingFrom, readingAccepted, nextLookMs — readVehicles and the loop of
// startVehicleTracking). run_reading_tests.sh copies the REAL functions into
// the marked place below; around them, a clock and a vehicle feed that keeps
// its last copy 20 s, as RealtimeRepository does (fetchProtoCached:
// CACHE_TTL_MS, the old copy served when a fetch fails), and other parts of
// the app fetching it too. Checked: the first reading after departure, the
// half-minute rhythm, never a copy from before departure, how often the
// network is asked — waiting long at the stop, begun as the bus pulls away,
// begun again soon after a journey, other screens fetching, the network down
// at departure or mid-ride, a slow network.
object J {
    const val VEHICLE_CHECK_INTERVAL_MS = 30_000L   // JourneyService
    const val DEFERRED_CHECK_RETRY_MS = 5_000L      // JourneyService
// @@READINGS@@
}

const val TTL = 20_000L                              // RealtimeRepository.CACHE_TTL_MS
const val S = 1000L
/** The clock of the app is epoch time: "never" (0) is long ago, as on the phone. */
const val T0 = 1_792_996_539_103L

class Result(val readings: List<Pair<Long, Long>>, val requests: List<Long>) {
    /** When each reading was taken, and the time of the feed it was taken from. */
    val first get() = readings.firstOrNull()
    /** The gaps between readings — and from the last to the end, so that readings stopping show. */
    fun gaps() = readings.zipWithNext { a, b -> b.first - a.first }
    /** How long after [from] the first reading was taken; a day if none. */
    fun firstAfter(from: Long) = (readings.firstOrNull()?.first ?: (from + 86_400_000L)) - from
    /** Readings throughout: none missing until [until] (a gap of [most] at most, from [from]). */
    fun steady(from: Long, until: Long, most: Long): Boolean {
        val times = listOf(from) + readings.map { it.first } + until
        return readings.isNotEmpty() && times.zipWithNext { a, b -> b - a }.all { it <= most }
    }
}

/**
 * Tracking begun at 0, departure at [departAt], until [until] — all relative
 * to T0. The feed was last fetched at [prevFeedAt] (null: never), answers
 * after [latency], fails fast while [down]; other screens fetch it at [others].
 */
fun simulate(departAt: Long, until: Long, latency: Long = 500, down: (Long) -> Boolean = { false },
             others: List<Long> = emptyList(), prevFeedAt: Long? = null): Result {
    val r = simulateAbs(T0 + departAt, T0 + until, latency, { down(it - T0) }, others.map { T0 + it },
        prevFeedAt?.let { T0 + it } ?: 0L)
    return Result(r.readings.map { (a, b) -> a - T0 to b - T0 }, r.requests.map { it - T0 })
}

fun simulateAbs(departAt: Long, until: Long, latency: Long, down: (Long) -> Boolean,
                others: List<Long>, prevFeedAt: Long): Result {
    var feedAt = prevFeedAt
    var t = T0
    var lastAttempt = 0L; var lastFeed = 0L
    val readings = ArrayList<Pair<Long, Long>>(); val requests = ArrayList<Long>()
    var o = 0
    fun othersUpTo(now: Long) {
        while (o < others.size && others[o] <= now) {
            val at = others[o++]
            if ((feedAt == 0L || at - feedAt >= TTL) && !down(at)) feedAt = at + latency
        }
    }
    /** RealtimeRepository.getVehicleSnapshot: the copy if fresh, else a fetch (the old copy if it fails). */
    fun get(): Long? {
        if (feedAt != 0L && t - feedAt < TTL) return feedAt
        requests += t
        if (down(t)) return feedAt.takeIf { it != 0L }
        t += latency
        feedAt = t
        return feedAt
    }
    while (t <= until) {
        othersUpTo(t)
        val departed = if (t >= departAt) departAt else 0L
        if (departed != 0L) {
            val snap = when (J.readingFrom(t, feedAt, lastAttempt, lastFeed, departed)) {
                J.ReadingFrom.FETCH -> { lastAttempt = t; get() }
                J.ReadingFrom.CACHED -> feedAt.takeIf { it != 0L }
                J.ReadingFrom.NONE -> null
            }
            if (snap != null && J.readingAccepted(snap, lastFeed, departed)) { lastFeed = snap; readings += t to snap }
        }
        t += J.nextLookMs(departed, lastFeed)
    }
    return Result(readings, requests)
}

var fails = 0
fun check(n: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n ${detail()}") }
}
fun show(r: Result) = "readings " + r.readings.take(8).joinToString { "${it.first / 1000.0}s(feed ${it.second / 1000.0})" } +
    "; requests " + r.requests.take(12).joinToString { "${it / 1000.0}" }

fun main() {
    // Waiting 4 min at the stop: nothing asked before departure; the first
    // reading within 5 s of it; then every 30 s (plus the answer's time).
    val d1 = 240 * S
    val r1 = simulate(d1, 600 * S)
    check("long wait: no request before departure", r1.requests.none { it < d1 }) { show(r1) }
    check("long wait: first reading within 5 s of departure", r1.firstAfter(d1) in 0..5 * S) { show(r1) }
    check("long wait: then every 30 s", r1.gaps().all { it in 30 * S..31 * S } && r1.steady(d1, 600 * S, 31 * S)) { show(r1) }
    check("long wait: one request per reading", r1.requests.size == r1.readings.size) { show(r1) }

    // Begun as the bus pulls away (26 Oct 2026, b): departure 9 s in.
    val d2 = 9 * S
    val r2 = simulate(d2, 300 * S)
    check("begun as it pulls away: first reading within 5 s of departure", r2.firstAfter(d2) in 0..5 * S) { show(r2) }
    check("begun as it pulls away: then every 30 s", r2.gaps().all { it in 30 * S..31 * S }) { show(r2) }

    // Begun again soon after a journey: its last feed 1 s before, or 2 s
    // before departure. Never taken; the first reading once the copy is old.
    for (prev in listOf(-1 * S, d2 - 2 * S)) {
        val r = simulate(d2, 300 * S, prevFeedAt = prev)
        check("begun again (last feed at ${prev / 1000} s): no copy from before departure",
            r.readings.all { it.second >= d2 }) { show(r) }
        // The copy is kept 20 s; looked at every 5 s; the answer's 0.5 s.
        check("begun again (last feed at ${prev / 1000} s): first reading within 26 s of departure (the copy's 20 s)",
            r.firstAfter(d2) in 0..26 * S && r.requests.none { it < d2 }) { show(r) }
        check("begun again (last feed at ${prev / 1000} s): then every 30 s", r.gaps().all { it in 30 * S..31 * S }) { show(r) }
    }

    // Another screen fetching (only Diagnostics does — by hand), here every
    // 7 s throughout, as hard as it gets: its copies are taken when newer
    // than ours; never one from before departure; the first reading within
    // 26 s (a copy fetched just before departure is kept 20 s); never a gap
    // over 31 s.
    val r4 = simulate(d1, 600 * S, others = (0..600 step 7).map { it * S })
    check("other screens fetching: no copy from before departure", r4.readings.all { it.second >= d1 }) { show(r4) }
    check("other screens fetching: first reading within 26 s", r4.firstAfter(d1) in 0..26 * S) { show(r4) }
    // And once, 3 s before departure: the same.
    val r4b = simulate(d1, 600 * S, others = listOf(d1 - 3 * S))
    check("Diagnostics fetching 3 s before departure: no copy from before it, first within 26 s",
        r4b.readings.all { it.second >= d1 } && r4b.firstAfter(d1) in 0..26 * S) { show(r4b) }
    check("other screens fetching: readings throughout, no gap over 31 s", r4.steady(d1, 600 * S, 31 * S)) { show(r4) }

    // The network down from 10 s before departure to 60 s after: asked every
    // 5 s meanwhile; the first reading within 5 s of it coming back.
    val r5 = simulate(d1, 600 * S, down = { it in (d1 - 10 * S) until (d1 + 60 * S) })
    check("down at departure: first reading within 5.5 s of the network back",
        r5.firstAfter(d1 + 60 * S) in 0..5_500L) { show(r5) }
    check("down at departure: asked every 5 s meanwhile, no more",
        r5.requests.count { it < d1 + 60 * S }.let { it in 11..13 }) { show(r5) }
    check("down at departure: then every 30 s", r5.gaps().all { it in 30 * S..31 * S }) { show(r5) }

    // The network down for a minute mid-ride: no rush of requests (one each
    // half minute, as before); readings again once it is back.
    val r6 = simulate(d1, 600 * S, down = { it in (d1 + 100 * S) until (d1 + 160 * S) })
    check("down mid-ride: one request each half minute meanwhile",
        r6.requests.count { it in (d1 + 100 * S) until (d1 + 160 * S) } <= 2) { show(r6) }
    check("down mid-ride: a reading within 31 s of it back",
        r6.readings.any { it.first in (d1 + 160 * S)..(d1 + 191 * S) }) { show(r6) }

    // A slow network: 8 s to answer.
    val r7 = simulate(d2, 300 * S, latency = 8 * S)
    check("slow network: first reading asked within 5 s of departure, fetched after it",
        r7.firstAfter(d2) in 0..13 * S && (r7.first?.second ?: -1L) >= d2 && r7.requests.first() - d2 in 0..5 * S) { show(r7) }
    check("slow network: then every 38 s at most", r7.gaps().all { it in 30 * S..38 * S }) { show(r7) }

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
