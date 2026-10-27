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
 * fixes that follow it; one that no fix can place ends every run but ours,
 * since which reports our fixes can place is then chance. With poor
 * positions the model holds what it has rather than deciding anew.
 *
 * Fair between vehicles abreast (9 Oct 2026): each one's run begins at its
 * first report beside us, and two that run together from a moment mid-ride
 * — tracking begun aboard, fixes thickening again — begin up to a report
 * apart. That head start is no evidence: the first choice compares all of
 * them over one stretch, from the latest beginning among the runs begun
 * within a report of the earliest. A later beginning is evidence only when
 * the vehicle's report before it was clearly apart from us, as we moved at
 * vehicle speed: then it was not the vehicle we were in (20 Oct 2026, b) —
 * unless it is of the chosen line, which has the benefit of the doubt.
 * Waiting at a stop, the bus we board is far off too until it comes. And a
 * tie the chosen line is not in is not guessed: the choice waits until one
 * has gone beyond it (20 Oct 2026).
 *
 * Stage 3 (decided 24 Oct 2026): seated at the back of a long vehicle its
 * report is ahead of us beyond BESIDE_M, so a report in the zone ahead is
 * beside us too while no other vehicle is near us (take); at a stop, the one
 * vehicle near us that was due there is taken at once (onBoarding); off the
 * route of ours, as the app tells, ours not with us at two readings and we
 * moving on, it has left us (onOffRoute); and fixes gone vague after ours
 * left us on foot do not hold up getting off (stayed, apartFromFoot).
 *
 * Pure Kotlin, no Android types, so it can be tested and replayed on its own
 * (tools/test-harness/run_model_tests.sh). The app follows its conclusions
 * (stage 3), fed through [RideShadow].
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
        /** A vehicle is taken as ours: by metres, or at once by the boarding stop (onBoarding). */
        data class Identified(val key: String, val tripId: String, val routeId: String,
                              val metres: Double, val byBoardingStop: Boolean = false) : Event()
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
        /** Time of the first report of its present run beside us. */
        internal var runFromMs = 0L
        /**
         * Its present run began just after it was seen clearly apart from us
         * while we moved at vehicle speed: that later beginning is real.
         */
        internal var seenApartRiding = false
        /** Its latest report was clearly apart from us, as judged on the present clock. */
        internal var lastApart = false
        /** Time of its latest report that no fix could place. */
        internal var unplacedMs = 0L
        /** Its recent reports as judged: time, metres after it, and whether beside us. */
        internal val history = ArrayDeque<Mark>()
        /** Beside us at its latest report — however old that is; see [besideAt]. */
        val besideNow: Boolean get() = togetherSinceMs != 0L && togetherSinceMs == lastReportMs
        /** Beside us at a report recent at [now]: one gone silent beside us is no longer. */
        fun besideAt(now: Long): Boolean = besideNow && now - lastReportMs <= MAX_REPORT_AGE_MS
    }

    /** A report waiting for our place at its moment, and whether its reading had another vehicle near us. */
    private class Pending(val report: Report, val crowded: Boolean)

    private val track = ArrayDeque<Fix>()
    private val vehicles = HashMap<String, Vehicle>()
    /** Reports not yet judged, as our place at their moment is not yet known; by vehicle. */
    private val waiting = HashMap<String, Pending>()
    private val offsetSamples = ArrayDeque<Long>()
    /** Fix time minus arrival time of the recent fixes. */
    private val skews = ArrayDeque<Long>()
    /** Our places worked out since the track last changed, by moment. */
    private val places = HashMap<Long, Place?>()
    /** The latest reading's time on the fix clock. */
    private var readingNowMs = 0L
    /** The vehicles of the latest reading within LONE_M of us at their moments (see mayBeNear). */
    private var nearAtReading: Set<String> = emptySet()

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

    /**
     * Whether ours was beside us at a report at most [withinMs] before
     * [nowMs], a time on the phone's clock (as the fixes' arrival times are).
     * False with no vehicle ours. Read only: for the app's "the vehicle is
     * still with us", which holds its timers back in a jam.
     */
    fun ourBesideWithin(nowMs: Long, withinMs: Long): Boolean {
        val o = ours ?: return false
        if (alighted) return false
        val lastFix = track.lastOrNull() ?: return false
        return lastFix.timeMs + (nowMs - lastFix.receivedMs) - o.lastBesideMs <= withinMs
    }

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
     *
     * A dropped report ends the run of every vehicle but ours. Our fixes
     * about it were too few to place its moment, and then which reports are
     * placed is chance: a vehicle whose reports fall in step with our fixes
     * earns metres while the others earn none. In simulations with a fix
     * every 10 s, and after such a spell once the fixes had thickened, the
     * wrong vehicle was taken about as often as ours (9 Oct 2026). With a
     * fix every second a report is dropped only in a gap of some 15 s or
     * more; then identification may come later, not wrong.
     */
    private fun judgeWaiting(nowFix: Long): Boolean {
        if (alighted) { waiting.clear(); return false }
        var judged = false
        for (key in waiting.keys.toList()) {
            val p = waiting.getValue(key)
            val r = p.report
            val v = vehicles[key]
            val t = r.timeMs + clockOffsetMs
            when {
                v == null -> waiting.remove(key)
                positionAt(t) != null -> { waiting.remove(key); if (take(v, r, nowFix, p.crowded)) judged = true }
                nowFix - t > SMOOTH_SPANS_MS.last() -> {
                    waiting.remove(key)
                    if (v === ours) apartFromFoot(v, r, t)
                    v.unplacedMs = t
                    for (other in vehicles.values) if (other !== ours) other.togetherSinceMs = 0L
                }
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
        // Which vehicles of this reading were within LONE_M of us: the zone
        // ahead holds only for a vehicle alone near us (see take), and so
        // does the early rule by the boarding stop (boardingVerdict).
        val near = latest.values.filter { mayBeNear(it, nowFix) }.map { it.key }.toSet()
        nearAtReading = near
        for (r in latest.values) {
            val v = vehicles[r.key]
            // Far vehicles are passed over — and so, in time, forgotten —
            // unless they have something to lose.
            // Unknown ones too while our own place is unknown: there is no
            // telling which are near.
            val far = here == null || distance(here.lat, here.lon, r.lat, r.lon) > IGNORE_BEYOND_M
            if (far && (v == null || (v !== ours && v.metres <= 0.0))) continue
            take(v ?: Vehicle(r.key).also { vehicles[r.key] = it }, r, nowFix, (near - r.key).isNotEmpty())
        }
        vehicles.values.removeAll { it !== ours && nowFix - it.lastSeenMs > FORGET_MS }

        val events = ArrayList<Event>()
        judgeParting(nowFix, events)
        if (!alighted) choose(events)
        return events
    }

    /**
     * What the early rule by the boarding stop finds (boardingVerdict): the
     * vehicle it takes, or null and [why] not; and the vehicles beside or
     * near us at the reading, nearest first, each with whether it was due.
     */
    class Boarding(val pick: Vehicle?, val why: String, val near: List<Pair<Vehicle, Boolean>>)

    /** The early rule's verdict once it has been judged (onBoarding), or closed by a first choice by metres; null before. */
    var boarding: Boarding? = null
        private set

    /**
     * The early rule by the boarding stop (HANDOVER "Етап 3", point 1;
     * decided 24 Oct 2026), at the latest reading: the vehicle beside us is
     * taken at once, without metres, if it is the only vehicle within
     * LONE_M of us, we moved at its report, and it was due at the stop we
     * boarded at around our departure ([due], by trip id). The feed's
     * readings come every 30 s and metres take two of them; at a stop, the
     * bus we board is the one due there that leaves with us.
     *
     * Only one vehicle near us at all, not one due among those beside us:
     * at ХОТЕЛ ПЛИСКА (14 Oct 2026) 183 was 16 m off and 76, ours, 20 m — had
     * only 183 been in the arrivals, it would have been taken. Within LONE_M,
     * not BESIDE_M: seated at the back of 181 (24 Oct 2026, our fixes moved
     * 20 m back), 185 of the chosen line, leaving the stop just behind us,
     * was 21 m off and our 181 32 m. And only while we move: standing, a
     * vehicle due there may stand beside us that we are not in.
     *
     * Null while it cannot be judged: no vehicle beside us at a report
     * taken while we moved. Pure: nothing is changed (see onBoarding).
     */
    fun boardingVerdict(due: (String) -> Boolean): Boarding? {
        val beside = besideUs()
        if (beside.none { movedAt(it.lastReportMs) }) return null
        val near = (beside.map { it.key } + nearAtReading).distinct().mapNotNull { vehicles[it] }
            .sortedBy { it.lastDistance ?: 0.0 }.map { it to due(it.tripId) }
        val v = beside.singleOrNull()
        return when {
            v == null || near.size > 1 -> Boarding(null, "${near.size} vehicles near us", near)
            !due(v.tripId) -> Boarding(null, "the only vehicle near us was not due there", near)
            else -> Boarding(v, "the only vehicle near us, due there", near)
        }
    }

    /**
     * The early rule by the boarding stop (boardingVerdict), at the latest
     * reading after departure, while no vehicle is ours: the vehicle it
     * finds is taken as ours. Judged once — taken or not — at the first
     * reading it can be judged, and never once a vehicle has been taken by
     * metres: ours withdrawn mid-ride, one due at the stop then beside us is
     * no evidence (review of 24 Oct 2026). Taken wrongly, it is put right as
     * any other: it leaves us while we go on.
     */
    fun onBoarding(due: (String) -> Boolean): List<Event> {
        if (boarding != null || ours != null || alighted) return emptyList()
        val b = boardingVerdict(due) ?: return emptyList()
        boarding = b
        val v = b.pick ?: return emptyList()
        ours = v
        return listOf(Event.Identified(v.key, v.tripId, v.routeId, v.metres, byBoardingStop = true))
    }

    /**
     * Takes in a report of [v]; true if it was judged. [crowded]: another
     * vehicle of the same reading was within LONE_M of us.
     */
    private fun take(v: Vehicle, r: Report, nowFix: Long, crowded: Boolean): Boolean {
        v.lastSeenMs = nowFix
        v.tripId = r.tripId
        v.routeId = r.routeId
        val t = r.timeMs + clockOffsetMs
        if (t <= v.lastReportMs || t <= v.unplacedMs) return false
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
            // From before our track begins: never placed, and it says nothing
            // of our fixes. A vehicle gone silent repeats such a report at
            // every reading (7 Oct 2026, evening 76: two of them did).
            val first = track.firstOrNull { (it.accuracy ?: 0.0) <= ACCURATE } ?: return false
            if (t < first.timeMs - EXTRAPOLATE_MS) return false
            if (w == null || r.timeMs > w.report.timeMs) waiting[v.key] = Pending(r, crowded)
            return false
        }
        if (w != null && w.report.timeMs <= r.timeMs) waiting.remove(v.key)
        val prevMs = v.lastReportMs
        val wasApart = v.lastApart
        v.lastReportMs = t
        val d = distance(us.lat, us.lon, r.lat, r.lon)
        v.lastDistance = d
        v.lastApart = d > APART_M
        // Beside us, or in the zone ahead with no other vehicle near us: in a
        // long vehicle, seated at the back, its report — from the front — is
        // ahead of us by more than BESIDE_M (two coupled trams of 22, 37-40 m:
        // 19 Oct 2026). Alone near us it is the one we are in or none; with
        // another near us, a bus 32-50 m ahead of ours could be taken.
        val beside = d <= BESIDE_M || (!crowded && inZoneAhead(t, us, r))
        when {
            beside -> {
                v.apartRun = 0
                v.firstApartPlace = null
                val since = v.togetherSinceMs
                if (since != 0L && t - since <= BRIDGE_MS) {
                    val then = positionAt(since)
                    if (then != null) {
                        val moved = distance(then.lat, then.lon, us.lat, us.lon)
                        if (moved >= MIN_MOVE_M) v.metres += moved
                    }
                } else {
                    v.runFromMs = t
                    // Not for the chosen line: one wild report of the vehicle
                    // we are in, just after tracking begins, would count
                    // against it (304 taken in 48 of 60 runs, 25 before:
                    // review of 20 Oct 2026); a tie it is in goes to it anyway.
                    v.seenApartRiding = r.routeId != chosenRouteId && wasApart && movingAt(prevMs)
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
        v.history.addLast(Mark(t, v.metres, beside))
        while (v.history.size > HISTORY) v.history.removeFirst()
        return true
    }

    /**
     * Whether [r] lies in the zone ahead of us at its moment [t], where we
     * were at [us]: up to ZONE_AHEAD_M ahead along the way we were going and
     * up to ZONE_SIDE_M to the side. Only while we move (see wayAt).
     */
    private fun inZoneAhead(t: Long, us: Place, r: Report): Boolean {
        val (hx, hy) = wayAt(t, us) ?: return false
        val h = hypot(hx, hy)
        val kx = KY * cos(Math.toRadians(us.lat))
        val vx = (r.lon - us.lon) * kx
        val vy = (r.lat - us.lat) * KY
        val along = (vx * hx + vy * hy) / h
        val across = abs(vx * hy - vy * hx) / h
        return along > 0.0 && along <= ZONE_AHEAD_M && across <= ZONE_SIDE_M
    }

    /**
     * The way we were going at [t], where we were at [us]: our travel over
     * the ZONE_HEADING_MS before, in metres east and north — or null when it
     * was less than ZONE_MIN_MPS makes, or our place then is not known.
     * Standing has no way.
     */
    private fun wayAt(t: Long, us: Place): Pair<Double, Double>? {
        val before = positionAt(t - ZONE_HEADING_MS) ?: return null
        val hx = (us.lon - before.lon) * KY * cos(Math.toRadians(us.lat))
        val hy = (us.lat - before.lat) * KY
        return if (hypot(hx, hy) >= ZONE_MIN_MPS * ZONE_HEADING_MS / 1000.0) hx to hy else null
    }

    /** Whether we were moving at [t] (see wayAt). */
    private fun movedAt(t: Long): Boolean = positionAt(t)?.let { wayAt(t, it) } != null

    /**
     * Whether [r] puts its vehicle within LONE_M of us at its moment. While
     * our place then is not known, its distance from the accurate fix
     * nearest in time is taken, allowing for travel at FAST_MPS between the
     * two: counted near unless clearly far, so that the zone ahead is not
     * opened by a vehicle we could not place. Reports too old to be taken in
     * are not counted.
     */
    private fun mayBeNear(r: Report, nowFix: Long): Boolean {
        val t = r.timeMs + clockOffsetMs
        if (nowFix - t > MAX_REPORT_AGE_MS) return false
        positionAt(t)?.let { return distance(it.lat, it.lon, r.lat, r.lon) <= LONE_M }
        val f = track.filter { (it.accuracy ?: 0.0) <= ACCURATE }.minByOrNull { abs(it.timeMs - t) } ?: return true
        return distance(f.lat, f.lon, r.lat, r.lon) <= LONE_M + FAST_MPS * abs(f.timeMs - t) / 1000.0
    }

    /** Ours has left us: we got off, it was not ours, or another took its place. */
    private fun judgeParting(nowFix: Long, events: MutableList<Event>) {
        val o = ours ?: return
        if (alighted) return
        if (o.apartRun < 2) {
            // Off its route, as the app tells (onOffRoute), not beside us for
            // OFF_ROUTE_QUIET_MS, and we move on at vehicle speed: it has left
            // us as if apart at two reports. Ours gone silent — taken wrongly,
            // or on a detour — can never be seen apart, and was held for good
            // while nothing else had gone further with us. Standing, it is
            // left to the rules above: the app stays off the route while we
            // walk away from a stop on a detour, and that is getting off.
            if (offRouteOf == o.key && nowFix - o.lastBesideMs >= OFF_ROUTE_QUIET_MS &&
                nowFix - lastVehicleSpeedMs <= SPEED_LAG_MS + SPEED_WINDOW_MS)
                leave(o, nowFix, events, "ours off its route and not with us; this one is")
            return
        }
        val wentOnSince = lastVehicleSpeedMs != 0L && lastVehicleSpeedMs >= o.firstApartMs - LEAD_MS &&
            wentOn(o.firstApartMs, o.firstApartPlace)
        when {
            // We went on while it left: we were never in it — unless another
            // that has been with us is still with us, which is the one.
            wentOnSince -> leave(o, nowFix, events, "ours left; this one is still with us")
            nowFix - lastVehicleSpeedMs >= ALIGHT_QUIET_MS && stayed(o.firstApartMs, o.firstApartPlace, nowFix) -> {
                events += Event.Alighted(o.key, o.tripId, o.firstApartMs)
                alighted = true
            }
            // Otherwise wait: standing, but not long enough yet — or our
            // place is not known, which is no evidence of standing.
        }
    }

    /**
     * Ours [o] has left us while we went on: another that has been with us
     * and is beside us now is the one ([why] it is said to be); else we were
     * never in it. Only while we go on: standing at the stop where we got
     * off, a bus that came with us and stands there too is no heir.
     */
    private fun leave(o: Vehicle, nowFix: Long, events: MutableList<Event>, why: String) {
        val heir = vehicles.values
            .filter { it !== o && it.besideAt(nowFix) && it.metres >= IDENTIFY_M }
            .maxByOrNull { it.metres }
        val lost = o.metres
        o.metres = 0.0
        o.history.clear()
        // Its run ends too: beside us again, it starts from nothing.
        o.togetherSinceMs = 0L
        if (heir != null) {
            events += Event.Switched(o.key, heir.key, heir.tripId, heir.routeId, heir.metres, why)
            ours = heir
        } else {
            events += Event.Withdrawn(o.key, lost)
            ours = null
        }
    }

    /** The vehicle whose route the app says we are off (onOffRoute); null while on it. */
    private var offRouteOf: String? = null

    /**
     * From the app: we are off the route of vehicle [key] — by the road, over
     * 50 m from it for 150 m at vehicle speed (OffRoadWatch), or without the
     * road by the stops — or null once we are back on it. The app only
     * tells; whether ours has left us is judged here, at every fix and
     * reading (HANDOVER "Етап 3", point 2; decided 24 Oct 2026).
     */
    fun onOffRoute(key: String?) {
        offRouteOf = key
    }

    /**
     * Whether we have gone on from [then], where we were at [since] — at
     * least [WENT_ON_M], and further than walking takes (walkFrom), so that
     * it and having stayed (stayed) are never both true: not a moment of
     * vehicle speed alone, which a run of wild fixes can feign.
     *
     * At ЦЕНТРАЛНИ ХАЛИ (26 Oct 2026, d), walking away from tram 22, our
     * fixes drifted 35 m aside and came back at once, 50 m in a moment —
     * vehicle speed — and walking on made the 100 m: withdrawn, where we
     * had got off.
     */
    private fun wentOn(since: Long, then: Place?): Boolean {
        if (then == null) return false
        val latest = track.lastOrNull()?.timeMs ?: return false
        val back = latest - SPEED_LAG_MS
        val now = positionAt(back) ?: return false
        val far = distance(then.lat, then.lon, now.lat, now.lon)
        return far >= WENT_ON_M && far > walkFrom(since, back)
    }

    /**
     * Whether we moved at vehicle speed over [MOVING_WINDOW_MS] about [t];
     * false while our place then is not known. Waiting at a stop it is false
     * — barring widely scattered fixes — at the reports of the bus we board
     * while that is clearly apart from us: it comes and stands at us before
     * we move.
     */
    private fun movingAt(t: Long): Boolean {
        val a = positionAt(t - MOVING_WINDOW_MS / 2) ?: return false
        val b = positionAt(t + MOVING_WINDOW_MS / 2) ?: return false
        return distance(a.lat, a.lon, b.lat, b.lon) >= VEHICLE_SPEED_MPS * MOVING_WINDOW_MS / 1000.0
    }

    /**
     * Whether we are seen to have stayed at [then] since [since]: a fix
     * lately, our place known now, and no further from it than walking
     * takes. Without fixes no vehicle speed can show, so its absence alone
     * is no evidence that we got off.
     *
     * Fixes too poor to place us now do not hold it up: where they last
     * placed us will do (lastPlaced) — about [since] or after, as they placed
     * ours's first report apart. Down into the metro underpass at ПЛ. ОРЛОВ
     * МОСТ (20 Oct 2026, c) our fixes went 57-77 m vague 14 s before the two
     * minutes were up, and the app ended the journey with nothing concluded.
     */
    private fun stayed(since: Long, then: Place?, nowFix: Long): Boolean {
        if (then == null) return false
        val latest = track.lastOrNull() ?: return false
        if (nowFix - latest.timeMs > FRESH_FIX_MS) return false
        val back = latest.timeMs - SPEED_LAG_MS
        positionAt(back)?.let { now ->
            return distance(then.lat, then.lon, now.lat, now.lon) <= walkFrom(since, back)
        }
        val (at, now) = lastPlaced() ?: return false
        // And the latest fix, however vague, no further off than walking and
        // its own accuracy allow: vague fixes that follow us riding on a
        // kilometre show we did not stay (review of 24 Oct 2026).
        return distance(then.lat, then.lon, now.lat, now.lon) <= walkFrom(since, at) &&
            distance(then.lat, then.lon, latest.lat, latest.lon) <= walkFrom(since, latest.timeMs) + (latest.accuracy ?: 0.0)
    }

    /** How far from where we were at [since] we may be at [at] on foot. */
    private fun walkFrom(since: Long, at: Long): Double = STAY_M + WALK_MPS * maxOf(0L, at - since) / 1000.0

    /**
     * When our accurate fixes last placed us, and where — if fixes, however
     * poor, have kept coming since, none more than FRESH_FIX_MS after the
     * one before. Null with no such place, or a gap in our fixes since: a
     * gap is no evidence of where we have been.
     */
    private fun lastPlaced(): Pair<Long, Place>? {
        var next: Fix? = null
        for (f in track.reversed()) {
            if (next != null && next.timeMs - f.timeMs > FRESH_FIX_MS) return null
            next = f
            if ((f.accuracy ?: 0.0) > ACCURATE) continue
            positionAt(f.timeMs)?.let { return f.timeMs to it }
        }
        return null
    }

    /**
     * A report of ours [o] at [t] that no fix could place, judged against
     * where our accurate fixes last placed us (lastPlaced) — once they have
     * seen it leave us on foot: clearly apart at a report they placed, after
     * one not beside us, with no vehicle speed since. Clearly apart if
     * further from there than APART_M beyond where walking could have taken
     * us since; then it counts as its second report apart, and getting off
     * is judged as ever (stayed). True if it did.
     *
     * After ПЛ. ОРЛОВ МОСТ (14 Oct 2026, d) 76 was 66 m and then 223 m off
     * as we stood, and from then on our fixes were 51-89 m vague: its
     * reports waited for fixes to place them, and the app ended the journey
     * with getting off not concluded. Not beside us at the report before:
     * one wild report of ours as we stand in it at a stop, and a tunnel
     * just after, would make it seem to leave us.
     */
    private fun apartFromFoot(o: Vehicle, r: Report, t: Long): Boolean {
        if (o.apartRun != 1 || t <= o.lastReportMs) return false
        val h = o.history
        if (h.size < 2 || h[h.size - 2].beside) return false
        if (lastVehicleSpeedMs >= o.firstApartMs - LEAD_MS) return false
        val (tk, pk) = lastPlaced() ?: return false
        val reach = STAY_M + WALK_MPS * abs(t - tk) / 1000.0
        if (distance(pk.lat, pk.lon, r.lat, r.lon) <= APART_M + reach) return false
        o.apartRun++
        o.lastReportMs = t
        o.lastApart = true
        o.togetherSinceMs = 0L
        o.history.addLast(Mark(t, o.metres, false))
        while (o.history.size > HISTORY) o.history.removeFirst()
        return true
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
                it.apartRun < 2 && it.togetherSinceMs != 0L &&
                    readingNowMs - it.lastReportMs <= MAX_REPORT_AGE_MS
            }
            val common = contenders.minOf { it.lastReportMs }
            // Compared over one stretch: from the latest beginning among the
            // runs begun within a report of the earliest, to the oldest last
            // report. Which of vehicles abreast reported first after a moment
            // mid-ride is no evidence (see the class comment); a run begun
            // later — a vehicle just come beside us — is compared over the
            // same stretch. (Taking the latest beginning of all runs instead,
            // one vehicle beside us once switched the comparison off for the
            // rest: review of 9 Oct 2026.) A run begun just after its vehicle
            // was seen clearly apart from us as we rode does not set it: 185,
            // 174 m ahead as we went at 55 km/h and beside us at one report as
            // we overtook it, held the choice a reading (20 Oct 2026, b).
            // Seen apart at the stop proves nothing — the bus we board is far
            // off too until it comes (a rule without "as we rode" took 304 at
            // an ordinary boarding: reverted 10 Oct, and again 20 Oct 2026).
            val first = contenders.minOf { it.runFromMs }
            val late = contenders.filter { !it.seenApartRiding && it.runFromMs - first <= HEAD_START_MS }
                .maxOfOrNull { it.runFromMs } ?: first
            val fair = contenders.associateWith { metresAt(it, common) - metresAt(it, late) }
            val top = fair.values.max()
            // Until the leader has gone beyond the tie in the stretch, the
            // choice waits for the next reports: one with nothing in it would
            // win on the tie — the stretch may be a few seconds, or none yet,
            // while a vehicle of those begun first has not reported since the
            // latest beginning. (Waiting until the leader had twice the tie
            // let 213's one poor report tip it to 304.)
            if (top <= TIE_M) return
            // A tie goes to the chosen line if it is in it. One it is not in
            // is not guessed: the choice waits until one has gone beyond the
            // tie. (20 Oct 2026: 76 and 185 level, 111 chosen — the few
            // metres that tipped it to 185 in 6 of 30 starts mid-way were
            // chance.)
            val close = contenders.filter { top - fair.getValue(it) <= TIE_M }
            val pick = close.filter { it.routeId == chosenRouteId }.maxByOrNull { fair.getValue(it) }
                ?: close.singleOrNull() ?: return
            ours = pick
            // The early rule by the boarding stop is for the first choice only.
            if (boarding == null) boarding = Boarding(null, "taken by metres first", emptyList())
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
     * report by then, and of the metres the next report added the share we
     * had travelled by [t].
     *
     * Those metres were travelled from the report beside us before it, not
     * from the last report by [t] when that one was bridged over — neither
     * beside us nor clearly apart, it earns nothing itself. Shared out from
     * the bridged report, all of them fell after it, and at a moment before
     * it the vehicle seemed to have none of them: the one that began first
     * kept its head start in the comparison from the later beginning (review
     * of 9 Oct 2026: 304 taken in 63 of 900 simulated runs, now 0).
     */
    private fun metresAt(v: Vehicle, t: Long): Double {
        val h = v.history
        val k = h.indexOfLast { it.timeMs <= t }
        if (k < 0) return 0.0
        val j = (k + 1 until h.size).firstOrNull { h[it].metres > h[k].metres } ?: return h[k].metres
        val fromIdx = (j - 1 downTo 0).firstOrNull { h[it].beside }
        // The run that added them began after t: none of them by then.
        if (fromIdx != null && fromIdx > k) return h[k].metres
        val before = h[fromIdx ?: k]
        val next = h[j]
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
            if (v.runFromMs != 0L) v.runFromMs += shift
            if (v.unplacedMs != 0L) v.unplacedMs += shift
            for (m in v.history) m.timeMs += shift
            // Judged against our place at the wrong moment: a vehicle beside
            // us seemed a hundred metres off (review of 20 Oct 2026).
            v.lastApart = false
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
        /**
         * The zone ahead (19 Oct 2026; decided 24 Oct): a report up to
         * ZONE_AHEAD_M ahead of us along the way we go and up to ZONE_SIDE_M
         * to the side is beside us too, while no other vehicle of the same
         * reading is within LONE_M of us. Seated at the back of a long
         * vehicle its report, from the front, was 26 m ahead in a bus and
         * 37-40 m in two coupled trams.
         */
        const val ZONE_AHEAD_M = 60.0
        const val ZONE_SIDE_M = 15.0
        const val LONE_M = 60.0
        /** The way we go is read off our places this far apart in time… */
        const val ZONE_HEADING_MS = 5_000L
        /** …and only at this speed at least, so that standing has none. */
        const val ZONE_MIN_MPS = 2.0
        /** No vehicle moves faster: a report whose moment we cannot place counts near unless clearly beyond it. */
        const val FAST_MPS = 20.0
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
        /** Within this of the furthest, the chosen line wins the first choice; a tie it is not in waits. */
        const val TIE_M = 50.0
        /** How much further another must have gone with us to replace ours. */
        const val SWITCH_M = 100.0
        /**
         * Off ours's route and ours not beside us this long: it has left us.
         * Not beside us at two readings in a row: in the twelve journeys
         * recorded to 14 Oct 2026 ours never missed more than one (at most
         * 50 s between its reports) — and after one missed, the next report,
         * 2-7 s old, comes over 60 s after the last (review of 24 Oct 2026).
         */
        const val OFF_ROUTE_QUIET_MS = 90_000L
        /** Ours silent this long no longer holds its place by its last report beside us. */
        const val SILENT_HOLD_MS = 5 * 60_000L
        /** Runs begun within this of the earliest set where the compared stretch begins: one report. */
        const val HEAD_START_MS = 30_000L

        /**
         * Vehicle speed: travel over [SPEED_WINDOW_MS], on smoothed accurate
         * positions. Twenty seconds, so that the scatter of a poor fix (±10 m)
         * all but never reaches it standing — over ten, it did in a test one
         * time in two over two minutes — while walking covers half of it.
         */
        const val VEHICLE_SPEED_MPS = 10.0 / 3.6
        const val SPEED_WINDOW_MS = 20_000L
        /**
         * Whether we rode as a vehicle was seen apart from us: vehicle speed
         * over this about its report. Ten seconds, not twenty, so that a
         * report soon after tracking begins can be judged; at a stop, fixes
         * scattered by 8-12 m changed no boarding in simulations (20 Oct 2026).
         */
        const val MOVING_WINDOW_MS = 10_000L
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
