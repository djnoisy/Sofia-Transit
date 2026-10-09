package bg.sofia.transit.service

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Which vehicle we are in, and when we get off it — by "metres travelled
 * together" (HANDOVER: "Нов подход", stage 1).
 *
 * Three facts carry it. A passenger is in one vehicle at a time and changes
 * it only by getting off. The vehicle they are in is where they are at the
 * same moment, moving when they move and standing when they stand. And once
 * they get off, it goes on while they stay.
 *
 * So each vehicle near us earns the metres we travel while it is beside us
 * at the moment of each of its reports — its report time against where we
 * were at that same second, from our own track — and the vehicle we are in
 * is the one that has travelled furthest with us. Nothing is guessed about
 * where a vehicle has got to since it reported: the rules this replaces
 * moved every report forward by our average speed, and on 7 Oct 2026 that
 * put a bus 728 m behind us at 0 m.
 *
 * What follows from it without rules of its own:
 *  - standing beside a vehicle earns nothing (we did not move), so a stop
 *    full of buses proves nothing, and neither does a red light;
 *  - a vehicle going the other way is beside us only while the two of us
 *    cover the 60 m between "30 m ahead" and "30 m behind", so it earns at
 *    most about that for the whole encounter, however long;
 *  - one that turns up beside us starts from nothing, and cannot outdo the
 *    vehicle that has been with us all along.
 *
 * Against poor positions (the owner's question of 7 Oct 2026): our place at
 * a moment is read off a line fitted through the accurate fixes within a
 * few seconds of it, not one fix, with a fix that jumped left out; fixes
 * worse than [ACCURATE] count for nothing either way; a report neither beside us nor clearly apart is
 * bridged over rather than breaking the run; and parting takes two reports
 * in a row. A report whose moment our fixes cannot yet place waits for the
 * fixes that follow it. With poor positions the model holds what it has
 * rather than deciding anew.
 *
 * Pure Kotlin, no Android types, so it can be tested and replayed on its own
 * (tools/test-harness/run_model_tests.sh). Stage 2 runs it beside the
 * current rules through [RideShadow], logging only; nothing reads it yet.
 */
class RideModel(
    /** The line the passenger chose; it wins a tie between vehicles. */
    private val chosenRouteId: String = ""
) {

    /**
     * One position of ours. [timeMs] is the receiver's own time of the fix
     * (Location.time, satellite time for GPS) — the clock the vehicles'
     * reports are on; [receivedMs] is the phone's clock when it arrived.
     * [accuracy] in metres, null when not given.
     */
    data class Fix(
        val timeMs: Long, val receivedMs: Long,
        val lat: Double, val lon: Double, val accuracy: Double?
    )

    /**
     * One vehicle in one reading of the feed. [key] identifies the vehicle
     * itself (its vehicle id, or the trip id when there is none), so a new
     * trip begun at a terminus is still the same vehicle. [timeMs] is the
     * report's own time.
     */
    data class Report(
        val key: String, val tripId: String, val routeId: String,
        val timeMs: Long, val lat: Double, val lon: Double
    )

    /** What the model has concluded. */
    sealed class Event {
        /** A vehicle is taken as ours. */
        data class Identified(val key: String, val tripId: String, val routeId: String,
                              val metres: Double) : Event()
        /**
         * Another vehicle replaces ours: ours left us while this one stayed,
         * or ours had lost its claim and this one has gone further with us.
         */
        data class Switched(val fromKey: String, val toKey: String, val toTripId: String,
                            val toRouteId: String, val metres: Double, val why: String) : Event()
        /** Ours left us while we went on at vehicle speed: we were never in it. */
        data class Withdrawn(val key: String, val metres: Double) : Event()
        /** Ours left us while we stayed on foot: we got off. */
        data class Alighted(val key: String, val tripId: String, val leftAtMs: Long) : Event()
    }

    /** One report of a vehicle as judged. */
    internal class Mark(var timeMs: Long, val metres: Double, val beside: Boolean)

    /** What is known of one vehicle. */
    class Vehicle(val key: String) {
        var tripId = ""; internal set
        var routeId = ""; internal set
        /** Metres travelled together with us. */
        var metres = 0.0; internal set
        /** Time of its latest report taken in. */
        var lastReportMs = 0L; internal set
        /** Distance from us at that report, at the same moment; null when ours was unknown. */
        var lastDistance: Double? = null; internal set
        /** Time of the latest report with it beside us, since it was last apart; 0 if none. */
        internal var togetherSinceMs = 0L
        /** Time of the latest report with it beside us, whatever came after; 0 if none. */
        internal var lastBesideMs = 0L
        /** Reports in a row with it clearly apart from us, and when that run began. */
        var apartRun = 0; internal set
        internal var firstApartMs = 0L
        /** Where we were at that first report apart — kept, as our track is not kept for long. */
        internal var firstApartPlace: Place? = null
        internal var lastSeenMs = 0L
        /** Its recent reports as judged: time, metres after it, and whether beside us. */
        internal val history = ArrayDeque<Mark>()
        /** Beside us at its latest report — however old that is; see [besideAt]. */
        val besideNow: Boolean get() = togetherSinceMs != 0L && togetherSinceMs == lastReportMs
        /** Beside us at a report recent at [now]: one gone silent beside us is no longer. */
        fun besideAt(now: Long): Boolean = besideNow && now - lastReportMs <= MAX_REPORT_AGE_MS
    }

    private val track = ArrayDeque<Fix>()
    private val vehicles = HashMap<String, Vehicle>()
    /** Reports not yet judged, as our place at their moment is not yet known; by vehicle. */
    private val waiting = HashMap<String, Report>()
    private val offsetSamples = ArrayDeque<Long>()
    /** Fix time minus arrival time of the recent fixes. */
    private val skews = ArrayDeque<Long>()
    /** Our places worked out since the track last changed, by moment. */
    private val places = HashMap<Long, Place?>()
    /** The latest reading's time on the fix clock. */
    private var readingNowMs = 0L

    /** The vehicle taken as ours, if any. */
    var ours: Vehicle? = null
        private set
    /** True once getting off has been concluded; nothing more is decided. */
    var alighted = false
        private set
    /** The last moment, on the fix clock, at which we travelled at vehicle speed; 0 if never. */
    var lastVehicleSpeedMs = 0L
        private set
    /**
     * How far the fix clock is ahead of the feed's, in ms: applied to report
     * times only when it is beyond [CLOCK_TOLERANCE_MS].
     */
    var clockOffsetMs = 0L
        private set

    fun vehicle(key: String): Vehicle? = vehicles[key]
    /** Vehicles with any metres, furthest first. */
    fun leaders(): List<Vehicle> = vehicles.values.filter { it.metres > 0 }.sortedByDescending { it.metres }
    /** Vehicles beside us at their latest report, if that is recent at the latest reading. */
    fun besideUs(): List<Vehicle> = vehicles.values.filter { it.besideAt(readingNowMs) }

    /** One position of ours. Returns what that concluded, if anything. */
    fun onFix(f: Fix): List<Event> {
        // A fix stamped out of step with when it arrived — a network fix on a
        // phone clock set wrong among satellite ones — is left out: kept, it
        // would shut out every fix until real time caught up with its stamp.
        // Judged against the recent fixes, all of them, taken in or not, so
        // that when most fixes move to another clock (the phone's set right
        // mid-ride) the judgement follows; and a fix already taken in that
        // proves to be the odd one is dropped, rather than shutting out the
        // ones after it.
        places.clear()   // the track may change below
        val skew = f.timeMs - f.receivedMs
        skews.addLast(skew)
        while (skews.size > SKEW_SAMPLES) skews.removeFirst()
        if (skews.size >= SKEW_MIN_SAMPLES) {
            val usual = skews.sorted()[skews.size / 2]
            if (abs(skew - usual) > SKEW_TOLERANCE_MS) return emptyList()
            while (track.isNotEmpty() && track.last().timeMs >= f.timeMs &&
                abs(track.last().timeMs - track.last().receivedMs - usual) > SKEW_TOLERANCE_MS) {
                track.removeLast()
            }
        }
        val last = track.lastOrNull()
        if (last != null && f.timeMs <= last.timeMs) return emptyList()
        track.addLast(f)
        while (track.isNotEmpty() && f.timeMs - track.first().timeMs > TRACK_SPAN_MS) track.removeFirst()

        // Judged a few seconds back, where fixes on both sides of each end
        // make its place: at the newest fix only those before it would vote,
        // and two jumps among four can carry the vote.
        val at = f.timeMs - SPEED_LAG_MS
        val end = positionAt(at)
        val start = positionAt(at - SPEED_WINDOW_MS)
        if (end != null && start != null &&
            distance(end.lat, end.lon, start.lat, start.lon) >= VEHICLE_SPEED_MPS * SPEED_WINDOW_MS / 1000.0) {
            lastVehicleSpeedMs = at
        }
        val events = ArrayList<Event>()
        val judged = judgeWaiting(f.timeMs)
        judgeParting(f.timeMs, events)
        if (judged && !alighted) choose(events)
        return events
    }

    /**
     * Judges the reports that were waiting for our place at their moment,
     * now that this fix may give it; one that no fix to come can place any
     * more is dropped. True if any was judged.
     */
    private fun judgeWaiting(nowFix: Long): Boolean {
        if (alighted) { waiting.clear(); return false }
        var judged = false
        for (key in waiting.keys.toList()) {
            val r = waiting.getValue(key)
            val v = vehicles[key]
            val t = r.timeMs + clockOffsetMs
            when {
                v == null -> waiting.remove(key)
                positionAt(t) != null -> { waiting.remove(key); if (take(v, r, nowFix)) judged = true }
                nowFix - t > SMOOTH_SPANS_MS.last() -> waiting.remove(key)
            }
        }
        return judged
    }

    /**
     * One reading of the feed: [receivedMs] by the phone's clock, the server's
     * time from the response header ([serverDateMs], 0 if absent), and the
     * vehicles in it.
     */
    fun onReading(receivedMs: Long, serverDateMs: Long, reports: List<Report>): List<Event> {
        if (alighted) return emptyList()
        val lastFix = track.lastOrNull() ?: return emptyList()
        val nowFix = lastFix.timeMs + (receivedMs - lastFix.receivedMs)
        readingNowMs = nowFix
        noteClock(nowFix, serverDateMs)

        // Roughly where we are, for passing over far vehicles: the last
        // accurate fix will do at the scale of a kilometre and a half.
        val here = track.lastOrNull { (it.accuracy ?: 0.0) <= ACCURATE }
        // The latest report of each vehicle in this reading.
        val latest = HashMap<String, Report>()
        for (r in reports) {
            val prev = latest[r.key]
            if (prev == null || r.timeMs > prev.timeMs) latest[r.key] = r
        }
        for (r in latest.values) {
            val v = vehicles[r.key]
            // Far vehicles are passed over — and so, in time, forgotten —
            // unless they have something to lose.
            // Unknown ones too while our own place is unknown: there is no
            // telling which are near.
            val far = here == null || distance(here.lat, here.lon, r.lat, r.lon) > IGNORE_BEYOND_M
            if (far && (v == null || (v !== ours && v.metres <= 0.0))) continue
            take(v ?: Vehicle(r.key).also { vehicles[r.key] = it }, r, nowFix)
        }
        vehicles.values.removeAll { it !== ours && nowFix - it.lastSeenMs > FORGET_MS }

        val events = ArrayList<Event>()
        judgeParting(nowFix, events)
        if (!alighted) choose(events)
        return events
    }

    /** Takes in a report of [v]; true if it was judged. */
    private fun take(v: Vehicle, r: Report, nowFix: Long): Boolean {
        v.lastSeenMs = nowFix
        v.tripId = r.tripId
        v.routeId = r.routeId
        val t = r.timeMs + clockOffsetMs
        if (t <= v.lastReportMs) return false
        if (nowFix - t > MAX_REPORT_AGE_MS) return false
        // Not yet judged while our own place then is unknown: it waits for the
        // fixes after it (judgeWaiting). Feed reports reach us 2-5 s old, and
        // with a fix only every 6 s — a phone saving power — too few of those
        // after the report have come by the reading to place us; judged only
        // at readings, such a report never was, and a vehicle whose reports
        // happened to come older earned metres alone. The newest report
        // waits: a stale copy of the feed does not put an older one back.
        val us = positionAt(t)
        val w = waiting[v.key]
        if (us == null) {
            if (w == null || r.timeMs > w.timeMs) waiting[v.key] = r
            return false
        }
        if (w != null && w.timeMs <= r.timeMs) waiting.remove(v.key)
        v.lastReportMs = t
        val d = distance(us.lat, us.lon, r.lat, r.lon)
        v.lastDistance = d
        when {
            d <= BESIDE_M -> {
                v.apartRun = 0
                v.firstApartPlace = null
                val since = v.togetherSinceMs
                if (since != 0L && t - since <= BRIDGE_MS) {
                    val then = positionAt(since)
                    if (then != null) {
                        val moved = distance(then.lat, then.lon, us.lat, us.lon)
                        if (moved >= MIN_MOVE_M) v.metres += moved
                    }
                }
                v.togetherSinceMs = t
                v.lastBesideMs = t
            }
            d > APART_M -> {
                v.togetherSinceMs = 0L
                if (v.apartRun == 0) { v.firstApartMs = t; v.firstApartPlace = us }
                v.apartRun++
                if (v.apartRun >= 2 && v !== ours) { v.metres = 0.0; v.history.clear() }
            }
            // Neither beside us nor clearly apart: bridged over, so that one
            // poor report — ours or the vehicle's — does not break the run.
            // Nor is it apart: "two in a row" means two in a row.
            else -> {
                v.apartRun = 0
                if (t - v.togetherSinceMs > BRIDGE_MS) v.togetherSinceMs = 0L
            }
        }
        v.history.addLast(Mark(t, v.metres, d <= BESIDE_M))
        while (v.history.size > HISTORY) v.history.removeFirst()
        return true
    }

    /** Ours has left us: we got off, it was not ours, or another took its place. */
    private fun judgeParting(nowFix: Long, events: MutableList<Event>) {
        val o = ours ?: return
        if (alighted || o.apartRun < 2) return
        val wentOnSince = lastVehicleSpeedMs != 0L && lastVehicleSpeedMs >= o.firstApartMs - LEAD_MS &&
            wentOn(o.firstApartPlace)
        when {
            // We went on while it left: we were never in it — unless another
            // that has been with us is still with us, which is the one.
            // Only while we go on: standing at the stop where we got off, a
            // bus that came with us and stands there too is no heir.
            wentOnSince -> {
                val heir = vehicles.values
                    .filter { it !== o && it.besideAt(nowFix) && it.metres >= IDENTIFY_M }
                    .maxByOrNull { it.metres }
                val lost = o.metres
                o.metres = 0.0
                o.history.clear()
                if (heir != null) {
                    events += Event.Switched(o.key, heir.key, heir.tripId, heir.routeId, heir.metres,
                        "ours left; this one is still with us")
                    ours = heir
                } else {
                    events += Event.Withdrawn(o.key, lost)
                    ours = null
                }
            }
            nowFix - lastVehicleSpeedMs >= ALIGHT_QUIET_MS && stayed(o.firstApartMs, o.firstApartPlace, nowFix) -> {
                events += Event.Alighted(o.key, o.tripId, o.firstApartMs)
                alighted = true
            }
            // Otherwise wait: standing, but not long enough yet — or our
            // place is not known, which is no evidence of standing.
        }
    }

    /**
     * Whether we have gone on from [then], at least [WENT_ON_M] — not a
     * moment of vehicle speed alone, which a run of wild fixes can feign.
     */
    private fun wentOn(then: Place?): Boolean {
        if (then == null) return false
        val latest = track.lastOrNull()?.timeMs ?: return false
        val now = positionAt(latest - SPEED_LAG_MS) ?: return false
        return distance(then.lat, then.lon, now.lat, now.lon) >= WENT_ON_M
    }

    /**
     * Whether we are seen to have stayed at [then] since [since]: a fix
     * lately, our place known now, and no further from it than walking
     * takes. Without fixes no vehicle speed can show, so its absence alone
     * is no evidence that we got off.
     */
    private fun stayed(since: Long, then: Place?, nowFix: Long): Boolean {
        if (then == null) return false
        val latest = track.lastOrNull() ?: return false
        if (nowFix - latest.timeMs > FRESH_FIX_MS) return false
        val at = latest.timeMs - SPEED_LAG_MS
        val now = positionAt(at) ?: return false
        val walk = STAY_M + WALK_MPS * maxOf(0L, at - since) / 1000.0
        return distance(then.lat, then.lon, now.lat, now.lon) <= walk
    }

    /** Takes a vehicle as ours, or replaces ours with one that has gone further with us. */
    private fun choose(events: MutableList<Event>) {
        val o = ours
        if (o != null && o.apartRun >= 2) return   // being judged
        // Only vehicles still in a run beside us: metres earned earlier by one
        // that has since drifted off are no claim to be ours now.
        val pool = vehicles.values.filter {
            it.apartRun < 2 && it.togetherSinceMs != 0L && it.metres >= IDENTIFY_M &&
                readingNowMs - it.lastReportMs <= MAX_REPORT_AGE_MS
        }
        if (pool.isEmpty()) return
        if (o == null) {
            // Compared at one moment, not at their last reports: two vehicles
            // beside us report at different seconds, and one may seem a whole
            // report — some 300 m — ahead of the other. The moment is the
            // oldest of their last reports, where every one's metres are
            // known, not guessed.
            // Fresh reports only: one silent for minutes would set the moment
            // before the others' remembered reports.
            val contenders = vehicles.values.filter {
                it.apartRun < 2 && it.togetherSinceMs != 0L && it.metres > 0 &&
                    readingNowMs - it.lastReportMs <= MAX_REPORT_AGE_MS
            }
            val common = contenders.minOf { it.lastReportMs }
            val fair = contenders.associateWith { metresAt(it, common) }
            val top = fair.values.max()
            val close = contenders.filter { top - fair.getValue(it) <= TIE_M }
            val pick = close.filter { it.routeId == chosenRouteId }.maxByOrNull { fair.getValue(it) }
                ?: close.maxBy { fair.getValue(it) }
            ours = pick
            events += Event.Identified(pick.key, pick.tripId, pick.routeId, pick.metres)
            return
        }
        // Ours is replaced when it parts from us (see judgeParting), not
        // because another has earned a little more meanwhile: two vehicles
        // travelling together report at different seconds, and one poor
        // report of ours must not hand its place over. Only a claim that has
        // lapsed is open — ours silent for SILENT_HOLD_MS, or neither beside
        // us nor clearly apart for longer than BRIDGE_MS.
        val now = readingNowMs
        val lapsed = now - o.lastReportMs > SILENT_HOLD_MS ||
            (o.togetherSinceMs == 0L && o.apartRun == 0 && now - o.lastBesideMs > BRIDGE_MS)
        if (!lapsed) return
        val rival = pool.filter { it !== o && it.besideAt(now) }.maxByOrNull { it.metres } ?: return
        if (rival.metres > o.metres + SWITCH_M) {
            events += Event.Switched(o.key, rival.key, rival.tripId, rival.routeId, rival.metres,
                "travelled ${(rival.metres - o.metres).toInt()} m further with us")
            ours = rival
        }
    }

    /**
     * A vehicle's metres at [t], from its recent reports: those of the last
     * report by then, and of the stretch to its next report the share we had
     * travelled by [t], when that stretch counted.
     */
    private fun metresAt(v: Vehicle, t: Long): Double {
        val h = v.history
        val k = h.indexOfLast { it.timeMs <= t }
        if (k < 0) return 0.0
        val before = h[k]
        val next = h.getOrNull(k + 1) ?: return before.metres
        val gained = next.metres - before.metres
        if (gained <= 0.0) return before.metres
        val a = positionAt(before.timeMs); val b = positionAt(t); val c = positionAt(next.timeMs)
        val share = if (a != null && b != null && c != null) {
            val whole = distance(a.lat, a.lon, c.lat, c.lon)
            if (whole > 0) distance(a.lat, a.lon, b.lat, b.lon) / whole else 0.0
        } else (t - before.timeMs).toDouble() / (next.timeMs - before.timeMs)
        return before.metres + gained * share.coerceIn(0.0, 1.0)
    }

    /**
     * The fix clock against the server's, from the Date header (whole seconds,
     * hence the half second added). The fix clock is normally satellite time
     * and agrees with the feed to well under a second (7 Oct 2026); this is
     * for a fix stamped by a phone clock set wrong. The smallest of the
     * recent samples is taken — the time a response takes to arrive only
     * ever adds to a sample — and applied only beyond the tolerance, so that
     * a slow download is never mistaken for a clock out of step.
     */
    private fun noteClock(nowFix: Long, serverDateMs: Long) {
        if (serverDateMs <= 0L) return
        offsetSamples.addLast(nowFix - (serverDateMs + 500L))
        while (offsetSamples.size > CLOCK_SAMPLES) offsetSamples.removeFirst()
        val smallest = offsetSamples.min()
        val offset = if (abs(smallest) > CLOCK_TOLERANCE_MS) smallest else 0L
        if (offset == clockOffsetMs) return
        // Times already taken in move with it, or a report would be taken
        // twice, or a new one passed over as old.
        val shift = offset - clockOffsetMs
        clockOffsetMs = offset
        for (v in vehicles.values) {
            if (v.lastReportMs != 0L) v.lastReportMs += shift
            if (v.togetherSinceMs != 0L) v.togetherSinceMs += shift
            if (v.lastBesideMs != 0L) v.lastBesideMs += shift
            if (v.firstApartMs != 0L) v.firstApartMs += shift
            for (m in v.history) m.timeMs += shift
        }
    }

    /** Where we were at [t] on the fix clock. */
    internal data class Place(val lat: Double, val lon: Double)

    /**
     * Our place at [t]: a straight line in time fitted through the accurate
     * fixes around it, read at [t] — so that movement between fixes is
     * allowed for — with any fix far off the line left out, so that a fix
     * that jumped is outvoted (see fitAt). The fixes within
     * [SMOOTH_HALF_MS] of [t] are used, or within a wider span when they
     * come sparsely (see SMOOTH_SPANS_MS); three at least, and [t] not far
     * beyond the first or last of them. Null otherwise: near a gap a lone
     * fix may be the one that jumped, and the model then holds what it has
     * rather than decide on it.
     *
     * A median of the fixes, tried first, lagged behind on sparse fixes: one
     * every 5 s at 40 km/h put us tens of metres back, nearer the bus behind
     * ours than ours, and a test took it for ours.
     */
    internal fun positionAt(t: Long): Place? = places.getOrPut(t) { placeAt(t) }

    private fun placeAt(t: Long): Place? {
        for (half in SMOOTH_SPANS_MS) {
            val near = track.filter {
                abs(it.timeMs - t) <= half && (it.accuracy ?: 0.0) <= ACCURATE
            }
            if (near.size < 3) continue
            if (t < near.first().timeMs - EXTRAPOLATE_MS || t > near.last().timeMs + EXTRAPOLATE_MS) continue
            return fitAt(t, near)
        }
        return null
    }

    /**
     * The line through [fixes] (three or more), read at [t]; see positionAt.
     * First a line that a few wild fixes cannot tilt — each axis's slope the
     * median of the slopes between every two fixes (Theil-Sen), which holds
     * with nearly a third of them wrong — then the fixes far off it left out
     * and the line fitted through the rest by least squares.
     */
    private fun fitAt(t: Long, fixes: List<Fix>): Place? {
        val lat0 = fixes[0].lat; val lon0 = fixes[0].lon
        val kx = KY * cos(Math.toRadians(lat0))
        val tau = fixes.map { (it.timeMs - t) / 1000.0 }
        val xs = fixes.map { (it.lon - lon0) * kx }
        val ys = fixes.map { (it.lat - lat0) * KY }
        fun robust(v: List<Double>): Pair<Double, Double> {
            val slopes = ArrayList<Double>()
            for (a in v.indices) for (b in a + 1 until v.size) {
                val dt = tau[b] - tau[a]
                if (dt != 0.0) slopes += (v[b] - v[a]) / dt
            }
            val slope = if (slopes.isEmpty()) 0.0 else median(slopes)
            return median(v.indices.map { v[it] - slope * tau[it] }) to slope
        }
        val (ax, bx) = robust(xs)
        val (ay, by) = robust(ys)
        val res = fixes.indices.map { hypot(xs[it] - (ax + bx * tau[it]), ys[it] - (ay + by * tau[it])) }
        val limit = maxOf(OUTLIER_MIN_M, OUTLIER_FACTOR * median(res))
        val keep = fixes.indices.filter { res[it] <= limit }
        if (keep.size < 3) return null
        // Least squares through the fixes kept, read at τ = 0, i.e. at t.
        val n = keep.size.toDouble()
        val mt = keep.sumOf { tau[it] } / n
        val mx = keep.sumOf { xs[it] } / n
        val my = keep.sumOf { ys[it] } / n
        val stt = keep.sumOf { (tau[it] - mt) * (tau[it] - mt) }
        val sx = if (stt > 0) keep.sumOf { (tau[it] - mt) * (xs[it] - mx) } / stt else 0.0
        val sy = if (stt > 0) keep.sumOf { (tau[it] - mt) * (ys[it] - my) } / stt else 0.0
        val x0 = mx - sx * mt
        val y0 = my - sy * mt
        return Place(lat0 + y0 / KY, lon0 + x0 / kx)
    }

    companion object {
        /** How much of our track is kept: enough for the oldest report taken in, and the run before it. */
        const val TRACK_SPAN_MS = 5 * 60_000L
        /** Fixes this far either side of a moment make our place at it… */
        const val SMOOTH_HALF_MS = 3_000L
        /** …or these, when fewer than three are that close (a fix every few seconds). */
        val SMOOTH_SPANS_MS = longArrayOf(SMOOTH_HALF_MS, 6_000L, 10_000L)
        /** How far beyond the first or last fix used a place may be read off the line. */
        const val EXTRAPOLATE_MS = 3_000L
        /**
         * A fix further off the fitted line than this many times the median
         * miss, and at least [OUTLIER_MIN_M], is left out. Forty metres: a
         * fix taken in a corner lies up to some 20 m off a line through
         * sparse fixes on either side of it, and the jumps seen in the logs
         * were of a hundred metres and more.
         */
        const val OUTLIER_FACTOR = 3.0
        const val OUTLIER_MIN_M = 40.0
        /** How far back vehicle speed is judged, so that fixes lie on both sides of each end. */
        const val SPEED_LAG_MS = 10_000L
        /** Fixes vaguer than this count for nothing. */
        const val ACCURATE = 30.0

        /**
         * Within this of us at the same moment, a vehicle is beside us. Our
         * bus measured 2-13 m on 7 Oct 2026 (22 m once, at a stop).
         *
         * Not widened for a poor fix: tried, it earned our own bus nothing
         * (the fitted place is better than the fix's stated accuracy) and let
         * a bus on a parallel street 50 m off earn 1578 m on fixes scattered
         * ±20 m, against 8 m without.
         */
        const val BESIDE_M = 30.0
        /** Beyond this, a vehicle is clearly apart from us. */
        const val APART_M = 100.0
        /** Less than this moved between two reports is GPS scatter, not travel. */
        const val MIN_MOVE_M = 20.0
        /**
         * The longest stretch between two reports with the vehicle beside us
         * over which the metres still count, poor reports between included.
         */
        const val BRIDGE_MS = 120_000L
        /** Reports older than this, when the reading comes, are passed over. */
        const val MAX_REPORT_AGE_MS = 120_000L
        /** Vehicles we have no metres with are passed over beyond this. */
        const val IGNORE_BEYOND_M = 1500.0
        /** Vehicles not seen for this long are forgotten. */
        const val FORGET_MS = 10 * 60_000L
        /** Reports of a vehicle remembered, for comparing vehicles at one moment. */
        const val HISTORY = 12
        /** A fix whose stamp differs this much from the others' against arrival is left out… */
        const val SKEW_TOLERANCE_MS = 5_000L
        /** …judged against the median of this many recent ones, once there are at least [SKEW_MIN_SAMPLES]. */
        const val SKEW_SAMPLES = 15
        const val SKEW_MIN_SAMPLES = 5

        /**
         * Metres together before a vehicle is taken as ours: two readings
         * after departure at ordinary speed (7 Oct 2026: 191 m at the second).
         */
        const val IDENTIFY_M = 150.0
        /** Within this of the furthest, the chosen line wins the first choice. */
        const val TIE_M = 50.0
        /** How much further another must have gone with us to replace ours. */
        const val SWITCH_M = 100.0
        /** Ours silent this long no longer holds its place by its last report beside us. */
        const val SILENT_HOLD_MS = 5 * 60_000L

        /**
         * Vehicle speed: travel over [SPEED_WINDOW_MS], on smoothed accurate
         * positions. Twenty seconds, so that the scatter of a poor fix (±10 m)
         * all but never reaches it standing — over ten, it did in a test one
         * time in two over two minutes — while walking covers half of it.
         */
        const val VEHICLE_SPEED_MPS = 10.0 / 3.6
        const val SPEED_WINDOW_MS = 20_000L
        /** Vehicle speed this shortly before ours was first seen apart still counts as after. */
        const val LEAD_MS = 5_000L
        /** How far we must have gone on, after ours left, for it not to have been ours. */
        const val WENT_ON_M = 100.0
        /** Got off: no further from where we were as ours left than this… */
        const val STAY_M = 50.0
        /** …plus walking at this pace since. */
        const val WALK_MPS = 2.0
        /** And a fix no older than this, so that the standing is seen, not supposed. */
        const val FRESH_FIX_MS = 15_000L
        /**
         * Ours gone and no vehicle speed for this long: we got off. The same
         * two minutes as the rule it replaces.
         */
        const val ALIGHT_QUIET_MS = 120_000L

        /** The clock difference is taken from this many readings… */
        const val CLOCK_SAMPLES = 9
        /** …and applied only beyond this. */
        const val CLOCK_TOLERANCE_MS = 3_000L

        private const val KY = 6_371_000.0 * Math.PI / 180.0

        /** Metres between two points, on the same sphere as LocationHelper. */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
            hypot((lat1 - lat2) * KY, (lon1 - lon2) * KY * cos(Math.toRadians((lat1 + lat2) / 2)))

        private fun median(xs: List<Double>): Double {
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
        }
    }
}
