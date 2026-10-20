// A small simulator for RideModel: vehicles moving along real roads (shapes
// from the bundled feed), a phone's fixes with the noise, jumps and gaps of
// poor GPS, and readings of the feed every 30 s with each vehicle's latest
// report — what the app gives the model, made to order.
import bg.sofia.transit.service.RideModel
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

const val KY = 6_371_000.0 * Math.PI / 180.0
/** The simulated journeys start here (7 Oct 2026, 08:30 Sofia time). */
const val T0 = 1_791_350_000_000L

class Pt(val lat: Double, val lon: Double) {
    /** This point moved [north] and [east] metres. */
    fun shift(north: Double, east: Double) =
        Pt(lat + north / KY, lon + east / (KY * cos(Math.toRadians(lat))))
    fun dist(o: Pt) = RideModel.distance(lat, lon, o.lat, o.lon)
}

/** A road as a polyline, measured along. */
class Path(val pts: List<Pt>) {
    private val kx = KY * cos(Math.toRadians(pts[0].lat))
    val cum = DoubleArray(pts.size).also { c ->
        for (i in 1 until pts.size) c[i] = c[i - 1] + pts[i - 1].dist(pts[i])
    }
    val length get() = cum.last()

    fun at(s: Double): Pt {
        val x = s.coerceIn(0.0, length)
        var i = cum.indexOfLast { it <= x }.coerceIn(0, pts.size - 2)
        val seg = cum[i + 1] - cum[i]
        val w = if (seg == 0.0) 0.0 else (x - cum[i]) / seg
        return Pt(pts[i].lat + w * (pts[i + 1].lat - pts[i].lat), pts[i].lon + w * (pts[i + 1].lon - pts[i].lon))
    }

    /** How far along the road the point nearest [p] is. */
    fun along(p: Pt): Double {
        var best = 0.0; var bestD = Double.MAX_VALUE
        for (i in 0 until pts.size - 1) {
            val ax = pts[i].lon * kx; val ay = pts[i].lat * KY
            val dx = pts[i + 1].lon * kx - ax; val dy = pts[i + 1].lat * KY - ay
            val px = p.lon * kx - ax; val py = p.lat * KY - ay
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else ((px * dx + py * dy) / l2).coerceIn(0.0, 1.0)
            val d = hypot(px - t * dx, py - t * dy)
            if (d < bestD) { bestD = d; best = cum[i] + t * (cum[i + 1] - cum[i]) }
        }
        return best
    }

    fun reversed() = Path(pts.reversed())

    /** The same road [m] metres to the right of the way it runs (left if negative). */
    fun shifted(m: Double): Path = Path(pts.indices.map { i ->
        val a = pts[maxOf(0, i - 1)]; val b = pts[minOf(pts.size - 1, i + 1)]
        val dn = (b.lat - a.lat) * KY; val de = (b.lon - a.lon) * kx
        val l = hypot(dn, de).takeIf { it > 0 } ?: 1.0
        pts[i].shift(-de / l * m, dn / l * m)
    })
}

/** Movement along a road: driving at a speed to a point, or standing. Times in seconds. */
class Mover(val path: Path, startAlong: Double, startT: Double = 0.0) {
    private class Leg(val t0: Double, val t1: Double, val s0: Double, val s1: Double)
    private val legs = ArrayList<Leg>()
    private var t = startT
    private var s = startAlong
    private val firstT = startT

    fun drive(to: Double, mps: Double) = apply {
        val dt = kotlin.math.abs(to - s) / mps
        legs += Leg(t, t + dt, s, to); t += dt; s = to
    }
    fun stand(sec: Double) = apply { legs += Leg(t, t + sec, s, s); t += sec }
    /** When the plan so far ends. */
    val endT get() = t

    fun alongAt(time: Double): Double {
        if (time <= firstT) return legs.firstOrNull()?.s0 ?: s
        for (l in legs) if (time <= l.t1) {
            val w = if (l.t1 == l.t0) 1.0 else (time - l.t0) / (l.t1 - l.t0)
            return l.s0 + w * (l.s1 - l.s0)
        }
        return s
    }
    fun at(time: Double): Pt = path.at(alongAt(time))
}

/** A vehicle in the feed. */
class SimVehicle(
    val key: String, val tripId: String, val routeId: String,
    val pos: (Double) -> Pt?,
    val reportEvery: Double = 30.0,
    val phase: Double = 0.0,
    /** No reports within these spans of time (seconds). */
    val silent: List<ClosedFloatingPointRange<Double>> = emptyList(),
    /** Extra error of the report at a time, metres north. */
    val glitch: (Double) -> Double = { 0.0 }
)

/** The phone's receiver. */
class Gps(
    val sigma: Double = 3.0,
    val accuracy: Double = 6.0,
    /** On average one fix in this many jumps by [jumpM]; 0 for none. */
    val jumpOneIn: Int = 0,
    val jumpM: Double = 0.0,
    /** No fixes within these spans of time (seconds). */
    val gaps: List<ClosedFloatingPointRange<Double>> = emptyList(),
    val seed: Int = 1
)

class SimResult(val events: List<Pair<Double, RideModel.Event>>, val model: RideModel,
                val metres: List<Pair<Double, Map<String, Double>>>) {
    fun first(): RideModel.Event? = events.firstOrNull()?.second
    inline fun <reified E : RideModel.Event> all(): List<Pair<Double, E>> =
        events.filter { it.second is E }.map { it.first to it.second as E }
    /** The most metres this vehicle ever had. */
    fun peak(key: String) = metres.maxOfOrNull { it.second[key] ?: 0.0 } ?: 0.0
    /** Its metres at the last reading at or before [t]. */
    fun metresAt(key: String, t: Double) = metres.lastOrNull { it.first <= t }?.second?.get(key) ?: 0.0
}

class Sim(
    val ours: (Double) -> Pt,
    val vehicles: List<SimVehicle>,
    val until: Double,
    val gps: Gps = Gps(),
    val chosenRoute: String = "",
    /** The fix clock ahead of true time, ms. */
    val fixClockAheadMs: Long = 0L,
    /** The phone's clock ahead of true time, ms. */
    val phoneClockAheadMs: Long = 0L,
    /** Readings before this carry no Date header. */
    val dateFrom: Double = 0.0
) {
    fun run(): SimResult {
        val model = RideModel(chosenRoute)
        val rnd = Random(gps.seed)
        val vrnd = Random(gps.seed + 1000)
        fun gauss() = rnd.nextDouble().let { u -> kotlin.math.sqrt(-2 * kotlin.math.ln(maxOf(u, 1e-12))) *
            cos(2 * Math.PI * rnd.nextDouble()) }
        val events = ArrayList<Pair<Double, RideModel.Event>>()
        val metres = ArrayList<Pair<Double, Map<String, Double>>>()
        var t = 0.0
        while (t <= until) {
            if (gps.gaps.none { t in it }) {
                var p = ours(t).shift(gauss() * gps.sigma, gauss() * gps.sigma)
                if (gps.jumpOneIn > 0 && rnd.nextInt(gps.jumpOneIn) == 0) {
                    val a = rnd.nextDouble() * 2 * Math.PI
                    p = p.shift(gps.jumpM * cos(a), gps.jumpM * sin(a))
                }
                val trueMs = T0 + (t * 1000).toLong()
                model.onFix(RideModel.Fix(trueMs + fixClockAheadMs, trueMs + phoneClockAheadMs + 60,
                    p.lat, p.lon, gps.accuracy)).forEach { events += t to it }
            }
            if (t >= 5 && ((t - 5) % 30.0) == 0.0) {
                val reports = vehicles.mapNotNull { v ->
                    // The latest report at least 2 s before the reading.
                    val k = kotlin.math.floor((t - 2 - v.phase) / v.reportEvery)
                    val rt = v.phase + k * v.reportEvery
                    if (rt < 0 || v.silent.any { rt in it }) return@mapNotNull null
                    val p = v.pos(rt) ?: return@mapNotNull null
                    val q = p.shift(vrnd.nextDouble(-4.0, 4.0) + v.glitch(rt), vrnd.nextDouble(-4.0, 4.0))
                    RideModel.Report(v.key, v.tripId, v.routeId, T0 + (rt * 1000).toLong(), q.lat, q.lon)
                }
                val trueMs = T0 + (t * 1000).toLong()
                model.onReading(trueMs + phoneClockAheadMs + 300, if (t < dateFrom) 0L else trueMs / 1000 * 1000, reports)
                    .forEach { events += t to it }
                metres += t to model.leaders().associate { it.key to it.metres }
            }
            t += 1.0
        }
        return SimResult(events, model, metres)
    }
}

/** Roads and stops from the bundled feed, extracted by run_model_tests.sh. */
object Feed {
    lateinit var dir: File
    private val shapes by lazy {
        File(dir, "shapes.csv").readLines().groupBy({ it.split(",")[0] }) {
            val p = it.split(","); Pt(p[1].toDouble(), p[2].toDouble())
        }
    }
    private val stops by lazy {
        File(dir, "stops.csv").readLines().associate {
            val p = it.split(","); p[0] to Pt(p[1].toDouble(), p[2].toDouble())
        }
    }
    fun path(shape: String) = Path(shapes.getValue(shape))
    fun stop(id: String) = stops.getValue(id)
}
