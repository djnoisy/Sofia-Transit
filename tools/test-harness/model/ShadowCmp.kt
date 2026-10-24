// The Model lines a recorded journey gives through RideShadow, as JourneyService feeds it
// (tools/test-harness/run_trace_checks.sh --shadow): compared with the phone's log, they
// must be the same. The early rule's line and the end line are written by JourneyService.
import bg.sofia.transit.service.RideShadow
import java.io.File
fun main(args: Array<String>) {
    val lines = File(args[0]).readLines()
    val start = lines.first { it.startsWith("# start ") }
    val route = Regex("""\(route ([^,)]+)""").find(start)!!.groupValues[1]
    val sh = RideShadow(route, { println("I " + it) }, { println("D " + it) })
    var cur: Triple<Long, Long, MutableList<RideShadow.Seen>>? = null; var exp = 0
    fun flush() { val r = cur ?: return; cur = null; sh.onReading(r.first, r.second, r.third) }
    for (l in lines) { val p = l.split(","); when (p[0]) {
        "F" -> { flush(); sh.onFix(p[2].toLong(), p[1].toLong(), p[3].toDouble(), p[4].toDouble(), p[5].toDoubleOrNull()) }
        "V" -> { flush(); cur = Triple(p[1].toLong(), p[2].toLong(), ArrayList()); exp = p[4].toInt() }
        "v" -> { val r = cur ?: continue; r.third += RideShadow.Seen(p[3], p[1], p[2], p[4].toLong(), p[5].toDouble(), p[6].toDouble()); if (r.third.size == exp) flush() }
    } }
    flush()
}
