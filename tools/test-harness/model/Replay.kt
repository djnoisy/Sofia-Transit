// Replays a journey trace (util/JourneyTrace.kt, journey_trace.txt) through
// RideModel, line by line in the order the app wrote them, and tells what the
// model concluded and when. Used by ModelTests and on its own:
//   run_model_tests.sh --replay <journey_trace.txt>
import bg.sofia.transit.service.RideModel
import java.io.File

data class Concluded(val atMs: Long, val event: RideModel.Event)

class Replayed(val journey: String, val events: List<Concluded>, val model: RideModel,
               /** Metres of each vehicle at each reading: reading time → key → metres. */
               val metresAt: List<Pair<Long, Map<String, Double>>>,
               val routeOf: Map<String, String>)

/** The journeys in a trace, each as its start line and its records. */
fun journeys(file: File): List<Pair<String, List<String>>> {
    val out = ArrayList<Pair<String, MutableList<String>>>()
    for (line in file.readLines()) {
        if (line.startsWith("# start ")) out.add(line to ArrayList())
        else if (line.startsWith("# end") || line.startsWith("# cleared")) continue
        else out.lastOrNull()?.second?.add(line)
    }
    return out
}

fun chosenRoute(start: String): String =
    Regex("""\(route ([^,)]+)""").find(start)?.groupValues?.get(1) ?: ""

fun replay(start: String, lines: List<String>): Replayed {
    val model = RideModel(chosenRoute(start))
    val events = ArrayList<Concluded>()
    val metresAt = ArrayList<Pair<Long, Map<String, Double>>>()
    val routeOf = HashMap<String, String>()
    var reading: Triple<Long, Long, MutableList<RideModel.Report>>? = null
    var expected = 0
    fun flush() {
        val r = reading ?: return
        reading = null
        model.onReading(r.first, r.second, r.third).forEach { events += Concluded(r.first, it) }
        metresAt += r.first to model.leaders().associate { it.key to it.metres }
    }
    for (line in lines) {
        val p = line.split(",")
        when (p[0]) {
            "F" -> {
                flush()
                val f = RideModel.Fix(p[2].toLong(), p[1].toLong(), p[3].toDouble(), p[4].toDouble(),
                    p[5].toDoubleOrNull())
                model.onFix(f).forEach { events += Concluded(f.receivedMs, it) }
            }
            "V" -> {
                flush()
                reading = Triple(p[1].toLong(), p[2].toLong(), ArrayList())
                expected = p[4].toInt()
            }
            "v" -> {
                val r = reading ?: continue   // a vehicle line cut off from its reading
                val key = p[3].ifBlank { p[1] }
                routeOf[key] = p[2]
                r.third += RideModel.Report(key, p[1], p[2], p[4].toLong() * 1000,
                    p[5].toDouble(), p[6].toDouble())
                if (r.third.size == expected) flush()
            }
        }
    }
    flush()
    return Replayed(start, events, model, metresAt, routeOf)
}

fun clock(ms: Long): String = java.time.Instant.ofEpochMilli(ms)
    .atZone(java.time.ZoneId.of("Europe/Sofia")).toLocalTime().withNano(0).toString()

fun describe(e: RideModel.Event): String = when (e) {
    is RideModel.Event.Identified -> "identified ${e.routeId} ${e.tripId} (${e.metres.toInt()} m together)"
    is RideModel.Event.Switched -> "switched ${e.fromKey} → ${e.toRouteId} ${e.toTripId} (${e.metres.toInt()} m): ${e.why}"
    is RideModel.Event.Withdrawn -> "withdrawn ${e.key} (${e.metres.toInt()} m)"
    is RideModel.Event.Alighted -> "got off ${e.tripId}, it left at ${clock(e.leftAtMs)}"
}

fun printReplay(r: Replayed) {
    println(r.journey)
    for (c in r.events) println("  ${clock(c.atMs)}  ${describe(c.event)}")
    val top = r.metresAt.lastOrNull()?.second.orEmpty()
    val peakOthers = HashMap<String, Double>()
    for ((_, m) in r.metresAt) for ((k, x) in m) peakOthers[k] = maxOf(peakOthers[k] ?: 0.0, x)
    println("  highest metres reached: " + peakOthers.entries.sortedByDescending { it.value }.take(6)
        .joinToString(", ") { "${r.routeOf[it.key] ?: "?"}/${it.key}=${it.value.toInt()}" })
    if (top.isEmpty()) Unit
}

fun main(args: Array<String>) {
    for ((start, lines) in journeys(File(args[0]))) printReplay(replay(start, lines))
}
