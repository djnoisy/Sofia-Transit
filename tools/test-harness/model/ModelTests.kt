// RideModel (service/RideModel.kt): which vehicle we are in and when we get
// off, by metres travelled together — on simulated journeys along the real
// roads of 213 and 304 towards ЦЕНТРАЛНА ГАРА (feed of 5 Oct 2026), where on
// 6 Oct 2026 the app took 304 for the 213 the passenger was in, and on the
// journey traces recorded on the phone (TRACE_DIR).
import bg.sofia.transit.service.RideModel
import bg.sofia.transit.service.RideModel.Event
import java.io.File

var fails = 0
var passes = 0
fun check(name: String, ok: Boolean, detail: () -> String = { "" }) {
    if (ok) { passes++; println("PASS $name") } else { fails++; println("FAIL $name ${detail()}") }
}

/** The 6 Oct 2026 journey and its variants. */
class Journey(
    val startAtPliska: Boolean = false,
    val alight: Boolean = true,
    val gps: Gps = Gps(),
    val glitch213: Boolean = false,
    val chosen: String = "A85",
    val dwellOrlov: Double = 20.0,
    val phase213: Double = 7.0,
    val silent213: List<ClosedFloatingPointRange<Double>> = emptyList(),
    /** 213 is not in the feed at all: we ride a vehicle that never reports. */
    val report213: Boolean = true,
    val standAtPliska: Double = 30.0,
    val fixAheadMs: Long = 0L,
    val phoneAheadMs: Long = 0L
) {
    val p213 = Feed.path("A4508")
    val p304 = Feed.path("A2791")
    val sAnna = p213.along(Feed.stop("A1196"))
    val sPliska = p213.along(Feed.stop("A2327"))
    val sOrlov = p213.along(Feed.stop("A1290"))
    val sVoenna = p213.along(Feed.stop("A0440"))
    val qPliska = p304.along(Feed.stop("A2327"))
    val qOrlov = p304.along(Feed.stop("A1289"))
    val qIgnatiev = p304.along(Feed.stop("A1914"))

    var tLeavePliska = 0.0; var tArriveOrlov = 0.0; var tLeaveOrlov = 0.0
    val bus213: Mover = (if (startAtPliska) Mover(p213, sPliska)
        else Mover(p213, sAnna - 300).drive(sAnna, 10.0).stand(20.0).drive(sPliska, 12.0))
        .stand(standAtPliska).also { tLeavePliska = it.endT }
        .drive(sOrlov, 11.0).also { tArriveOrlov = it.endT }
        .stand(dwellOrlov).also { tLeaveOrlov = it.endT }
        .drive(sVoenna, 10.0).stand(20.0).drive(sVoenna + 1500, 11.0)
    // 304 stands at ХОТЕЛ ПЛИСКА as we arrive and leaves a second after us:
    // 11 m behind on the road the two lines share as far as ПЛ. ОРЛОВ МОСТ.
    val bus304 = Mover(p304, qPliska).stand(tLeavePliska + 1.0)
        .drive(qOrlov, 11.0).stand(20.0).drive(qIgnatiev, 10.0).stand(20.0).drive(qIgnatiev + 1500, 11.0)
    // A bus the other way, passing us between УМБАЛ СВ. АННА and ХОТЕЛ ПЛИСКА.
    val pBack = p213.reversed().shifted(8.0)
    val oncoming = Mover(pBack, pBack.length - (sAnna + 900) - 1500).drive(pBack.length - sAnna + 500, 13.0)

    val stopSpot: Pt = p213.at(sOrlov).shift(4.0, 4.0)
    fun ours(t: Double): Pt =
        if (alight && t >= tArriveOrlov + 8) stopSpot else bus213.at(t).shift(1.0, 1.0)

    // The first report of 213 after leaving ХОТЕЛ ПЛИСКА, 50 m out.
    val glitchFrom = tLeavePliska + 20

    fun vehicles(extra: List<SimVehicle> = emptyList()) = listOfNotNull(
        if (report213) SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus213.at(it) }, phase = phase213,
            silent = silent213,
            glitch = { rt -> if (glitch213 && rt >= glitchFrom && rt < glitchFrom + 30) 50.0 else 0.0 })
        else null,
        SimVehicle("V304", "A217-A2791-4-5-1", "A217", { bus304.at(it) }, phase = 19.0),
        SimVehicle("VBACK", "A85-A4509-1-1-1", "A85", { oncoming.at(it) }, phase = 3.0)
    ) + extra

    fun sim(until: Double = tLeaveOrlov + 420, extra: List<SimVehicle> = emptyList()) =
        Sim({ ours(it) }, vehicles(extra), until, gps, chosen, fixAheadMs, phoneAheadMs)
}

fun ident(r: SimResult) = r.all<Event.Identified>()
fun switched(r: SimResult) = r.all<Event.Switched>()
fun withdrawn(r: SimResult) = r.all<Event.Withdrawn>()
fun alighted(r: SimResult) = r.all<Event.Alighted>()
fun story(r: SimResult) = r.events.joinToString("; ") { "${it.first.toInt()}s ${describe(it.second)}" }

/** The expectations of an ordinary journey ending at ПЛ. ОРЛОВ МОСТ. */
fun expectRideAndGetOff(name: String, j: Journey, r: SimResult, identifyBy: Double = 400.0) {
    val id = ident(r).firstOrNull()
    check("$name: 213 identified", id?.second?.key == "V213" && id.first <= identifyBy) { story(r) }
    check("$name: never 304, never withdrawn", switched(r).isEmpty() && withdrawn(r).isEmpty()) { story(r) }
    val off = alighted(r).firstOrNull()
    check("$name: got off 213 after it left ПЛ. ОРЛОВ МОСТ", off?.second?.key == "V213" &&
        off.first >= j.tLeaveOrlov + 20 && off.first <= j.tLeaveOrlov + 360) {
        "left ${j.tLeaveOrlov.toInt()}s; ${story(r)}" }
}

fun main(args: Array<String>) {
    Feed.dir = File(args[0])

    // ── Our place at a moment (RideModel.positionAt) ──
    run {
        // Riding at 12 m/s, a fix every 5 s scattered ±5 m; our place asked
        // 2 s before the newest fix, as for a report 2 s old.
        val p = Feed.path("A4508")
        val rnd = kotlin.random.Random(3)
        var worst = 0.0
        for (trial in 0 until 40) {
            val m = RideModel()
            val s0 = 3000.0 + trial * 200
            var t = 0.0
            while (t <= 60.0) {
                val truth = p.at(s0 + 12 * t)
                val q = truth.shift(rnd.nextDouble(-5.0, 5.0), rnd.nextDouble(-5.0, 5.0))
                m.onFix(RideModel.Fix(T0 + (t * 1000).toLong(), T0 + (t * 1000).toLong(), q.lat, q.lon, 8.0))
                t += 5.0
            }
            val ask = 58.0
            val got = m.positionAt(T0 + (ask * 1000).toLong())
            val err = got?.let { p.at(s0 + 12 * ask).dist(Pt(it.lat, it.lon)) } ?: 999.0
            worst = maxOf(worst, err)
        }
        check("our place on a fix every 5 s at 12 m/s, near the newest fix: within 15 m", worst <= 15.0) {
            "worst ${worst.toInt()} m" }
    }
    run {
        // Two fixes of seven 150 m out: our place is not pulled after them.
        val p = Feed.path("A4508")
        val m = RideModel()
        for (k in 0..6) {
            var q = p.at(3000.0 + 12 * k)
            if (k == 2 || k == 5) q = q.shift(150.0, 0.0)
            m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 6.0))
        }
        val got = m.positionAt(T0 + 3000L)
        val err = got?.let { p.at(3036.0).dist(Pt(it.lat, it.lon)) } ?: 999.0
        check("two fixes of seven 150 m out: our place within 10 m", err <= 10.0) { "${err.toInt()} m" }
    }

    // ── A report judged once our place is known ──
    run {
        // Fixes to 10 s, then a reading with a report at 14 s — our place
        // then not yet known; a fix at 15 s; the same report again.
        val p = Feed.path("A4508")
        val m = RideModel()
        fun fix(k: Int) { val q = p.at(3000.0 + 11 * k)
            m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 6.0)) }
        for (k in 0..10) fix(k)
        val q = p.at(3000.0 + 11 * 14)
        val rep = RideModel.Report("V", "A85-A4508-1", "A85", T0 + 14_000L, q.lat, q.lon)
        m.onReading(T0 + 16_000L, T0 + 16_000L, listOf(rep))
        val before = m.vehicle("V")?.lastDistance
        for (k in 11..17) fix(k)
        m.onReading(T0 + 18_000L, T0 + 18_000L, listOf(rep))
        val after = m.vehicle("V")?.lastDistance
        check("a report from before our place was known: judged at the next reading",
            before == null && after != null && after < 10.0) { "before $before after $after" }
    }

    // ── The clock difference learnt late: what was taken in moves with it ──
    run {
        // Fixes stamped 4 s ahead; no Date header in the first readings, then
        // one; the same reports come again in it. At 6 m/s, so that the
        // vehicle is beside us (24 m) even before the difference is known.
        val p = Feed.path("A4508")
        val m = RideModel()
        var lastReports = emptyList<RideModel.Report>()
        var metresBefore = 0.0
        var metresAfter = 0.0
        for (t in 0..200) {
            val q = p.at(3000.0 + 6 * t)
            m.onFix(RideModel.Fix(T0 + t * 1000L + 4000, T0 + t * 1000L + 4000, q.lat, q.lon, 6.0))
            if (t >= 5 && (t - 5) % 30 == 0) {
                val rt = t - 2
                val vq = p.at(3000.0 + 6 * rt)
                val reports = listOf(RideModel.Report("V", "A85-A4508-1", "A85", T0 + rt * 1000L, vq.lat, vq.lon))
                if (t < 125) {
                    m.onReading(T0 + t * 1000L + 4300, 0L, reports)
                    lastReports = reports
                } else if (t == 125) {
                    metresBefore = m.vehicle("V")?.metres ?: 0.0
                    // The previous reading's reports again, now with the server's time.
                    m.onReading(T0 + t * 1000L + 4300, T0 + t * 1000L, lastReports)
                    metresAfter = m.vehicle("V")?.metres ?: 0.0
                }
            }
        }
        check("the clock difference learnt late: a report already taken in is not taken again",
            m.clockOffsetMs in 3_000L..4_500L && metresBefore > 300 && metresAfter == metresBefore) {
            "offset ${m.clockOffsetMs}, metres ${metresBefore.toInt()} → ${metresAfter.toInt()}" }
    }

    // ── The journey of 6 Oct 2026 ──
    run {
        val j = Journey()
        val r = j.sim().run()
        expectRideAndGetOff("6.10", j, r)
        check("6.10: 304 never near 213's metres", r.peak("V304") < r.metresAt("V213", j.tLeaveOrlov) - 1000) {
            "304 ${r.peak("V304").toInt()}, 213 ${r.metresAt("V213", j.tLeaveOrlov).toInt()}" }
        check("6.10: the oncoming bus earns at most 60 m", r.peak("VBACK") <= 60.0) { "${r.peak("VBACK")}" }
        // Two minutes from the last vehicle speed, which the 10 s window over
        // smoothed places still sees for a few seconds after the bus stops.
        check("6.10: getting off takes 2 min standing", alighted(r).firstOrNull()?.let {
            it.first >= j.tArriveOrlov + 120 } == true) { "arrived ${j.tArriveOrlov.toInt()}s; ${story(r)}" }
    }
    run {
        val j = Journey(glitch213 = true)
        expectRideAndGetOff("6.10, one report of 213 50 m out", j, j.sim().run())
    }

    // ── The bus's own position poor: one report in three 45 m out ──
    run {
        val j = Journey()
        val v = j.vehicles().map { sv ->
            if (sv.key != "V213") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                glitch = { rt -> if (((rt - sv.phase) / 30).toInt() % 3 == 1) 45.0 else 0.0 })
        }
        val r = Sim({ j.ours(it) }, v, j.tLeaveOrlov + 420, j.gps, j.chosen).run()
        expectRideAndGetOff("one report of 213 in three 45 m out", j, r)
        val travelled = j.bus213.alongAt(j.tArriveOrlov) - j.bus213.alongAt(0.0)
        check("one report of 213 in three 45 m out: its metres still count across them",
            r.metresAt("V213", j.tArriveOrlov) >= 0.8 * travelled) {
            "${r.metresAt("V213", j.tArriveOrlov).toInt()} of ${travelled.toInt()} m" }
    }

    // ── One report of 213 150 m out, past ХОТЕЛ ПЛИСКА with 304 beside us ──
    run {
        val j = Journey()
        val wild = j.tLeavePliska + 80
        val v = j.vehicles().map { sv ->
            if (sv.key != "V213") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                glitch = { rt -> if (rt >= wild && rt < wild + 30) 150.0 else 0.0 })
        }
        val r = Sim({ j.ours(it) }, v, j.tLeaveOrlov + 420, j.gps, j.chosen).run()
        expectRideAndGetOff("one report of 213 150 m out", j, r)
    }

    // ── Started at ХОТЕЛ ПЛИСКА beside 304: a tie, broken by the chosen line ──
    run {
        val j = Journey(startAtPliska = true, alight = false, chosen = "A217")
        val r = j.sim(until = j.tLeaveOrlov + 200).run()
        check("from ХОТЕЛ ПЛИСКА, 304 chosen: 304 taken first (tie, chosen line)",
            ident(r).firstOrNull()?.second?.key == "V304") { story(r) }
        val sw = switched(r).firstOrNull()
        check("from ХОТЕЛ ПЛИСКА, 304 chosen: 213 takes over where the roads part",
            sw?.second?.toKey == "V213" && sw.first >= j.tArriveOrlov - 30 && sw.first <= j.tLeaveOrlov + 120) {
            "arrive ${j.tArriveOrlov.toInt()}s leave ${j.tLeaveOrlov.toInt()}s; ${story(r)}" }
        check("from ХОТЕЛ ПЛИСКА, 304 chosen: staying aboard, no getting off", alighted(r).isEmpty()) { story(r) }
        check("from ХОТЕЛ ПЛИСКА, 304 chosen: 213 at the end", r.model.ours?.key == "V213")
    }
    run {
        val j = Journey(startAtPliska = true, alight = false, chosen = "A85")
        val r = j.sim(until = j.tLeaveOrlov + 200).run()
        check("from ХОТЕЛ ПЛИСКА, 213 chosen: 213, and nothing else",
            r.events.size == 1 && ident(r).single().second.key == "V213") { story(r) }
    }
    run {
        // The same with one report of 213 50 m out: the tie must not tip to 304.
        val j = Journey(startAtPliska = true, alight = false, chosen = "A85", glitch213 = true)
        val r = j.sim(until = j.tLeaveOrlov + 200).run()
        check("from ХОТЕЛ ПЛИСКА, 213 chosen, one report 50 m out: still only 213",
            r.events.size == 1 && ident(r).single().second.key == "V213") { story(r) }
    }

    // ── Ours loses its claim ──
    run {
        // 304 taken first (chosen, tie at ХОТЕЛ ПЛИСКА), then silent for good;
        // we ride 213 on past ПЛ. ОРЛОВ МОСТ. After 5 min of silence its
        // claim lapses and 213, beside us all along, takes over.
        val j0 = Journey(startAtPliska = true, alight = false, chosen = "A217")
        val j = Journey(startAtPliska = true, alight = false, chosen = "A217")
        val v = j.vehicles().map { sv ->
            if (sv.key != "V304") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                silent = listOf(70.0..1e9))
        }
        val r = Sim({ j.ours(it) }, v, j0.tLeaveOrlov + 300, j.gps, j.chosen).run()
        val sw = switched(r).firstOrNull()
        check("ours silent 5 min, 213 beside us: 213 takes over once the silence has lasted",
            ident(r).firstOrNull()?.second?.key == "V304" && sw?.second?.toKey == "V213" && sw.first >= 49 + 300) {
            story(r) }
    }
    run {
        // A bus of the chosen line runs beside us 1 km and is taken first,
        // then keeps 60 m ahead — neither beside us nor clearly apart — while
        // 213, the one we are in, stays beside us.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val ride = Mover(p, s0).drive(s0 + 4000, 11.0)
        val ahead = { t: Double -> p.at(s0 + 11 * t + (if (t < 100) 0.0 else minOf(60.0, (t - 100) * 2))) }
        val r = Sim({ ride.at(it) }, listOf(
            SimVehicle("V213", "A85-A4508-2-6-1", "A85", { ride.at(it) }, phase = 7.0),
            SimVehicle("VX", "A99-A1-1-1-1", "A99", ahead, phase = 4.0)
        ), until = 340.0, chosenRoute = "A99").run()
        val sw = switched(r).firstOrNull()
        check("ours keeps 60 m ahead over 2 min while 213 stays beside us: 213 takes over",
            ident(r).firstOrNull()?.second?.key == "VX" && sw?.second?.toKey == "V213") { story(r) }
    }

    // ── Fix stamps out of step ──
    run {
        // The very first fix stamped 10 min ahead: it must not shut out the rest.
        val m = RideModel()
        val p = Feed.path("A4508")
        for (k in 0..40) {
            val q = p.at(3000.0 + 11 * k)
            val stamp = if (k == 0) T0 + 600_000L else T0 + k * 1000L
            m.onFix(RideModel.Fix(stamp, T0 + k * 1000L, q.lat, q.lon, 6.0))
        }
        val got = m.positionAt(T0 + 35_000L)
        val err = got?.let { p.at(3000.0 + 11 * 35).dist(Pt(it.lat, it.lon)) } ?: 999.0
        check("the first fix stamped 10 min ahead: the fixes after it still taken", err <= 10.0) { "${err.toInt()} m" }
    }
    run {
        // Eight network fixes on a phone clock 8 s fast, then satellite fixes:
        // most fixes move to the other clock, and the judgement follows.
        val m = RideModel()
        val p = Feed.path("A4508")
        for (k in 0..60) {
            val q = p.at(3000.0 + 11 * k)
            val phone = T0 + k * 1000L + 8000
            val stamp = if (k < 8) phone else T0 + k * 1000L
            m.onFix(RideModel.Fix(stamp, phone, q.lat, q.lon, 6.0))
        }
        val got = m.positionAt(T0 + 50_000L)
        val err = got?.let { p.at(3000.0 + 11 * 50).dist(Pt(it.lat, it.lon)) } ?: 999.0
        check("network fixes on a fast phone clock, then satellite ones: the satellite ones taken", err <= 10.0) {
            "${err.toInt()} m" }
    }

    // ── A vehicle gone silent beside us is no heir ──
    run {
        // We ride a vehicle the feed does not have. V (the chosen line) and B
        // run with us; B stops reporting at 200 s; at 300 s V pulls away
        // while we go on. B, last heard beside us minutes before, must not
        // take over: V is withdrawn.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val ride = Mover(p, s0).drive(s0 + 6000, 11.0)
        val v = Mover(p, s0).drive(s0 + 3300, 11.0).drive(s0 + 9000, 20.0)
        val r = Sim({ ride.at(it) }, listOf(
            SimVehicle("V", "A85-A4508-2-6-1", "A85", { v.at(it) }, phase = 7.0),
            SimVehicle("B", "A99-A1-1-1-1", "A99", { ride.at(it) }, phase = 17.0, silent = listOf(200.0..1e9))
        ), until = 480.0, chosenRoute = "A85").run()
        check("a vehicle silent beside us for minutes: no heir when ours pulls away",
            ident(r).firstOrNull()?.second?.key == "V" && switched(r).isEmpty() && withdrawn(r).size == 1) { story(r) }
    }

    // ── At the first choice, a vehicle silent for minutes has no say ──
    run {
        // S runs beside us 30 s at a crawl (120 m), then falls silent; five
        // minutes on, R joins us. S's old metres must not decide the choice.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val ride = Mover(p, s0).drive(s0 + 280, 4.0).drive(s0 + 5000, 11.0)
        val r = Sim({ ride.at(it) }, listOf(
            SimVehicle("S", "A99-A1-1-1-1", "A99", { ride.at(it) }, phase = 7.0, silent = listOf(40.0..1e9)),
            SimVehicle("R", "A85-A4508-2-6-1", "A85", { t -> if (t < 300) null else ride.at(t) }, phase = 13.0)
        ), until = 420.0, chosenRoute = "A99").run()
        check("a vehicle silent for minutes has no say at the first choice: R taken",
            ident(r).singleOrNull()?.second?.key == "R") { story(r) }
    }

    // ── A bus that came with us stands at the stop where we get off ──
    run {
        // VX runs 10 m behind 213 for 1.5 km; both stop; we get off; 213
        // leaves after 20 s, VX stands 5 min — beside us as 213 is judged
        // gone. Getting off, not VX taking over.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val bus = Mover(p, s0).drive(s0 + 1500, 11.0).stand(20.0).drive(s0 + 4000, 11.0)
        val vx = Mover(p, s0 - 10).drive(s0 + 1490, 11.0).stand(300.0).drive(s0 + 4000, 11.0)
        val tArrive = 1500 / 11.0
        val spot = p.at(s0 + 1500).shift(4.0, 4.0)
        val r = Sim({ t -> if (t < tArrive + 8) bus.at(t) else spot }, listOf(
            SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus.at(it) }, phase = 7.0),
            SimVehicle("VX", "A99-A1-1-1-1", "A99", { vx.at(it) }, phase = 21.0)
        ), until = tArrive + 400, chosenRoute = "A85").run()
        check("a bus that came with us stands at our stop: we got off 213, VX not taken",
            switched(r).isEmpty() && alighted(r).singleOrNull()?.second?.key == "V213") { story(r) }
    }

    // ── Getting off seen only after more than our kept track ──
    run {
        // After 213 leaves we run 80 m to and fro at the stop now and then
        // for 6 min (vehicle speed, but going nowhere), then stand. Getting
        // off is seen long after the moment 213 left has dropped out of our
        // kept track: where we were then must have been kept.
        val j = Journey(alight = true)
        val spot = j.stopSpot
        val p = j.p213
        val back = { t: Double ->
            val since = t - (j.tArriveOrlov + 8)
            if (since < 0) j.ours(t)
            else if (since < 360 && (since % 120) < 40) {
                // 80 m along the road and back, in 40 s.
                val x = (since % 120)
                val d = if (x < 20) x * 4.0 else (40 - x) * 4.0
                p.at(p.along(spot) + d).shift(4.0, 4.0)
            } else spot
        }
        val r = Sim(back, j.vehicles(), j.tLeaveOrlov + 900, j.gps, j.chosen).run()
        val off = alighted(r).firstOrNull()
        check("running 80 m to and fro at the stop for 6 min, then standing: got off 213",
            withdrawn(r).isEmpty() && off?.second?.key == "V213" && off.first >= j.tArriveOrlov + 8 + 360) { story(r) }
    }

    // ── A bus of the chosen line the other way, at the first choice ──
    run {
        val bad = ArrayList<String>()
        for (phase in 0 until 30 step 3) {
            val p = Feed.path("A4508")
            val s0 = p.along(Feed.stop("A1196")) - 300
            val ride = Mover(p, s0).drive(s0 + 3000, 11.0)
            val back = p.reversed().shifted(8.0)
            // Passing us about 50 s in, as 213 earns its first 150 m.
            val meet = back.length - (s0 + 11 * 50)
            val other = Mover(back, meet - 11 * 50).drive(meet + 11 * 200, 11.0)
            val r = Sim({ ride.at(it) }, listOf(
                SimVehicle("V213", "A85-A4508-2-6-1", "A85", { ride.at(it) }, phase = 7.0),
                SimVehicle("VBACK", "A85-A4509-1-1-1", "A85", { other.at(it) }, phase = phase.toDouble())
            ), until = 200.0, chosenRoute = "A85").run()
            if (ident(r).firstOrNull()?.second?.key != "V213" || r.events.size != 1) bad += "phase $phase: ${story(r)}"
        }
        check("the chosen line's bus the other way, passing at the first choice: never taken", bad.isEmpty()) {
            bad.joinToString(" | ") }
    }

    // ── Our fixes lost just as we move on ──
    run {
        // We ride a vehicle the feed does not have; VX, beside us 1.5 km, is
        // taken for it. At a light VX goes after 10 s while we wait 50 s; as
        // we move on, the fixes stop for 3 min. No fixes are no evidence of
        // standing: not taken for getting off.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val tLight = 1500 / 11.0
        val ride = Mover(p, s0).drive(s0 + 1500, 11.0).stand(50.0).drive(s0 + 5000, 11.0)
        val vx = Mover(p, s0).drive(s0 + 1500, 11.0).stand(10.0).drive(s0 + 6000, 11.0)
        val r = Sim({ ride.at(it) }, listOf(
            SimVehicle("VX", "A99-A1-1-1-1", "A99", { vx.at(it) }, phase = 9.0)
        ), until = tLight + 300, gps = Gps(gaps = listOf((tLight + 50)..(tLight + 230))), chosenRoute = "A99").run()
        check("fixes lost as we move on after ours left: not taken for getting off",
            ident(r).firstOrNull()?.second?.key == "VX" && alighted(r).isEmpty()) { story(r) }
    }

    // ── Apart, then reports 60 m out, then apart again: not "two in a row" ──
    run {
        val j = Journey()
        val k0 = j.tLeavePliska + 40
        val v = j.vehicles().map { sv ->
            if (sv.key != "V213") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                glitch = { rt -> when {
                    rt >= k0 && rt < k0 + 30 -> 150.0
                    rt >= k0 + 30 && rt < k0 + 120 -> 60.0
                    rt >= k0 + 120 && rt < k0 + 150 -> 150.0
                    else -> 0.0 } })
        }
        val r = Sim({ j.ours(it) }, v, j.tLeaveOrlov + 420, j.gps, j.chosen).run()
        expectRideAndGetOff("213 apart, 60 m out three times, apart again", j, r)
    }

    // ── A fix stamped 20 s ahead among the others ──
    run {
        val m = RideModel()
        val p = Feed.path("A4508")
        for (k in 0..40) {
            val q = p.at(3000.0 + 11 * k)
            val stamp = if (k == 20) T0 + (k + 20) * 1000L else T0 + k * 1000L
            m.onFix(RideModel.Fix(stamp, T0 + k * 1000L, q.lat, q.lon, 6.0))
        }
        val got = m.positionAt(T0 + 35_000L)
        val err = got?.let { p.at(3000.0 + 11 * 35).dist(Pt(it.lat, it.lon)) } ?: 999.0
        check("one fix stamped 20 s ahead: left out, the fixes after it still taken", err <= 10.0) { "${err.toInt()} m" }
    }

    // ── An oncoming bus beside us at a red light ──
    run {
        val p = Feed.path("A4508")
        val sAnna = p.along(Feed.stop("A1196"))
        val sLight = sAnna + 800
        val bus = Mover(p, sAnna - 300).drive(sLight, 11.0).stand(90.0).drive(sLight + 1500, 11.0)
        val back = p.reversed().shifted(8.0)
        val bLight = back.length - sLight
        val tStop = (sLight - (sAnna - 300)) / 11.0
        // It reaches the light as we do, stands 90 s beside us, then goes its way.
        val other = Mover(back, bLight - tStop * 11.0).drive(bLight, 11.0).stand(90.0).drive(bLight + 1500, 11.0)
        val r = Sim({ bus.at(it) }, listOf(
            SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus.at(it) }, phase = 7.0),
            SimVehicle("VBACK", "A85-A4509-1-1-1", "A85", { other.at(it) }, phase = 11.0)
        ), until = tStop + 90 + 120, chosenRoute = "A85").run()
        check("red light: the oncoming bus beside us 90 s earns at most 60 m", r.peak("VBACK") <= 60.0) {
            "${r.peak("VBACK")}" }
        check("red light: only 213 identified", r.events.size == 1 && ident(r).single().second.key == "V213") {
            story(r) }
    }

    // ── A bus on a parallel street, 50 m off, the whole way ──
    for ((label, g) in listOf("good GPS" to Gps(), "poor GPS (±10 m, accuracy 25 m)" to Gps(sigma = 10.0, accuracy = 25.0, seed = 7))) {
        val j = Journey(gps = g)
        val parallel = j.p213.shifted(50.0)
        // Keeps abreast of us: its place along its own road follows ours.
        val r = j.sim(extra = listOf(SimVehicle("VPAR", "A99-A1-1-1-1", "A99",
            { t -> parallel.at(parallel.along(j.bus213.at(t))) }, phase = 23.0))).run()
        check("parallel street, $label: never taken", r.events.none {
            (it.second as? Event.Identified)?.key == "VPAR" || (it.second as? Event.Switched)?.toKey == "VPAR" }) {
            story(r) }
        check("parallel street, $label: far fewer metres than 213", r.peak("VPAR") < r.peak("V213") / 4) {
            "VPAR ${r.peak("VPAR").toInt()} V213 ${r.peak("V213").toInt()}" }
    }

    // ── Poor positions: the owner's question of 7 Oct 2026 ──
    // label, the receiver, and how late after 213 leaves getting off may come.
    val gapsEvery = { step: Double -> (0..3000).map { k -> (k * step + 0.5)..(k * step + step - 0.5) } }
    val poor = listOf(
        Triple("scatter ±10 m, accuracy 25 m", { s: Int -> Gps(sigma = 10.0, accuracy = 25.0, seed = s) }, 360.0),
        Triple("scatter ±20 m, accuracy 29 m", { s: Int -> Gps(sigma = 20.0, accuracy = 29.0, seed = s) }, 360.0),
        Triple("a 120 m jump in 1 fix of 30", { s: Int -> Gps(sigma = 4.0, accuracy = 8.0, jumpOneIn = 30, jumpM = 120.0, seed = s) }, 360.0),
        Triple("40 s without fixes every 3 min", { s: Int -> Gps(gaps = (0..20).map { k -> (k * 180.0 + 60)..(k * 180.0 + 100) }, seed = s) }, 360.0),
        Triple("a fix only every 5 s", { s: Int -> Gps(sigma = 5.0, accuracy = 10.0, gaps = gapsEvery(5.0), seed = s) }, 360.0),
        Triple("a fix every 6 s, scatter ±10 m", { s: Int -> Gps(sigma = 10.0, accuracy = 25.0, gaps = gapsEvery(6.0), seed = s) }, 360.0),
        Triple("scatter, jumps and gaps at once", { s: Int -> Gps(sigma = 10.0, accuracy = 25.0, jumpOneIn = 30, jumpM = 120.0,
            gaps = (0..20).map { k -> (k * 180.0 + 60)..(k * 180.0 + 100) }, seed = s) }, 360.0),
        // Far beyond anything in the logs: never a wrong decision, but
        // getting off may come late, as wild fixes keep feigning speed.
        Triple("a 150 m jump in 1 fix of 8", { s: Int -> Gps(sigma = 5.0, accuracy = 10.0, jumpOneIn = 8, jumpM = 150.0, seed = s) }, 900.0)
    )
    // MODEL_SEEDS=30 for a longer run.
    val seeds = System.getenv("MODEL_SEEDS")?.toIntOrNull() ?: 5
    for ((label, gpsOf, late) in poor) {
        var ok = 0
        val bad = ArrayList<String>()
        for (seed in 1..seeds) {
            val j = Journey(gps = gpsOf(seed), glitch213 = true)
            val r = j.sim(until = j.tLeaveOrlov + late + 60).run()
            val id = ident(r).firstOrNull()
            val off = alighted(r).firstOrNull()
            val good = id?.second?.key == "V213" && switched(r).isEmpty() && withdrawn(r).isEmpty() &&
                off?.second?.key == "V213" && off.first >= j.tLeaveOrlov + 20 && off.first <= j.tLeaveOrlov + late
            if (good) ok++ else bad += "seed $seed: ${story(r)}"
        }
        check("poor positions, $label: 213, never 304, got off — $ok of $seeds", ok == seeds) { bad.joinToString(" | ") }
    }
    run {
        val j = Journey(gps = Gps(accuracy = 35.0))
        val r = j.sim().run()
        check("every fix vaguer than 30 m: the model decides nothing", r.events.isEmpty()) { story(r) }
    }

    // ── Getting off after a short stop, whatever the rhythm of the reports ──
    run {
        val bad = ArrayList<String>()
        for (phase in listOf(0.0, 5.0, 10.0, 15.0, 20.0, 25.0)) {
            val j = Journey(dwellOrlov = 8.0, phase213 = phase)
            val r = j.sim().run()
            val off = alighted(r).firstOrNull()
            if (off?.second?.key != "V213" || off.first > j.tLeaveOrlov + 360) bad += "phase $phase: ${story(r)}"
        }
        check("8 s stop, reports at any phase: getting off concluded", bad.isEmpty()) { bad.joinToString(" | ") }
    }

    // ── Staying aboard while the bus stands 10 min ──
    run {
        val j = Journey(alight = false, standAtPliska = 600.0)
        val r = j.sim(until = j.tLeavePliska - 10).run()
        check("aboard, standing 10 min: 213 kept, no getting off",
            r.model.ours?.key == "V213" && alighted(r).isEmpty() && withdrawn(r).isEmpty()) { story(r) }
    }

    // ── Our bus silent ──
    run {
        val j0 = Journey()
        val j = Journey(silent213 = listOf((j0.tArriveOrlov + 5)..1e9))
        val r = j.sim().run()
        check("213 silent once we got off: 213 still ours, nothing concluded — the backstop timers' case",
            ident(r).singleOrNull()?.second?.key == "V213" && r.model.ours?.key == "V213" &&
                alighted(r).isEmpty() && withdrawn(r).isEmpty() && switched(r).isEmpty()) { story(r) }
    }
    run {
        val j0 = Journey()
        val j = Journey(silent213 = listOf((j0.tLeavePliska + 10)..(j0.tArriveOrlov - 20)))
        val r = j.sim().run()
        check("213 silent from ХОТЕЛ ПЛИСКА nearly to ПЛ. ОРЛОВ МОСТ, 304 beside us: kept, got off",
            switched(r).isEmpty() && withdrawn(r).isEmpty() && alighted(r).singleOrNull()?.second?.key == "V213") {
            "304 ${r.peak("V304").toInt()} m; ${story(r)}" }
    }

    // ── In a vehicle that never reports, another taken for it ──
    // We ride a vehicle the feed does not have; another runs with us 1.5 km
    // and is taken for ours. Both stop at a stop; it leaves, ours stays.
    fun silentVehicle(stay: Double): SimResult {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 1500, 11.0).stand(stay).drive(s0 + 3000, 11.0)
        val other = Mover(p, s0).stand(11.0).drive(s0 + 1500, 11.0).stand(20.0).drive(s0 + 4000, 11.0)
        return Sim({ ride.at(it) }, listOf(
            SimVehicle("VOTHER", "A99-A1-1-1-1", "A99", { other.at(it) }, phase = 13.0)
        ), until = 10.0 + 1500 / 11.0 + stay + 120, chosenRoute = "A85").run()
    }
    run {
        val r = silentVehicle(stay = 60.0)
        check("in a silent vehicle, the other leaves us at a stop and we move on after 60 s: withdrawn",
            ident(r).firstOrNull()?.second?.key == "VOTHER" && withdrawn(r).size == 1 && alighted(r).isEmpty()) {
            story(r) }
    }
    run {
        // Known limit, as with the rule this replaces: standing over 2 min.
        val r = silentVehicle(stay = 180.0)
        check("known limit: in a silent vehicle standing 3 min after the other left, taken for getting off",
            alighted(r).size == 1) { story(r) }
    }

    // ── Clocks ──
    run {
        val j = Journey(fixAheadMs = 20_000, phoneAheadMs = 20_000)
        val r = j.sim().run()
        expectRideAndGetOff("fixes stamped by a phone clock 20 s fast", j, r)
        check("fixes stamped by a phone clock 20 s fast: the difference is measured",
            r.model.clockOffsetMs in 19_000L..20_500L) { "${r.model.clockOffsetMs}" }
    }
    run {
        // No Date header in the first readings: the difference is learnt late,
        // and what was taken in before must move with it.
        val j = Journey(fixAheadMs = 20_000, phoneAheadMs = 20_000)
        val r = Sim({ j.ours(it) }, j.vehicles(), j.tLeaveOrlov + 420, j.gps, j.chosen, 20_000, 20_000,
            dateFrom = j.tLeavePliska).run()
        val id = ident(r).firstOrNull()
        check("fixes on a phone clock 20 s fast, the server's time known only later: 213, got off",
            id?.second?.key == "V213" && switched(r).isEmpty() && withdrawn(r).isEmpty() &&
                alighted(r).singleOrNull()?.second?.key == "V213") { story(r) }
        val travelled = j.bus213.alongAt(j.tArriveOrlov) - j.bus213.alongAt(0.0)
        check("…and no metres counted twice", r.metresAt("V213", j.tArriveOrlov) <= travelled + 50) {
            "${r.metresAt("V213", j.tArriveOrlov).toInt()} of ${travelled.toInt()} m" }
    }
    run {
        val j = Journey(phoneAheadMs = 20_000)
        val r = j.sim().run()
        expectRideAndGetOff("phone clock 20 s fast, fixes on satellite time", j, r)
        check("phone clock 20 s fast, fixes on satellite time: nothing to correct",
            r.model.clockOffsetMs == 0L) { "${r.model.clockOffsetMs}" }
    }

    // ── Recorded journeys ──
    val traces = System.getenv("TRACE_DIR")?.let { File(it) }
    if (traces != null && traces.isDirectory) {
        for (f in traces.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
            for ((start, lines) in journeys(f)) {
                val rp = replay(start, lines)
                println("TRACE ${f.name}: " + rp.events.joinToString("; ") { "${clock(it.atMs)} ${describe(it.event)}" })
                expectations[f.name]?.invoke(rp)
            }
        }
    } else println("(no TRACE_DIR: recorded journeys not replayed)")

    println("$passes passed, $fails failed")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}

/** What is known to have happened on recorded journeys, by file name. */
val expectations: Map<String, (Replayed) -> Unit> = mapOf(
    // 7 Oct 2026: 76 from АЛ. МАЛИНОВ (213 chosen), off at ПЛ. ОРЛОВ МОСТ at
    // 09:01; 76 left about 09:01:40 and was over 100 m away from 09:02:25.
    "2026-10-07_76.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("7.10 trace: 76 identified by 08:44:30", (id?.event as? Event.Identified)?.routeId == "A81" &&
            clock(id.atMs) <= "08:44:30") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("7.10 trace: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("7.10 trace: got off between 09:02:30 and 09:04:00", off?.event is Event.Alighted &&
            clock(off.atMs) in "09:02:30".."09:04:00") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A81" }
        check("7.10 trace: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 7 Oct 2026, evening: tram 22 (22 chosen, tracking started while waiting)
    // from УЛ. ОПЪЛЧЕНСКА, off at ЦЕНТРАЛНИ ХАЛИ about 18:19; the tram left
    // about 18:19:30 and was over 100 m away from 18:19:56. Its reports come
    // every 30 s, so the second after departure (18:15:26) is the first that
    // can make 150 m together; the app took it at 18:14:57 by its
    // boarding-stop check, which the model does not have.
    "2026-10-07_22.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("7.10 tram 22: 22 identified by 18:15:30", (id?.event as? Event.Identified)?.routeId == "TM22" &&
            clock(id.atMs) <= "18:15:30") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("7.10 tram 22: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("7.10 tram 22: got off between 18:20:00 and 18:21:30", off?.event is Event.Alighted &&
            clock(off.atMs) in "18:20:00".."18:21:30") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "TM22" }
        check("7.10 tram 22: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 7 Oct 2026, evening: tram 12 (27 chosen, tracking started while waiting)
    // from ЦЕНТРАЛНИ ХАЛИ to УАСГ, aboard at the back of a long tram: its
    // reports were 13-30 m from us at the same moment throughout, at the
    // edge of BESIDE_M. Off at УАСГ about 18:37; the tram was over 100 m away
    // from 18:37:36. The trace ends at 18:38:47, when the app ended at the
    // chosen stop, a reading before the model's 2 min without vehicle speed
    // would be up — so no getting off here.
    "2026-10-07_12.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("7.10 tram 12: 12 identified by 18:25:35", (id?.event as? Event.Identified)?.routeId == "TM33" &&
            clock(id.atMs) <= "18:25:35") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("7.10 tram 12: nothing else", rp.events.size == 1) { rp.events.joinToString { describe(it.event) } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "TM33" }
        check("7.10 tram 12: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 7 Oct 2026, evening: bus 204 (72 chosen, tracking started while waiting)
    // from УЛ. ГРАФ ИГНАТИЕВ, off at ХОТЕЛ ПЛИСКА about 18:51:30; the bus left
    // about 18:51:50 and was over 100 m away from 18:52:04. Its reports were
    // 4-16 m from us. 305, at "0 m" in the app's log near ХОТЕЛ ПЛИСКА,
    // gains nothing. As with tram 22, the app took 204 at the first reading
    // (18:46:06) by its boarding-stop check, the model at the second.
    "2026-10-07_204.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("7.10 bus 204: 204 identified by 18:46:40", (id?.event as? Event.Identified)?.routeId == "A84" &&
            clock(id.atMs) <= "18:46:40") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("7.10 bus 204: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("7.10 bus 204: got off between 18:52:30 and 18:54:00", off?.event is Event.Alighted &&
            clock(off.atMs) in "18:52:30".."18:54:00") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A84" }
        check("7.10 bus 204: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 7 Oct 2026, evening: bus 76 (204 chosen, tracking started aboard just
    // after ХОТЕЛ ПЛИСКА) to МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ, off about 19:08:50;
    // the bus was over 100 m away from 19:09:09. 76 was 1-15 m from us
    // throughout; a 204 ran 119-500 m ahead. The app took 76 only at 19:00:41:
    // a 72 whose report was 49 and 80 s old stood at "0 m" by its age
    // correction and tied with it — at the same moment it was over 600 m off.
    "2026-10-07_76b.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("7.10 evening 76: 76 identified by 18:59:15", (id?.event as? Event.Identified)?.routeId == "A81" &&
            clock(id.atMs) <= "18:59:15") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("7.10 evening 76: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("7.10 evening 76: got off between 19:10:00 and 19:11:00", off?.event is Event.Alighted &&
            clock(off.atMs) in "19:10:00".."19:11:00") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A81" }
        check("7.10 evening 76: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    }
)
