// The model in the app from end to end: a journey is written through the REAL
// JourneyTrace and given to the REAL RideShadow exactly as JourneyService does
// — the same fix with its arrival time, the same reading with the same list of
// vehicles (JourneyTrace.listed), the early rule by the boarding stop asked
// after a reading with the trips due there (B), being off the route of the
// vehicle followed told before a fix (O) — then the trace file written is
// replayed through the model as run_model_tests.sh --replay does
// (model/Replay.kt), and the replay's "Model @" lines must be the app's, line
// for line.
//
// The journeys are made to find what would set the two apart: ids with
// commas (the trace writes them with ';'), a vehicle with no id (the trip id
// stands in), accuracy around the model's 30 m (the trace writes 0.1 m),
// fixes without accuracy, gaps, fix and arrival clocks apart, reports of
// varying age; a stop name with a comma, the trip due there or not, the other
// bus near us at the first reading or far behind; ours silent for a while
// off its route, and off its route while beside us. 40 journeys, each from
// its own seed. Written with the reviewer of stage 2 (8 Oct 2026); B and O
// added in stage 3 (b).
import android.content.Context
import android.location.Location
import bg.sofia.transit.data.repository.VehicleInfo
import bg.sofia.transit.data.repository.VehicleSnapshot
import bg.sofia.transit.service.RideModel
import bg.sofia.transit.service.RideShadow
import bg.sofia.transit.util.JourneyTrace
import java.io.File
import kotlin.random.Random

fun main(args: Array<String>) {
    Feed.dir = File(args[0])
    val dir = kotlin.io.path.createTempDirectory("shadow").toFile()
    val f = File(dir, "journey_trace.txt")
    JourneyTrace.init(Context(dir, dir))
    var same = 0
    var lines = 0
    var byStop = 0
    var leftOffRoute = 0
    val differ = ArrayList<String>()
    for (seed in 1..40) {
        f.delete()
        val rnd = Random(seed)
        val app = ArrayList<String>()
        val shadow = RideShadow("A85", { app += it })
        JourneyTrace.start("Автобус 213 (route A85, trip T,213)")
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        // Ride 1.5 km, stand at a stop, ride 2 km more and get off; the bus goes on.
        val ride = Mover(p, s0).stand(20.0).drive(s0 + 1500, 9.0).stand(40.0).drive(s0 + 3500, 10.0).stand(400.0)
        val bus = Mover(p, s0).stand(20.0).drive(s0 + 1500, 9.0).stand(40.0).drive(s0 + 3500, 10.0).drive(s0 + 9000, 10.0)
        val otherBack = if (seed % 3 == 0) 300.0 else 40.0
        val other = Mover(p, s0 - otherBack).stand(15.0).drive(s0 + 4000, 9.5)
        // Ours silent from 250 s to 420 s in half the journeys; off its route
        // from 260 s to 330 s (silent: it leaves us) or 120-180 s (beside us: it stays).
        val silent = seed % 2 == 0
        val offFrom = if (silent) 260.0 else 120.0
        val offTo = if (silent) 330.0 else 180.0
        val vehicles = listOf(
            Triple("V,213", "T,213", { t: Double -> if (silent && t in 250.0..420.0) null else bus.at(t) }),
            Triple("", "T304", { t: Double -> other.at(t).shift(3.0, 0.0) }),
        )
        val due = if (seed % 4 == 1) emptyMap() else mapOf(RideShadow.clean("T,213") to T0 / 1000 + 25)
        // As JourneyService: the vehicle followed, by the model's conclusions it follows.
        var followedKey: String? = null
        var toldKey: String? = null
        fun follow(events: List<RideModel.Event>) {
            for (e in events) when (e) {
                is RideModel.Event.Identified -> followedKey = e.key
                is RideModel.Event.Switched -> followedKey = e.toKey
                is RideModel.Event.Withdrawn -> if (e.key == followedKey) followedKey = null
                is RideModel.Event.Alighted -> {}
            }
        }
        var lastLat = 0.0; var lastLon = 0.0
        var t = 0.0
        while (t <= 900.0) {
            if (rnd.nextInt(10) != 0) {
                val q = ride.at(t).shift(rnd.nextDouble(-6.0, 6.0), rnd.nextDouble(-6.0, 6.0))
                val acc: Float? = if (rnd.nextInt(15) == 0) null else (29.9 + rnd.nextDouble() * 0.2).toFloat()
                val fixMs = T0 + (t * 1000).toLong() + rnd.nextLong(0, 900)
                val loc = Location(q.lat, q.lon, acc, 5f, 90f, fixMs)
                val receivedMs = fixMs + 40 + rnd.nextLong(0, 300)
                // As JourneyService.onFix: being off the route of ours told first (tellOffRoute).
                val key = if (t in offFrom..offTo) followedKey else null
                if (key != toldKey) {
                    toldKey = key
                    JourneyTrace.offRoute(receivedMs, key)
                    shadow.onOffRoute(key)
                }
                JourneyTrace.fix(loc, receivedMs)
                follow(shadow.onFix(loc.time, receivedMs, loc.latitude, loc.longitude,
                    if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                    if (loc.hasSpeed()) loc.speed.toDouble() else null))
                lastLat = loc.latitude; lastLon = loc.longitude
            }
            if (t >= 10 && (t.toLong() % 30L) == 10L) {
                val infos = vehicles.mapNotNull { (id, trip, pos) ->
                    val rt = kotlin.math.floor(t - 2 - rnd.nextDouble(0.0, 25.0))
                    if (rt < 0) null else pos(rt)?.shift(rnd.nextDouble(-4.0, 4.0), rnd.nextDouble(-4.0, 4.0))?.let {
                        VehicleInfo(id, trip, "A85", it.lat, it.lon, 90f, null, null, (T0 / 1000) + rt.toLong())
                    }
                }
                val fetched = T0 + (t * 1000).toLong() + rnd.nextLong(0, 900)
                val snap = VehicleSnapshot(fetched, (fetched - 200) / 1000 * 1000, fetched / 1000 - 5, infos)
                // As JourneyService.readVehicles.
                val listed = JourneyTrace.listed(snap, lastLat, lastLon, "T,213")
                JourneyTrace.vehicles(snap, lastLat, lastLon, listed)
                follow(shadow.onReading(snap.fetchedAtMs, snap.serverDateMs, listed.map { v ->
                    RideShadow.Seen(v.vehicleId, v.tripId, v.routeId, v.timestamp, v.lat, v.lon) }))
                // As JourneyService.askBoarding, a moment after the reading.
                if (shadow.wantsBoarding()) {
                    val at = fetched + 150
                    JourneyTrace.boarding(at, "БУЛ. ЦАРИГРАДСКО, ШОСЕ", due)
                    follow(shadow.onBoarding(at, "БУЛ. ЦАРИГРАДСКО, ШОСЕ", due))
                }
            }
            t += 1.0
        }
        JourneyTrace.end()
        val until = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < until && !(f.exists() && f.readText().contains("# end"))) Thread.sleep(20)
        val (start, recs) = journeys(f).single()
        val replayed = replay(start, recs).events.map { "Model @${clock(it.atMs)}: ${describe(it.event)}" }
        val logged = app.filter { it.startsWith("Model @") }
        lines += logged.size
        if (logged.any { "(by the boarding stop)" in it }) byStop++
        if (silent && logged.any { "withdrawn V;213" in it || "switched V;213" in it }) leftOffRoute++
        if (logged.isNotEmpty() && replayed == logged) same++
        else differ += "seed $seed\n  app:    $logged\n  replay: $replayed"
    }
    differ.forEach { println("FAIL $it") }
    println("$same of 40 journeys: the replay of the trace gives the app's lines ($lines lines; " +
        "$byStop taken by the boarding stop, $leftOffRoute left us off its route)")
    // The B and O lines must have been put to the test, not only written.
    val exercised = byStop > 0 && leftOffRoute > 0
    if (!exercised) println("FAIL the early rule or the off-route rule never decided anything")
    kotlin.system.exitProcess(if (same == 40 && exercised) 0 else 1)
}
