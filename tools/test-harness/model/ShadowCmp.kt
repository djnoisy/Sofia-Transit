// The Model lines a recorded journey gives through RideShadow, as JourneyService feeds it
// (tools/test-harness/run_trace_checks.sh --shadow): compared with the phone's log, they
// must be the same — the early rule by the boarding stop (B lines) and being off the route
// of ours (O lines) included, and the line at the end when the journey was closed.
import bg.sofia.transit.service.RideShadow
import java.io.File
fun main(args: Array<String>) {
    val lines = File(args[0]).readLines().map { it.trimEnd('\r') }   // "\r\n" if passed through Windows
    val start = lines.first { it.startsWith("# start ") }
    val route = Regex("""\(route ([^,)]+)""").find(start)!!.groupValues[1]
    val sh = RideShadow(route, { println("I " + it) }, { println("D " + it) })
    var cur: Triple<Long, Long, MutableList<RideShadow.Seen>>? = null; var exp = 0
    fun flush() { val r = cur ?: return; cur = null; sh.onReading(r.first, r.second, r.third) }
    for (l in lines) { val p = l.split(","); when (p[0]) {
        "F" -> { flush(); sh.onFix(p[2].toLong(), p[1].toLong(), p[3].toDouble(), p[4].toDouble(), p[5].toDoubleOrNull()) }
        "V" -> { flush(); cur = Triple(p[1].toLong(), p[2].toLong(), ArrayList()); exp = p[4].toInt() }
        "v" -> { val r = cur ?: continue; r.third += RideShadow.Seen(p[3], p[1], p[2], p[4].toLong(), p[5].toDouble(), p[6].toDouble()); if (r.third.size == exp) flush() }
        "B" -> { flush(); if (p[2].isEmpty()) sh.boardingUntold()
                 else sh.onBoarding(p[1].toLong(), p[2], (3 until p.size - 1 step 2).associate { p[it] to p[it + 1].toLong() }) }
        "O" -> { flush(); sh.onOffRoute(p.getOrNull(2)?.ifEmpty { null }) }
    } }
    flush()
    // The line at the end is the app's when it closed the journey; not when the
    // app was killed and the next journey closed it ("unclosed").
    if (lines.any { it.startsWith("# end ") && !it.endsWith(" unclosed") }) println("I " + sh.summary())
}
