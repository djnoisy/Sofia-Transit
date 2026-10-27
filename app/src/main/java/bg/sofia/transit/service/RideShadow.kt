package bg.sofia.transit.service

import java.util.Locale

/**
 * Feeds [RideModel] in the app and hands on what it concludes: since stage 3
 * of the new approach (HANDOVER "Етап 3") JourneyService follows the events
 * returned here — a vehicle identified, another switched to, ours withdrawn,
 * got off. The name is from stage 2, when the model ran beside the rules and
 * only wrote to the log.
 *
 * The model is fed the very fixes and readings the journey trace records, and
 * what else the app tells it — the boarding stop with the trips due there,
 * being off the route of ours — is recorded there as well, so that replaying
 * the trace (run_model_tests.sh --replay, run_trace_checks.sh --shadow)
 * concludes the same, at the same times, as the "Model" lines of the log: as
 * the app did. So it is given the values exactly as the trace writes them —
 * places to six decimals, accuracy to one, ids with the trace's own escaping —
 * and its lines say what happened in the words the replay uses ([describe]).
 *
 * Fixes come on the main thread and readings on a background one: each call
 * holds the lock. Pure Kotlin, no Android types.
 */
class RideShadow(
    chosenRouteId: String,
    /** Writes a line to the log. */
    private val info: (String) -> Unit,
    /** Writes a detail line to the log. */
    private val debug: (String) -> Unit = {}
) {
    /** One vehicle of a reading, as the feed gives it; [timestampSec] is its report time. */
    class Seen(
        val vehicleId: String, val tripId: String, val routeId: String,
        val timestampSec: Long, val lat: Double, val lon: Double
    )

    private val model = RideModel(chosenRouteId)
    /** What became of the early rule by the boarding stop, once logged; null before. */
    private var boardingOutcome: String? = null
    /** The trip and route ids of each vehicle as the feed last gave them, by the model's key. */
    private val feedIds = HashMap<String, Pair<String, String>>()

    /** One fix of ours. Returns what the model concluded on it. */
    @Synchronized
    fun onFix(timeMs: Long, receivedMs: Long, lat: Double, lon: Double, accuracy: Double?,
              speed: Double? = null): List<RideModel.Event> {
        val f = RideModel.Fix(timeMs, receivedMs, traced6(lat), traced6(lon), accuracy?.let { traced1(it) },
            speed?.let { traced1(it) })
        return tell(receivedMs, model.onFix(f))
    }

    /** One reading of the feed: the vehicles the trace lists. Returns what the model concluded on it. */
    @Synchronized
    fun onReading(receivedMs: Long, serverDateMs: Long, vehicles: List<Seen>): List<RideModel.Event> {
        val reports = vehicles.map { v ->
            val key = keyOf(v.vehicleId, v.tripId)
            feedIds[key] = v.tripId to v.routeId
            RideModel.Report(key, clean(v.tripId), clean(v.routeId),
                v.timestampSec * 1000, traced6(v.lat), traced6(v.lon))
        }
        val events = tell(receivedMs, model.onReading(receivedMs, serverDateMs, reports))
        val top = model.leaders().take(3)
        if (top.isNotEmpty()) {
            debug("Model @${clock(receivedMs)}: metres together — " + top.joinToString(", ") { v ->
                "${v.routeId}/${v.key} ${v.metres.toInt()} m" +
                    (v.lastDistance?.let { " (${it.toInt()} m away)" } ?: "")
            })
        }
        return events
    }

    /**
     * Whether the early rule by the boarding stop is still open: not judged,
     * nor closed by the model's first choice by metres (the rule is for the
     * first choice: RideModel.onBoarding), nor the stop found untold.
     */
    @Synchronized
    fun wantsBoarding(): Boolean = boardingOutcome == null && model.boarding == null

    /**
     * The early rule by the boarding stop ([stopName]) at [atMs], after a
     * reading: the model takes the vehicle it finds (RideModel.onBoarding).
     * [due]: the trips due there around our departure, by trip id as the
     * trace writes it, and when. Logged once, when it can be judged — until
     * then (nothing beside us at a report taken while we moved) it is asked
     * again after the next reading. Returns what the model concluded.
     */
    @Synchronized
    fun onBoarding(atMs: Long, stopName: String, due: Map<String, Long>): List<RideModel.Event> {
        if (!wantsBoarding()) return emptyList()
        val events = model.onBoarding { it in due }
        val b = model.boarding ?: return emptyList()
        val list = b.near.joinToString(", ") { (v, isDue) ->
            "${v.routeId}/${v.key} ${v.lastDistance?.toInt()?.let { "$it m" } ?: "? m"}" + (if (isDue) " (due there)" else "")
        }
        val outcome = b.pick?.let { v ->
            "took ${v.routeId} ${v.tripId} at once, due there at ${clock((due[v.tripId] ?: 0L) * 1000)}"
        } ?: "no early pick: ${b.why}"
        boardingOutcome = outcome
        info("Model (boarding stop ${clean(stopName)}): $outcome; near us: $list")
        return tell(atMs, events)
    }

    /** The boarding stop cannot be told this journey: the early rule is not judged. */
    @Synchronized
    fun boardingUntold() {
        if (!wantsBoarding()) return
        boardingOutcome = "boarding stop not told"
        info("Model (boarding stop): not told — the early rule is not judged")
    }

    /**
     * From the app: we are off the route of the vehicle the model holds as
     * [key] (RideModel.onOffRoute), or null once back on it.
     */
    @Synchronized
    fun onOffRoute(key: String?) = model.onOffRoute(key)

    /** Whether ours was beside us at a report at most [withinMs] before [nowMs] (RideModel.ourBesideWithin). */
    @Synchronized
    fun withUs(nowMs: Long, withinMs: Long): Boolean = model.ourBesideWithin(nowMs, withinMs)

    /**
     * The trip and route ids of the vehicle the model knows as [key], as the
     * feed last gave them: the model's own have ';' for ','. Null if unseen.
     */
    @Synchronized
    fun feedIds(key: String): Pair<String, String>? = feedIds[key]

    /** What the model holds at the end of the journey, for the log. */
    @Synchronized
    fun summary(): String {
        val o = model.ours
        val others = model.leaders().filter { it !== o }.take(3)
        return "Model at the end: " +
            (o?.let { "ours ${it.routeId}/${it.key} ${it.tripId}, ${it.metres.toInt()} m together" }
                ?: "no vehicle taken") +
            (if (model.alighted) ", got off concluded" else "") +
            (if (others.isEmpty()) "" else "; others: " +
                others.joinToString(", ") { "${it.routeId}/${it.key} ${it.metres.toInt()} m" }) +
            "; early rule by the boarding stop: ${boardingOutcome ?: model.boarding?.why ?: "not judged"}"
    }

    private fun tell(atMs: Long, events: List<RideModel.Event>): List<RideModel.Event> {
        for (e in events) info("Model @${clock(atMs)}: ${describe(e)}")
        return events
    }

    companion object {
        /** What the model concluded, in the words of the replay (tools/test-harness/model/Replay.kt). */
        fun describe(e: RideModel.Event): String = when (e) {
            is RideModel.Event.Identified -> "identified ${e.routeId} ${e.tripId} (" +
                (if (e.byBoardingStop) "by the boarding stop" else "${e.metres.toInt()} m together") + ")"
            is RideModel.Event.Switched -> "switched ${e.fromKey} → ${e.toRouteId} ${e.toTripId} (${e.metres.toInt()} m): ${e.why}"
            is RideModel.Event.Withdrawn -> "withdrawn ${e.key} (${e.metres.toInt()} m)"
            is RideModel.Event.Alighted -> "got off ${e.tripId}, it left at ${clock(e.leftAtMs)}"
        }

        /** Sofia time of day of an epoch time in ms, to the second. */
        fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.of("Europe/Sofia")).toLocalTime().withNano(0).toString()

        /** An id or a name as the trace writes it (util/JourneyTrace.kt): a comma or line break would break its line. */
        fun clean(s: String) = s.replace(',', ';').replace('\n', ' ')

        /** The model's key of a vehicle: its id, or its trip's when it has none — as the trace writes them. */
        fun keyOf(vehicleId: String, tripId: String) = clean(vehicleId).ifBlank { clean(tripId) }

        /** As the trace writes them (util/JourneyTrace.kt). */
        private fun traced6(v: Double) = String.format(Locale.US, "%.6f", v).toDouble()
        private fun traced1(v: Double) = String.format(Locale.US, "%.1f", v).toDouble()
    }
}
