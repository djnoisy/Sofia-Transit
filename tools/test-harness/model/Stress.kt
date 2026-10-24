// Stress tests of a recorded journey (tools/test-harness/run_trace_checks.sh --stress):
// thinned fixes, fixes dropped for 10-20 s at every 5 s, and tracking begun mid-way
// every 10 s. Prints what the model concludes in each case and counts wrong vehicles.
import bg.sofia.transit.service.RideModel.Event
import java.io.File

fun thin(lines: List<String>, stepS: Int): List<String> {
    var next = Long.MIN_VALUE
    return lines.mapNotNull { line ->
        val p = line.split(","); if (p[0] != "F") return@mapNotNull line
        val t = p[2].toLong(); if (t < next) return@mapNotNull null
        next = t + stepS * 1000L - 500L; (p.take(6) + listOf("", "")).joinToString(",")
    }
}
fun fixT(l: String) = l.split(",").let { if (it[0] == "F") it[1].toLong() else if (it[0] == "V") it[1].toLong() else null }
fun summary(rp: Replayed, ours: String): Triple<Boolean, String?, String?> {
    val wrong = rp.events.any { when (val e = it.event) { is Event.Identified -> e.routeId != ours; is Event.Switched -> e.toRouteId != ours; else -> false } }
    val id = rp.events.firstOrNull { it.event is Event.Identified }?.let { clock(it.atMs) }
    val off = rp.events.firstOrNull { it.event is Event.Alighted }?.let { clock(it.atMs) }
    return Triple(wrong, id, off)
}
fun main(args: Array<String>) {
    val (start, lines) = journeys(File(args[0])).first()
    val ours = args.getOrNull(1) ?: (replay(start, lines).events.firstNotNullOfOrNull { (it.event as? Event.Identified)?.routeId } ?: "")
    val base = summary(replay(start, lines), ours)
    println("base: $base, events=" + replay(start, lines).events.joinToString { clock(it.atMs) + " " + describe(it.event) })
    for (s in listOf(2, 3, 4, 5, 6, 7, 8, 10, 15)) {
        val rp = replay(start, thin(lines, s)); println("every $s s: " + summary(rp, ours) + " | " + rp.events.joinToString { clock(it.atMs) + " " + describe(it.event) })
    }
    // gaps: drop fixes for g seconds starting every 5 s
    val times = lines.mapNotNull { fixT(it) }; val t0 = times.first(); val t1 = times.last()
    for (g in listOf(10, 14, 20)) {
        var n = 0; var wrong = 0; var later = 0; var earlier = 0; var noId = 0; var offDiff = 0; val worst = ArrayList<String>()
        var at = t0
        while (at < t1) {
            val cut = lines.filter { l -> val p = l.split(","); !(p[0] == "F" && p[1].toLong() in at until at + g * 1000) }
            val r = summary(replay(start, cut), ours); n++
            if (r.first) { wrong++; worst += "wrong@" + clock(at) }
            if (r.second == null) noId++ else if (r.second!! > base.second!!) { later++; if (worst.size < 6) worst += "id ${r.second}@" + clock(at) } else if (r.second!! < base.second!!) earlier++
            if (r.third != base.third) { offDiff++; if (worst.size < 12) worst += "off ${r.third}@" + clock(at) }
            at += 5000
        }
        println("gap $g s: $n cases, wrong=$wrong, later id=$later, earlier=$earlier, no id=$noId, different off=$offDiff  ${worst.take(12)}")
    }
    // tracking started mid-way: drop everything before t, every 10 s
    var n = 0; var wrong = 0; val ids = ArrayList<Long>(); var noId = 0; val offs = HashMap<String?, Int>()
    var at = t0
    val lastV = lines.last { it.startsWith("V,") }.split(",")[1].toLong()
    while (at < lastV - 120_000) {
        val firstIdx = lines.indexOfFirst { fixT(it)?.let { t -> t >= at } == true && it.startsWith("F,") }
        val cut = lines.drop(firstIdx)
        val rp = replay(start, cut); val r = summary(rp, ours); n++
        if (r.first) { wrong++; println("  WRONG from " + clock(at) + ": " + rp.events.joinToString { clock(it.atMs) + " " + describe(it.event) }) }
        val idEv = rp.events.firstOrNull { it.event is Event.Identified }
        if (idEv == null) { noId++; println("  no id from " + clock(at)) } else { val d = (idEv.atMs - maxOf(at, lines.first { it.startsWith("V,") }.split(",")[1].toLong())) / 1000; ids += d; if (d > 75) println("  late: from " + clock(at) + " id " + clock(idEv.atMs)) }
        offs[r.third] = (offs[r.third] ?: 0) + 1
        at += 10_000
    }
    ids.sort()
    println("started mid-way: $n starts, wrong=$wrong, no id=$noId, id after start (s): min ${ids.firstOrNull()}, median ${ids.getOrNull(ids.size/2)}, max ${ids.lastOrNull()}; got off: $offs")
}
