// Trips at a stop around us, or just past it (service/ReachedStops.kt): the
// early rule by the boarding stop counts such a trip as due there, since the
// trip-updates feed drops the stop a vehicle has reached (HANDOVER,
// "Пътуване от 7.11.2026 b"). Run by run_boarding_tests.sh.
import bg.sofia.transit.service.ReachedStops
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot

private var passes = 0
private var fails = 0

private fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) passes++ else { fails++; println("FAIL $name ${detail()}") }
}

private fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double =
    hypot((a.first - b.first) * 111_195.0,
        (a.second - b.second) * 111_195.0 * cos(Math.toRadians((a.first + b.first) / 2)))

fun main(args: Array<String>) {
    val order = listOf("S1", "S2", "S3", "S4", "S5")
    val orders = mapOf("T" to order)
    fun of(ids: Set<String>, next: String, o: Map<String, List<String>> = orders) =
        ReachedStops.of(ids, mapOf("T" to next), o)

    // ── Made-up orders ──
    check("at S3 (first ahead S4): S3 given", of(setOf("S3"), "S4") == setOf("T" to "S3"))
    check("before S3 (first ahead S3): nothing for S3", of(setOf("S3"), "S3").isEmpty())
    check("past S4 (first ahead S5): nothing for S3", of(setOf("S3"), "S5").isEmpty())
    check("first ahead the first stop: nothing", of(order.toSet(), "S1").isEmpty())
    check("all stops around us, first ahead S4: only S3",
        of(order.toSet(), "S4") == setOf("T" to "S3"))
    check("the last stop reached: nothing to tell", of(setOf("S5"), "S5").isEmpty())
    check("a stop around us not on the trip: nothing", of(setOf("X"), "S4").isEmpty())
    check("a trip not in the static data: nothing", of(setOf("S3"), "S4", emptyMap()).isEmpty())
    check("a stop ahead not on the trip: nothing", of(setOf("S3"), "X").isEmpty())
    check("the stop ahead twice in the order (a loop): nothing",
        of(setOf("S2", "S4"), "S3", mapOf("T" to listOf("S2", "S3", "S4", "S3"))).isEmpty())
    check("two ids of one place (bus and trolley rows): the one on the trip",
        of(setOf("S3", "TB-S3"), "S4") == setOf("T" to "S3"))
    check("several trips: each by its own order",
        ReachedStops.of(setOf("S3"), mapOf("T" to "S4", "U" to "S3", "V" to "S4"),
            mapOf("T" to order, "U" to order, "V" to listOf("Y", "S3", "S4"))) ==
            setOf("T" to "S3", "V" to "S3"))
    check("nothing ahead known: nothing", ReachedStops.of(setOf("S3"), emptyMap(), orders).isEmpty())

    // ── Every order of stops of the network ──
    // A vehicle whose first stop ahead is p[i] has reached p[i-1] (at it, or
    // just past it). Looked at from where we wait — around p[i-1], p[i] or
    // p[i+1], every stop within 150 m (JourneyService.BOARDING_STOPS_RADIUS):
    // the stop given is p[i-1], and never one the vehicle has not reached.
    val lines = File(args[0]).readLines().filter { it.isNotBlank() }
    val trips = lines.map { l ->
        val (trip, rest) = l.split("|", limit = 2)
        trip to rest.split(";").map { s -> val (id, la, lo) = s.split(":"); id to (la.toDouble() to lo.toDouble()) }
    }
    val places = trips.flatMap { it.second }.toMap()
    // Stops by a coarse grid (~200 m), for the stops within 150 m.
    val cell = { p: Pair<Double, Double> -> (p.first / 0.002).toInt() to (p.second / 0.0027).toInt() }
    val grid = places.entries.groupBy({ cell(it.value) }, { it.key })
    fun around(p: Pair<Double, Double>): Set<String> {
        val (cy, cx) = cell(p)
        val out = HashSet<String>()
        for (dy in -1..1) for (dx in -1..1) for (id in grid[(cy + dy) to (cx + dx)].orEmpty())
            if (metres(places.getValue(id), p) <= 150.0) out += id
        return out
    }
    var cases = 0
    var given = 0
    val wrong = ArrayList<String>()
    val missed = ArrayList<String>()
    for ((trip, stops) in trips) {
        val ids = stops.map { it.first }
        val o = mapOf(trip to ids)
        for (i in 1 until ids.size) {
            for (k in listOf(i - 1, i, i + 1)) {
                if (k >= ids.size) continue
                cases++
                val r = ReachedStops.of(around(stops[k].second), mapOf(trip to ids[i]), o)
                for ((_, s) in r) {
                    given++
                    if (s != ids[i - 1]) wrong += "$trip at ${ids[i - 1]}: gave $s (waiting by ${ids[k]})"
                }
                if (k == i - 1 && (trip to ids[i - 1]) !in r) missed += "$trip at ${ids[i - 1]}"
            }
        }
    }
    println("network: ${trips.size} orders of stops, $cases cases, $given stops given")
    check("network: the stop given is always the one just reached (never one ahead)", wrong.isEmpty()) {
        "${wrong.size}: ${wrong.take(5)}" }
    check("network: waiting by the stop just reached, it is always given", missed.isEmpty()) {
        "${missed.size}: ${missed.take(5)}" }
    println("$passes passed, $fails failed")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
