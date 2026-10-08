package bg.sofia.transit.service

import java.util.Locale

/**
 * Stage 2 of the new approach (HANDOVER: "Нов подход"): [RideModel] run
 * beside the current rules, on the very fixes and readings the journey trace
 * records, with its conclusions written to the log and nothing else. No
 * announcement, no tracking decision, nothing the passenger sees reads it.
 *
 * The check it is there for is that the model is fed in the app as in the
 * tests: replaying the same journey's trace (run_model_tests.sh --replay)
 * must conclude the same, at the same times, as the "Model @" lines of the
 * log. So it is given the values exactly as the trace writes them — places to
 * six decimals, accuracy to one, ids with the trace's own escaping — and its
 * lines say what happened in the words the replay uses ([describe]).
 *
 * Also logs what the proposed early rule by the boarding stop would decide
 * (HANDOVER: "Предложение: ранно разпознаване по спирката на качване"),
 * once a journey, beside the model and without touching it.
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
    /** What became of the early rule by the boarding stop, for the summary; null while open. */
    private var boardingOutcome: String? = null

    @Synchronized
    fun onFix(timeMs: Long, receivedMs: Long, lat: Double, lon: Double, accuracy: Double?) {
        val f = RideModel.Fix(timeMs, receivedMs, traced6(lat), traced6(lon), accuracy?.let { traced1(it) })
        tell(receivedMs, model.onFix(f))
    }

    @Synchronized
    fun onReading(receivedMs: Long, serverDateMs: Long, vehicles: List<Seen>) {
        val reports = vehicles.map { v ->
            val tripId = clean(v.tripId)
            RideModel.Report(clean(v.vehicleId).ifBlank { tripId }, tripId, clean(v.routeId),
                v.timestampSec * 1000, traced6(v.lat), traced6(v.lon))
        }
        tell(receivedMs, model.onReading(receivedMs, serverDateMs, reports))
        val top = model.leaders().take(3)
        if (top.isNotEmpty()) {
            debug("Model @${clock(receivedMs)}: metres together — " + top.joinToString(", ") { v ->
                "${v.routeId}/${v.key} ${v.metres.toInt()} m" +
                    (v.lastDistance?.let { " (${it.toInt()} m away)" } ?: "")
            })
        }
    }

    /** Whether the early rule by the boarding stop is still to be judged. */
    @Synchronized
    fun wantsBoarding(): Boolean = boardingOutcome == null && model.ours == null && !model.alighted

    /**
     * The early rule by the boarding stop, at the latest reading: of the
     * vehicles beside us at the same moment, the one that was due at the stop
     * we boarded at ([stopName]) around our departure, if just one was —
     * [dueAt] tells when a trip was due there, or null. Logged, once.
     */
    @Synchronized
    fun judgeBoarding(stopName: String, dueAt: (String) -> Long?) {
        if (!wantsBoarding()) return
        val beside = model.besideUs().sortedBy { it.lastDistance ?: 0.0 }
        val due = beside.mapNotNull { v -> dueAt(v.tripId)?.let { v to it } }
        val list = beside.joinToString(", ") { v ->
            "${v.routeId}/${v.key} ${v.lastDistance?.toInt()} m" +
                (if (due.any { it.first === v }) " (due there)" else "")
        }
        val outcome = when {
            beside.isEmpty() -> "nothing beside us — no early pick"
            due.size == 1 -> due[0].let { (v, at) ->
                "would take ${v.routeId} ${v.tripId} at once, due there at ${clock(at * 1000)}"
            }
            else -> "no early pick: ${due.size} of the vehicles beside us due there"
        }
        boardingOutcome = outcome
        info("Model (boarding stop $stopName): $outcome" +
            (if (beside.isEmpty()) "" else "; beside us: $list"))
    }

    /** The boarding stop cannot be told this journey: the early rule is not judged. */
    @Synchronized
    fun boardingUntold() {
        if (boardingOutcome != null) return
        boardingOutcome = "boarding stop not told"
        info("Model (boarding stop): not told — the early rule is not judged")
    }

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
            "; early rule by the boarding stop: ${boardingOutcome ?: "not judged"}"
    }

    private fun tell(atMs: Long, events: List<RideModel.Event>) {
        for (e in events) info("Model @${clock(atMs)}: ${describe(e)}")
    }

    companion object {
        /** What the model concluded, in the words of the replay (tools/test-harness/model/Replay.kt). */
        fun describe(e: RideModel.Event): String = when (e) {
            is RideModel.Event.Identified -> "identified ${e.routeId} ${e.tripId} (${e.metres.toInt()} m together)"
            is RideModel.Event.Switched -> "switched ${e.fromKey} → ${e.toRouteId} ${e.toTripId} (${e.metres.toInt()} m): ${e.why}"
            is RideModel.Event.Withdrawn -> "withdrawn ${e.key} (${e.metres.toInt()} m)"
            is RideModel.Event.Alighted -> "got off ${e.tripId}, it left at ${clock(e.leftAtMs)}"
        }

        /** Sofia time of day of an epoch time in ms, to the second. */
        fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
            .atZone(java.time.ZoneId.of("Europe/Sofia")).toLocalTime().withNano(0).toString()

        /** As the trace writes them (util/JourneyTrace.kt). */
        private fun traced6(v: Double) = String.format(Locale.US, "%.6f", v).toDouble()
        private fun traced1(v: Double) = String.format(Locale.US, "%.1f", v).toDouble()
        private fun clean(s: String) = s.replace(',', ';').replace('\n', ' ')
    }
}
