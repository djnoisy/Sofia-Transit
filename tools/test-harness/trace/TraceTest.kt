// JourneyTrace (util/JourneyTrace.kt), the raw record of each journey: the
// format of every line, which vehicles a reading lists, readings never split
// by fixes written at the same time from other threads, the size cap with the
// journey's start line kept, clear during a journey, a journey left open
// closed by the next, and that nothing reaches the caller — before init
// included.
import android.content.Context
import android.location.Location
import bg.sofia.transit.data.repository.VehicleInfo
import bg.sofia.transit.data.repository.VehicleSnapshot
import bg.sofia.transit.util.JourneyTrace
import java.io.File
import kotlin.concurrent.thread

var fails = 0
fun check(n: String, ok: Boolean, detail: String = "") {
    if (ok) println("PASS $n") else { fails++; println("FAIL $n $detail") }
}

fun lines(f: File) = if (f.exists()) f.readLines() else emptyList()

/** Waits until the background writer has caught up with [marker]. */
fun waitFor(f: File, marker: String, timeoutMs: Long = 20_000) {
    val until = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < until) {
        if (f.exists() && f.readText().contains(marker)) return
        Thread.sleep(20)
    }
}

fun veh(trip: String, lat: Double, lon: Double, ts: Long = 1_759_730_000L, id: String = "v-$trip") =
    VehicleInfo(id, trip, "A85", lat, lon, 90f, null, null, ts)

/** A V line followed by exactly as many v lines as it says, everywhere. */
fun blocksWhole(ls: List<String>): Boolean {
    var i = 0
    // After rotation the file may start inside a block: skip to the first record header.
    while (i < ls.size && ls[i].startsWith("v,")) i++
    while (i < ls.size) {
        val l = ls[i]
        if (l.startsWith("V,")) {
            val n = l.split(",")[4].toInt()
            for (k in 1..n) if (i + k >= ls.size || !ls[i + k].startsWith("v,")) return false
            i += n + 1
        } else if (l.startsWith("v,")) return false
        else i++
    }
    return true
}

fun main() {
    val dir = kotlin.io.path.createTempDirectory("trace").toFile()
    val f = File(dir, "journey_trace.txt")

    // Before init: nothing written, nothing thrown.
    JourneyTrace.fix(Location(42.0, 23.0, 5f))
    JourneyTrace.start("before init")
    check("before init: no file, no exception", !f.exists())

    JourneyTrace.init(Context(dir, dir))
    val lat = 42.668000; val lon = 23.375000
    JourneyTrace.start("Автобус 213 → ЦЕНТРАЛНА ГАРА (route A85, trip A85-A4508-2-6-1)\nsecond line")
    JourneyTrace.fix(Location(lat, lon, 4.5f, 9.75f, 313.2f, 1_759_730_001_000L))
    JourneyTrace.fix(Location(lat, lon))   // no accuracy, speed or bearing
    val snap = VehicleSnapshot(1_759_730_002_500L, 1_759_730_002_000L, 1_759_729_999L, listOf(
        veh("near", lat + 0.0009, lon),            // ~100 m
        veh("far", lat + 0.02, lon),               // ~2.2 km
        veh("tracked", lat + 0.045, lon),          // ~5 km, but the one followed
        veh("odd,id", lat, lon + 0.001, id = "x,y") // ~80 m, commas in its ids
    ))
    JourneyTrace.vehicles(snap, lat, lon, "tracked")
    JourneyTrace.end()
    waitFor(f, "# end")
    val ls = lines(f)

    check("start line, on one line", ls.getOrNull(0)?.startsWith("# start ") == true &&
        ls[0].endsWith("second line") && ls[0].contains("Автобус 213"), ls.getOrNull(0) ?: "")
    check("fix with all fields", ls.getOrNull(1)?.let {
        val p = it.split(",")
        p.size == 8 && p[0] == "F" && p[2] == "1759730001000" && p[3] == "42.668000" &&
            p[4] == "23.375000" && p[5] == "4.5" && p[6] == "9.8" && p[7] == "313.2"
    } == true, ls.getOrNull(1) ?: "")
    check("fix without accuracy, speed, bearing: empty fields", ls.getOrNull(2)?.let {
        val p = it.split(",")
        p.size == 8 && p[5] == "" && p[6] == "" && p[7] == ""
    } == true, ls.getOrNull(2) ?: "")
    check("reading header: times, count, our position", ls.getOrNull(3) ==
        "V,1759730002500,1759730002000,1759729999,3,42.668000,23.375000", ls.getOrNull(3) ?: "")
    val vs = ls.filter { it.startsWith("v,") }
    check("near vehicle listed", vs.any { it.startsWith("v,near,A85,v-near,1759730000,42.668900,23.375000,90.0") },
        vs.toString())
    check("followed vehicle listed though 5 km away", vs.any { it.startsWith("v,tracked,") })
    check("vehicle 2 km away left out", vs.none { it.startsWith("v,far,") })
    check("commas in ids do not break the line", vs.any { it.startsWith("v,odd;id,A85,x;y,") } &&
        vs.all { it.split(",").size == 8 }, vs.toString())
    check("end line last", ls.lastOrNull()?.startsWith("# end ") == true)

    // Many threads at once: fixes from one, readings from others — one long
    // journey, enough to be cut down to size.
    JourneyTrace.start("long ride")
    val ts = (1..6).map { k ->
        thread {
            repeat(3_000) { i ->
                if (k == 1) JourneyTrace.fix(Location(lat, lon, 3f, 10f, 1f, i.toLong()))
                else JourneyTrace.vehicles(VehicleSnapshot(i.toLong(), 0, 0,
                    (0..k).map { veh("t$k-$it", lat + it * 0.0001, lon) }), lat, lon, "")
            }
        }
    }
    ts.forEach { it.join() }
    JourneyTrace.end()
    Thread.sleep(300)
    waitFor(f, "# end")
    Thread.sleep(500)
    val all = lines(f)
    check("concurrent writing: every reading whole", blocksWhole(all))
    check("size kept under the cap (4 MB)", f.length() <= 4_000_000L + 200_000L, "${f.length()}")
    check("cut down to size: the journey's start line is kept at the top",
        all.firstOrNull()?.let { it.startsWith("# start ") && it.endsWith("long ride") } == true,
        all.firstOrNull() ?: "")
    check("cut down to size: no vehicle line without its reading", blocksWhole(all.drop(1)) &&
        all.getOrNull(1)?.startsWith("v,") != true, all.getOrNull(1) ?: "")
    check("the journey's start line appears once", all.count { it.endsWith(" long ride") } == 1)

    // Cleared during a journey: its start line stays.
    JourneyTrace.start("journey two")
    JourneyTrace.fix(Location(lat, lon, 3f))
    JourneyTrace.clear()
    waitFor(f, "# cleared")
    Thread.sleep(200)
    lines(f).let {
        check("clear during a journey keeps its start line", it.size == 2 &&
            it[0].startsWith("# cleared ") && it[1].startsWith("# start ") && it[1].endsWith("journey two"),
            it.take(3).toString())
    }

    // A journey left open (the service killed) is closed when the next starts.
    JourneyTrace.start("journey three")
    JourneyTrace.end()
    waitFor(f, "journey three")
    Thread.sleep(200)
    lines(f).let {
        val i = it.indexOfFirst { l -> l.endsWith("journey three") }
        check("an open journey is closed before the next starts",
            i > 0 && it[i - 1].startsWith("# end ") && it[i - 1].endsWith(" unclosed"), it.toString())
    }

    JourneyTrace.clear()
    waitFor(f, "# cleared")
    Thread.sleep(200)
    check("clear with no journey open leaves only the marker",
        lines(f).let { it.size == 1 && it[0].startsWith("# cleared ") }, lines(f).take(3).toString())

    if (fails == 0) println("ALL PASS") else println("$fails FAILED")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
