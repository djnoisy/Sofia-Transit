// RideModel (service/RideModel.kt): which vehicle we are in and when we get
// off, by metres travelled together — on simulated journeys along the real
// roads of 213 and 304 towards ЦЕНТРАЛНА ГАРА (feed of 5 Oct 2026), where on
// 6 Oct 2026 the app took 304 for the 213 the passenger was in, and on the
// journey traces recorded on the phone (TRACE_DIR).
import bg.sofia.transit.service.RideModel
import bg.sofia.transit.service.RideShadow
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
    val phase304: Double = 19.0,
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
        SimVehicle("V304", "A217-A2791-4-5-1", "A217", { bus304.at(it) }, phase = phase304),
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
        // then not yet known; fixes from 11 s place it; the same report
        // again in the next reading is not taken in twice.
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
        val after = m.vehicle("V")?.lastDistance
        val marks = m.vehicle("V")?.history?.size
        m.onReading(T0 + 18_000L, T0 + 18_000L, listOf(rep))
        check("a report from before our place was known: judged once fixes place us, before the next reading",
            before == null && after != null && after < 10.0) { "before $before after $after" }
        check("…and the same report in the next reading not taken in again",
            m.vehicle("V")?.history?.size == marks) { "${m.vehicle("V")?.history?.size} vs $marks" }
    }

    // ── Reports that can never be placed hold nothing for long ──
    // (7 Oct 2026, evening 76: two vehicles gone silent repeated reports
    // from before tracking began at every reading.) A fix every second, V
    // beside us; S repeats one old report at every reading — from before our
    // first fix, or, once a 30 s gap in our fixes is over, from inside it
    // (V beside us only after the gap). V is taken as without S: the one from
    // before our track never holds the choice; the one in the gap ends the
    // runs once, not at every reading.
    run {
        val p = Feed.path("A4508")
        fun ride(stale: Long?, gap: IntRange?, besideFrom: Int = 0): Pair<Int, Event>? {
            val m = RideModel()
            var got: Pair<Int, Event>? = null
            for (t in 0..400) {
                val q = p.at(3000.0 + 11 * t)
                if (gap == null || t !in gap)
                    m.onFix(RideModel.Fix(T0 + t * 1000L, T0 + t * 1000L + 60, q.lat, q.lon, 6.0)).forEach { if (got == null) got = t to it }
                if (t >= 5 && (t - 5) % 30 == 0) {
                    val vq = p.at(3000.0 + 11 * (t - 3)).shift(if (t - 3 < besideFrom) 500.0 else 3.0, 0.0)
                    val reports = listOf(RideModel.Report("V", "T-V", "A85", T0 + (t - 3) * 1000L, vq.lat, vq.lon)) +
                        (stale?.takeIf { it < t * 1000L }?.let { listOf(RideModel.Report("S", "T-S", "A99", T0 + it, q.lat, q.lon)) }
                            ?: emptyList())
                    m.onReading(T0 + t * 1000L + 300, T0 + t * 1000L, reports).forEach { if (got == null) got = t to it }
                }
            }
            return got
        }
        val plain = ride(null, null)
        val before = ride(-20_000L, null)
        check("an old report from before our track, repeated: the choice as without it",
            plain != null && before?.first == plain.first && (before.second as? Event.Identified)?.key == "V") { "$plain vs $before" }
        val inGap = ride(140_000L, 125..155, besideFrom = 160)
        val gapOnly = ride(null, 125..155, besideFrom = 160)
        check("an old report from a gap in our fixes, repeated: V taken, no later than a reading after it is without",
            inGap != null && (inGap.second as? Event.Identified)?.key == "V" && gapOnly != null &&
                inGap.first <= gapOnly.first + 30) { "$gapOnly vs $inGap" }
    }

    // ── A fix every 6 s and fresh reports: the report waits for the fixes after it ──
    // (9 Oct 2026: power saving, a fix every 5-6 s; reports 2-5 s old.)
    run {
        val p = Feed.path("A4508")
        val m = RideModel()
        val fromFix = ArrayList<Pair<Int, Event>>()
        val fromReading = ArrayList<Pair<Int, Event>>()
        for (t in 0..240) {
            val q = p.at(3000.0 + 11 * t)
            if (t % 6 == 0) m.onFix(RideModel.Fix(T0 + t * 1000L, T0 + t * 1000L + 60, q.lat, q.lon, 8.0))
                .forEach { fromFix += t to it }
            if (t >= 5 && (t - 5) % 30 == 0) {
                val rt = t - 3
                val vq = p.at(3000.0 + 11 * rt).shift(3.0, 0.0)
                m.onReading(T0 + t * 1000L + 300, T0 + t * 1000L,
                    listOf(RideModel.Report("V", "A85-A4508-1", "A85", T0 + rt * 1000L, vq.lat, vq.lon)))
                    .forEach { fromReading += t to it }
            }
        }
        val id = (fromFix + fromReading).filter { it.second is Event.Identified }
        check("a fix every 6 s, reports 3 s old: the vehicle identified, on the fix that placed its report",
            id.size == 1 && fromFix.any { it.second is Event.Identified }) {
            "fix: $fromFix reading: $fromReading" }
    }
    run {
        // The first fix after the reading too vague to count, the next one
        // places the report; then a stale copy of the feed brings an older
        // report while a newer one waits: the newer is the one judged.
        val p = Feed.path("A4508")
        val m = RideModel()
        fun fix(k: Int, acc: Double = 8.0) { val q = p.at(3000.0 + 11 * k)
            m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, acc)) }
        fun report(k: Int) = p.at(3000.0 + 11 * k).let {
            RideModel.Report("V", "A85-A4508-1", "A85", T0 + k * 1000L, it.lat, it.lon) }
        for (k in listOf(0, 6, 12)) fix(k)
        m.onReading(T0 + 18_000L, T0 + 18_000L, listOf(report(15)))
        fix(18, acc = 50.0)
        val afterVague = m.vehicle("V")?.lastReportMs
        fix(24)
        check("a report waiting: not placed by a vague fix, placed by the next",
            afterVague == 0L && m.vehicle("V")?.lastReportMs == T0 + 15_000L) {
            "after the vague fix $afterVague, then ${m.vehicle("V")?.lastReportMs}" }
        for (k in listOf(30, 36, 42)) fix(k)
        m.onReading(T0 + 48_000L, T0 + 48_000L, listOf(report(45)))
        m.onReading(T0 + 49_000L, T0 + 49_000L, listOf(report(41)))   // a stale copy of the feed, unplaced too
        fix(48); fix(54)
        check("a report waiting: a stale older one does not take its place",
            m.vehicle("V")?.lastReportMs == T0 + 45_000L) { "${m.vehicle("V")?.lastReportMs?.minus(T0)}" }
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
    // 213's reports as they reach us: 28 s old at the reading, and fresh — 3 s
    // and 5 s, as in the feed (9 Oct 2026: 2-5 s), where few fixes after the
    // report have come by the reading.
    val reportAges = listOf(7.0 to "reports 28 s old", 2.0 to "reports 3 s old", 0.0 to "reports 5 s old")
    for ((label, gpsOf, late) in poor) for ((phase, ages) in reportAges) {
        var ok = 0
        val bad = ArrayList<String>()
        for (seed in 1..seeds) {
            val j = Journey(gps = gpsOf(seed), glitch213 = true, phase213 = phase)
            val r = j.sim(until = j.tLeaveOrlov + late + 60).run()
            val id = ident(r).firstOrNull()
            val off = alighted(r).firstOrNull()
            val good = id?.second?.key == "V213" && switched(r).isEmpty() && withdrawn(r).isEmpty() &&
                off?.second?.key == "V213" && off.first >= j.tLeaveOrlov + 20 && off.first <= j.tLeaveOrlov + late
            if (good) ok++ else bad += "seed $seed: ${story(r)}"
        }
        check("poor positions, $label, $ages: 213, never 304, got off — $ok of $seeds", ok == seeds) { bad.joinToString(" | ") }
    }
    run {
        val j = Journey(gps = Gps(accuracy = 35.0))
        val r = j.sim().run()
        check("every fix vaguer than 30 m: the model decides nothing", r.events.isEmpty()) { story(r) }
    }

    // ── Two vehicles abreast, and fixes that cannot place every report ──
    // Boarding at ХОТЕЛ ПЛИСКА with 304 standing beside us (6 Oct 2026), 213
    // chosen; 60 pairs of report phases of 213 and 304. Wherever the fixes
    // can place only some reports, or begin mid-ride, 304 was taken (the
    // simulations of 9 Oct 2026, in brackets: before the reports waiting for
    // judgement held the choice, dropped ones ended the runs and runs abreast
    // were compared from the later beginning). Never 304 now; where anything
    // is taken, it is 213, and nothing replaces it.
    val phases = (0 until 30 step 3).flatMap { a -> (0 until 30 step 5).map { b -> a.toDouble() to b.toDouble() } }
    fun never304(label: String, startAtPliska: Boolean = true, mustTake: Boolean, gaps: (Double) -> List<ClosedFloatingPointRange<Double>>) {
        val bad = ArrayList<String>()
        for ((p213, p304) in phases) {
            val dep = Journey(startAtPliska = startAtPliska).tLeavePliska
            val j = Journey(startAtPliska = startAtPliska, gps = Gps(gaps = gaps(dep)), phase213 = p213, phase304 = p304)
            val r = j.sim().run()
            val id = ident(r)
            if ((mustTake && id.isEmpty()) || id.any { it.second.key != "V213" } || switched(r).isNotEmpty())
                bad += "213 at $p213, 304 at $p304: ${story(r)}"
        }
        check("$label: " + (if (mustTake) "213 taken" else "never 304") + ", nothing else", bad.isEmpty()) {
            bad.joinToString(" | ") }
    }
    for ((step, must) in listOf(6.0 to true, 7.0 to true, 8.0 to false, 9.0 to false, 10.0 to false))   // (3, 6, 22, 0, 0)
        never304("from ХОТЕЛ ПЛИСКА, a fix every ${step.toInt()} s", mustTake = must) { gapsEvery(step) }
    for (lost in listOf(0.0, 12.0, 24.0))   // (11, 15, 11)
        never304("from ХОТЕЛ ПЛИСКА, a fix every 6 s, one lost ${lost.toInt()} s after departure", mustTake = true) { dep ->
            val k = Math.round((dep + lost) / 6.0) * 6.0
            gapsEvery(6.0).filter { it.start < k - 1 || it.start > k + 1 } + listOf((k - 5.5)..(k + 5.5)) }
    never304("from ХОТЕЛ ПЛИСКА, a fix a second at the stop, every 10 s from departure", mustTake = false) { dep ->   // (17)
        gapsEvery(10.0).filter { it.start >= dep } }
    for (startAtPliska in listOf(true, false)) for (after in listOf(30.0, 60.0, 120.0)) {
        val from = if (startAtPliska) "from ХОТЕЛ ПЛИСКА" else "from УМБАЛ СВ. АННА"
        never304("$from, a fix every 10 s until ${after.toInt()} s after ХОТЕЛ ПЛИСКА, then every second",   // (35-38; 18-37)
            startAtPliska, mustTake = true) { dep -> gapsEvery(10.0).filter { it.endInclusive < dep + after } }
        never304("$from, tracking begun ${after.toInt()} s after ХОТЕЛ ПЛИСКА",   // (18; 21)
            startAtPliska, mustTake = true) { dep -> listOf(-1.0..(dep + after - 0.5)) }
    }
    // ── Others beside us for a moment, while two run abreast (review of 9 Oct 2026) ──
    // Tracking begun mid-ride, 213 and 304 abreast; a third vehicle C beside
    // us at one report — trailing 65 m behind otherwise, or passing the
    // other way. Taking the latest beginning of all runs, C switched the fair
    // comparison off and 304 was taken in up to 18 of 60 runs. 213 always.
    for (after in listOf(30.0, 60.0)) for (oncoming in listOf(false, true)) for (cDelay in listOf(35.0, 45.0, 55.0)) {
        val bad = ArrayList<String>()
        for ((p213, p304) in phases) {
            val dep = Journey(startAtPliska = true).tLeavePliska
            val ts = dep + after; val tC = ts + cDelay
            val j = Journey(startAtPliska = true, gps = Gps(gaps = listOf(-1.0..(ts - 0.5))), phase213 = p213, phase304 = p304)
            val c = if (oncoming) { val sMeet = j.bus213.alongAt(tC)
                SimVehicle("VC", "A99-x", "A99", { t -> j.p213.at(sMeet - 12.0 * (t - tC)).shift(6.0, 0.0) }, phase = tC % 30.0) }
            else SimVehicle("VC", "A99-x", "A99", { t ->
                j.p213.at(j.bus213.alongAt(t) - if (kotlin.math.abs(t - tC) < 0.5) 4.0 else 65.0) }, phase = tC % 30.0)
            val r = j.sim(extra = listOf(c)).run()
            val id = ident(r)
            if (id.size != 1 || id[0].second.key != "V213" || switched(r).isNotEmpty()) bad += "213 at $p213, 304 at $p304: ${story(r)}"
        }
        check("tracking begun ${after.toInt()} s after ХОТЕЛ ПЛИСКА, a vehicle " + (if (oncoming) "passing the other way" else "trailing") +
            " beside us once ${cDelay.toInt()} s after: 213 taken, nothing else", bad.isEmpty()) { bad.joinToString(" | ") }
    }
    // The chosen line X (not ours) beside us at exactly one report, 65 m
    // behind otherwise, tracking begun mid-ride: compared over a few seconds
    // X, with nothing, was within the tie of 213 and won it (6-11 of 900).
    // A stretch in which the leader has not gone beyond the tie now waits:
    // X never.
    for (after in listOf(30.0, 60.0)) {
        val bad = ArrayList<String>()
        for (p213 in 0 until 30 step 6) for (p304 in 0 until 30 step 5) for (pX in 0 until 30 step 2) {
            val dep = Journey(startAtPliska = true).tLeavePliska
            val ts = dep + after
            val j = Journey(startAtPliska = true, chosen = "A99", gps = Gps(gaps = listOf(-1.0..(ts - 0.5))),
                phase213 = p213.toDouble(), phase304 = p304.toDouble())
            fun firstAfter(ph: Double, t: Double): Double { var x = ph; while (x < t + 3.5) x += 30.0; return x }
            val tX = firstAfter(pX.toDouble(), maxOf(firstAfter(p213.toDouble(), ts), firstAfter(p304.toDouble(), ts)))
            val x = SimVehicle("VX", "A99-x", "A99", { t ->
                j.p213.at(j.bus213.alongAt(t) - if (kotlin.math.abs(t - tX) < 0.5) 4.0 else 65.0) }, phase = pX.toDouble())
            val r = j.sim(extra = listOf(x)).run()
            if (ident(r).any { it.second.key == "VX" }) bad += "213 at $p213, 304 at $p304, X at $pX: ${story(r)}"
        }
        check("tracking begun ${after.toInt()} s after ХОТЕЛ ПЛИСКА, the chosen line beside us at one report: never taken",
            bad.isEmpty()) { bad.joinToString(" | ") }
    }
    // A bus of the chosen line C comes beside us after we have left ХОТЕЛ
    // ПЛИСКА in 213 (it drove 45 m behind until then) and stays: 213's run
    // began over a report earlier, so the stretch compared is 213's, and 213
    // is taken. Compared from the latest beginning of all runs, the two were
    // level and C won on the tie. (Beside us within a report of 213's
    // beginning — 25 s after leaving — C may be taken: that head start is no
    // evidence, by design.)
    for (cAfter in listOf(40.0, 60.0)) {
        val bad = ArrayList<String>()
        for (p213 in 0 until 30 step 3) for (pC in 0 until 30 step 5) {
            val j = Journey(startAtPliska = true, chosen = "A99", phase213 = p213.toDouble())
            val tC = j.tLeavePliska + cAfter
            val c = SimVehicle("VC", "A99-c", "A99", { t -> j.p213.at(j.bus213.alongAt(t) - if (t < tC) 45.0 else 4.0) }, phase = pC.toDouble())
            val r = Sim({ j.ours(it) }, j.vehicles(listOf(c)).filter { it.key != "V304" }, j.tLeaveOrlov + 420, j.gps, j.chosen).run()
            if (ident(r).firstOrNull()?.second?.key != "V213") bad += "213 at $p213, C at $pC: ${story(r)}"
        }
        check("in 213 from ХОТЕЛ ПЛИСКА, a bus of the chosen line beside us from ${cAfter.toInt()} s after: 213 taken",
            bad.isEmpty()) { bad.joinToString(" | ") }
    }
    // Tracking begun mid-ride, 213 and 304 abreast; 304's second report after
    // that 50 m off — bridged over. The metres of its next report were
    // shared out from the bridged one, and 304 kept its head start (review
    // of 9 Oct 2026: 63 of 900 runs; 213 at 15 s and 304 at 27 s among them).
    run {
        val bad = ArrayList<String>()
        val pairs = (0 until 30 step 3).flatMap { a -> (0 until 30 step 3).map { b -> a to b } } + listOf(15 to 27)
        for ((a, b) in pairs) {
            val dep = Journey(startAtPliska = true).tLeavePliska
            val ts = dep + 30.0
            var k = b.toDouble(); while (k < ts - 3) k += 30.0
            val glitchAt = k + 30.0
            val j = Journey(startAtPliska = true, gps = Gps(gaps = listOf(-1.0..(ts - 0.5))), phase213 = a.toDouble(), phase304 = b.toDouble())
            val vs = j.vehicles().map { v -> if (v.key != "V304") v else SimVehicle(v.key, v.tripId, v.routeId, v.pos, phase = v.phase,
                glitch = { rt -> if (kotlin.math.abs(rt - glitchAt) < 0.5) 50.0 else 0.0 }) }
            val r = Sim({ j.ours(it) }, vs, j.tLeaveOrlov + 420, j.gps, j.chosen).run()
            if (ident(r).firstOrNull()?.second?.key != "V213") bad += "213 at $a, 304 at $b: ${story(r)}"
        }
        check("tracking begun mid-ride, one report of 304 50 m off: 213 taken", bad.isEmpty()) { bad.joinToString(" | ") }
    }
    // Waiting on foot at ХОТЕЛ ПЛИСКА: a bus S stands beside us, reports
    // once and goes silent (its trip ended); 213 comes at 60 s and leaves
    // with us at 80 s. S, its run begun long before 213's, was taken with
    // "0 m together" when its line was chosen. Never now.
    run {
        val p213 = Feed.path("A4508")
        val sPl = p213.along(Feed.stop("A2327"))
        val bad = ArrayList<String>()
        for (chosen in listOf("", "A99")) for (tS in listOf(10.0, 20.0, 25.0)) for (ph in 0 until 30 step 2) {
            val bus = Mover(p213, sPl - 600).drive(sPl, 10.0).stand(20.0).drive(sPl + 5000, 11.0)
            val stop = p213.at(sPl).shift(3.0, 3.0)
            val s = SimVehicle("VS", "A99-s", "A99", { _ -> p213.at(sPl + 8) }, phase = tS, silent = listOf((tS + 1)..1e9))
            val v213 = SimVehicle("V213", "A85-x", "A85", { t -> bus.at(t) }, phase = ph.toDouble())
            val r = Sim({ t -> if (t < 70.0) stop else bus.at(t).shift(1.0, 1.0) }, listOf(s, v213), 600.0, Gps(), chosen).run()
            if (ident(r).firstOrNull()?.second?.key != "V213") bad += "chosen '$chosen', S at $tS, 213 at $ph: ${story(r)}"
        }
        check("a bus beside us at the stop, then silent: 213 taken, not it", bad.isEmpty()) { bad.joinToString(" | ") }
    }
    // A fix every second with one dropout of 9 or 12 s soon after leaving
    // ХОТЕЛ ПЛИСКА, 304 alongside: every report can still be placed — 213
    // taken, nothing else. (Ending runs on a long gap alone took 304 in 20-27
    // of 60 runs here: review of 9 Oct 2026.)
    for (gap in listOf(9.0, 12.0)) for (at in listOf(0.0, 15.0, 45.0)) {
        val bad = ArrayList<String>()
        for ((p213, p304) in phases) {
            val from = Journey(startAtPliska = true).tLeavePliska + at
            val j = Journey(startAtPliska = true, gps = Gps(gaps = listOf((from + 0.5)..(from + gap - 0.5))),
                phase213 = p213, phase304 = p304)
            val r = j.sim().run()
            if (ident(r).firstOrNull()?.second?.key != "V213" || switched(r).isNotEmpty())
                bad += "213 at $p213, 304 at $p304: ${story(r)}"
        }
        check("from ХОТЕЛ ПЛИСКА, fixes a second with one ${gap.toInt()} s dropout ${at.toInt()} s after departure: 213 taken, nothing else",
            bad.isEmpty()) { bad.joinToString(" | ") }
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

    // ── Stage 2: the model beside the rules (service/RideShadow.kt) ──
    // We pull out of A1196 on 213's road at 10 m/s with 213 (due at the stop
    // we boarded at) and whatever [others] are; a reading every 30 s, from
    // 35 s, of reports 2 s old. The early rule by the boarding stop is judged
    // after the first reading, as the app does once the stop is known.
    fun shadowRide(others: List<SimVehicle>, due: Set<String>, readings: Int = 1,
                   until: Double = 40.0, judge: Boolean = true): Pair<RideShadow, List<String>> {
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it }, { lines += "D $it" })
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 10.0)
        val all = listOf(SimVehicle("V213", "T213", "A85", { ride.at(it) })) + others
        var t = 0.0
        var read = 0
        while (t <= until) {
            val ms = T0 + (t * 1000).toLong()
            val q = ride.at(t)
            shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0)
            if (t >= 35 && (t - 35) % 30.0 == 0.0 && read < readings) {
                read++
                shadow.onReading(ms + 300, ms, all.mapNotNull { v ->
                    v.pos(t - 2)?.let { RideShadow.Seen(v.key, v.tripId, v.routeId, (T0 / 1000) + (t - 2).toLong(), it.lat, it.lon) }
                })
                if (read == 1 && judge) shadow.judgeBoarding("БУЛ. ЦАРИГРАДСКО ШОСЕ") { trip -> if (trip in due) T0 / 1000 else null }
            }
            t += 1.0
        }
        return shadow to lines
    }
    // Beside us 12 m to one side at the reading, as the tram 18 was on 7 Oct;
    // that one went the other way, which a single reading does not see.
    val opposite = SimVehicle("VOPP", "TOPP", "A99", { t -> Feed.path("A4508").let { p ->
        p.at(p.along(Feed.stop("A1196")) + 10.0 * maxOf(0.0, t - 10)).shift(0.0, 12.0) } })
    run {
        val (_, lines) = shadowRide(listOf(opposite), due = setOf("T213"))
        check("stage 2, early rule: of two beside us, the one due at the boarding stop is taken",
            lines.any { it.startsWith("Model (boarding stop БУЛ. ЦАРИГРАДСКО ШОСЕ): would take A85 T213 at once") &&
                "A99/VOPP" in it && "A85/V213 0 m (due there)" in it }) { lines.joinToString(" | ") }
    }
    run {
        val (_, lines) = shadowRide(listOf(opposite), due = setOf("T213", "TOPP"))
        check("stage 2, early rule: two beside us both due — no early pick",
            lines.any { it.startsWith("Model (boarding stop") && "no early pick: 2 of the vehicles" in it }) {
            lines.joinToString(" | ") }
    }
    run {
        val far = SimVehicle("V213", "T213", "A85", { t -> Feed.path("A4508").let { p ->
            p.at(p.along(Feed.stop("A1196")) + 10.0 * (t - 10) + 400) } })
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 10.0)
        for (t in 0..40) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0)
        }
        val r = far.pos(38.0)!!
        shadow.onReading(T0 + 40_300, T0 + 40_000, listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + 38, r.lat, r.lon)))
        shadow.judgeBoarding("X") { T0 / 1000 }
        check("stage 2, early rule: nothing beside us — no early pick",
            lines.any { it == "Model (boarding stop X): nothing beside us — no early pick" }) { lines.joinToString(" | ") }
        shadow.judgeBoarding("X") { T0 / 1000 }
        shadow.boardingUntold()
        check("stage 2, early rule: judged once a journey", lines.count { it.startsWith("Model (boarding stop") } == 1) {
            lines.joinToString(" | ") }
    }
    run {
        // A fix every 6 s, reports 3 s old: the model takes 213 on the fix
        // after the second reading (the report waited for it), before the
        // early rule is judged on that reading — the boarding stop known
        // only then, the lookup taking a moment. Judged all the same.
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 11.0)
        for (t in 0..70) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            if (t % 6 == 0) shadow.onFix(ms, ms + 60, q.lat, q.lon, 8.0)
            if (t == 35 || t == 65) {
                val r = ride.at(t - 3.0)
                shadow.onReading(ms + 300, ms, listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + t - 3, r.lat, r.lon)))
            }
            if (t == 66) shadow.judgeBoarding("X") { T0 / 1000 }
        }
        val id = lines.indexOfFirst { "identified A85 T213" in it }
        val rule = lines.indexOfFirst { it.startsWith("Model (boarding stop X): would take A85 T213") }
        check("stage 2, early rule: judged on the reading though the model took a vehicle on a fix since",
            id in 0 until rule) { lines.joinToString(" | ") }
    }
    run {
        val (shadow, lines) = shadowRide(emptyList(), due = emptySet(), readings = 3, until = 100.0)
        val id = lines.firstOrNull { "identified" in it }
        check("stage 2: the model's conclusions logged in the replay's words, at the reading's time",
            id != null && Regex("""^Model @\d\d:\d\d:\d\d: identified A85 T213 \(\d+ m together\)$""").matches(id)) {
            lines.joinToString(" | ") }
        check("stage 2: once the model has taken a vehicle the early rule is no longer judged",
            !shadow.wantsBoarding() && shadowRide(emptyList(), due = emptySet(), readings = 3, until = 100.0,
                judge = false).first.wantsBoarding().not() &&
                shadowRide(emptyList(), due = emptySet(), readings = 1, judge = false).first.wantsBoarding()) {
            "still open before the model takes a vehicle, closed after" }
        check("stage 2: the summary names ours", shadow.summary().startsWith("Model at the end: ours A85/V213 T213")) {
            shadow.summary() }
        check("stage 2: metres together logged as detail", lines.any { it.startsWith("D Model @") && "A85/V213" in it }) {
            lines.joinToString(" | ") }
    }
    run {
        // Values as the trace writes them: a replay of the trace sees what the app saw.
        // An accuracy of 30.04 m is written 30.0 — inside the model's 30 m.
        val a = ArrayList<String>(); val b = ArrayList<String>()
        val exact = RideShadow("", { a += it }); val traced = RideShadow("", { b += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).drive(s0 + 2000, 12.0)
        for (t in 0..100) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            exact.onFix(ms, ms + 60, q.lat + 1.3e-7, q.lon - 2.7e-7, 30.04)
            traced.onFix(ms, ms + 60, String.format(java.util.Locale.US, "%.6f", q.lat + 1.3e-7).toDouble(),
                String.format(java.util.Locale.US, "%.6f", q.lon - 2.7e-7).toDouble(), 30.0)
            if (t >= 35 && (t - 35) % 30 == 0) {
                val r = ride.at(t - 2.0)
                exact.onReading(ms + 300, ms, listOf(RideShadow.Seen("V,1", "T,1", "A85", T0 / 1000 + t - 2, r.lat, r.lon)))
                traced.onReading(ms + 300, ms, listOf(RideShadow.Seen("V;1", "T;1", "A85", T0 / 1000 + t - 2,
                    String.format(java.util.Locale.US, "%.6f", r.lat).toDouble(),
                    String.format(java.util.Locale.US, "%.6f", r.lon).toDouble())))
            }
        }
        check("stage 2: fed as the trace writes values, exact and traced input conclude alike",
            a.isNotEmpty() && a == b) { "${a.joinToString(" | ")} vs ${b.joinToString(" | ")}" }
    }

    // ── Recorded journeys ──
    val traces = System.getenv("TRACE_DIR")?.let { File(it) }
    if (traces != null && traces.isDirectory) {
        for (f in traces.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }) {
            for ((start, lines) in journeys(f)) {
                val rp = replay(start, lines)
                println("TRACE ${f.name}: " + rp.events.joinToString("; ") { "${clock(it.atMs)} ${describe(it.event)}" })
                expectations[f.name]?.invoke(rp)
                variants[f.name]?.invoke(start, lines)
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
    // about 18:19:30 and was over 100 m away from 18:19:56. The app reads the
    // feed every 30 s (the tram itself reports every 5 s or so), so the
    // second reading after departure (18:15:26) is the first that can make
    // 150 m together; the app took it at 18:14:57 by its
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
    },
    // 9 Oct 2026: 314 (A200) from МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ, 213 chosen,
    // tracking started while waiting, power saving left on: from 15:26:35 to
    // 15:27:28 a fix every 5-6 s, standing at the stop. Departure 15:27:31.
    // The app's first check (15:27:36) was skipped for degraded positioning
    // and it took 314 at 15:28:07 by its boarding-stop check; the model too,
    // at its second reading. 314 stood at ОБЩИНА МЛАДОСТ from 15:29:48; off
    // and walking straight away at about 15:30:04 (the owner); 314 was 12 m
    // off then and over 100 m away from 15:30:34.
    "2026-10-09_314.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("9.10 bus 314: 314 identified by 15:28:10", (id?.event as? Event.Identified)?.routeId == "A200" &&
            clock(id.atMs) <= "15:28:10") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("9.10 bus 314: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("9.10 bus 314: got off between 15:31:00 and 15:32:30", off?.event is Event.Alighted &&
            clock(off.atMs) in "15:31:00".."15:32:30") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A200" }
        check("9.10 bus 314: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    }
)

/**
 * The trace's records with only one fix in every [stepS] seconds kept, as a
 * phone in power saving gives them, without speed or bearing.
 */
fun thinned(lines: List<String>, stepS: Int): List<String> {
    var next = Long.MIN_VALUE
    return lines.mapNotNull { line ->
        val p = line.split(",")
        if (p[0] != "F") return@mapNotNull line
        val t = p[2].toLong()
        if (t < next) return@mapNotNull null
        next = t + stepS * 1000L - 500L
        (p.take(6) + listOf("", "")).joinToString(",")
    }
}

/** Recorded journeys replayed as they would have been with poorer positions, by file name. */
val variants: Map<String, (String, List<String>) -> Unit> = mapOf(
    // 9 Oct 2026, 314: had power saving held the whole way. A fix every 6 s
    // leaves fewer than three fixes within 10 s of a fresh report (2-5 s old
    // at the reading) by the reading: the report waits for the fixes after
    // it, and 314 is taken a few seconds later than on the real trace. At 10
    // and 15 s only a report within about half a second of a fix can be
    // placed, and on this trace none is; and a report no fix can place ends
    // the runs of all but ours: nothing is decided. Whatever is decided here
    // must not be another vehicle.
    "2026-10-09_314.txt" to { start, lines ->
        val six = replay(start, thinned(lines, 6))
        val id = six.events.firstOrNull()
        check("9.10 bus 314, a fix every 6 s: 314 identified by 15:28:15", (id?.event as? Event.Identified)?.routeId == "A200" &&
            clock(id.atMs) <= "15:28:15") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        val off = six.events.lastOrNull()
        check("9.10 bus 314, a fix every 6 s: got off between 15:31:00 and 15:32:30", six.events.size == 2 &&
            off?.event is Event.Alighted && clock(off.atMs) in "15:31:00".."15:32:30") {
            six.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
        for (step in listOf(6, 10, 15)) {
            val rp = replay(start, thinned(lines, step))
            println("TRACE 2026-10-09_314.txt, a fix every $step s: " +
                rp.events.joinToString("; ") { "${clock(it.atMs)} ${describe(it.event)}" })
            check("9.10 bus 314, a fix every $step s: no vehicle but 314 taken", rp.events.all {
                when (val e = it.event) {
                    is Event.Identified -> e.routeId == "A200"
                    is Event.Switched -> e.toRouteId == "A200"
                    else -> true
                } }) { rp.events.joinToString { describe(it.event) } }
        }
    }
)
