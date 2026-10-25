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

    // ── The zone ahead: seated at the back of a long vehicle (stage 3, 24 Oct 2026) ──
    // Its report, from the front, 35-50 m ahead of us whenever it moves (two
    // coupled trams of 22, 19 Oct 2026: 37-40 m): beyond BESIDE_M, it earned
    // nothing and nothing was taken. Alone near us it is beside us in the
    // zone ahead: taken, and getting off concluded at the back door.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val sStop = s0 + 2500
        val tram = Mover(p, s0).stand(20.0).drive(sStop, 10.0)
        val tArrive = tram.endT
        tram.stand(20.0)
        val tLeave = tram.endT
        tram.drive(sStop + 2000, 10.0)
        val bad = ArrayList<String>()
        var n = 0
        for (back in listOf(35.0, 40.0, 50.0)) for (seed in 1..seeds) for (gaps in listOf(false, true)) {
            n++
            val ours = { t: Double ->
                if (t >= tArrive + 5) p.at(sStop - back).shift(2.0, 2.0) else p.at(tram.alongAt(t) - back).shift(1.0, 1.0) }
            val r = Sim(ours, listOf(SimVehicle("VT", "TM22-1", "TM22", { tram.at(it) }, phase = (seed * 7 % 30).toDouble())),
                until = tLeave + 400, gps = Gps(seed = seed, gaps = if (gaps) gapsEvery(6.0) else emptyList()),
                chosenRoute = "TM55").run()
            val id = ident(r).firstOrNull()
            val off = alighted(r).firstOrNull()
            if (id?.second?.key != "VT" || id.first > 20 + 75 || r.events.size != 2 ||
                off?.second?.key != "VT" || off.first < tLeave + 20 || off.first > tLeave + 360)
                bad += "${back.toInt()} m back, seed $seed${if (gaps) ", a fix every 6 s" else ""}: ${story(r)}"
        }
        check("seated 35-50 m behind the front of a long vehicle, nothing else near: taken, got off", bad.isEmpty()) {
            "${bad.size} of $n: " + bad.take(3).joinToString(" | ") }
    }
    // A bus 36-50 m ahead of ours, riding with us all the way, its line chosen
    // or a third: in the zone ahead, it was taken in 30-49 % of runs when the
    // zone held whatever else was near (19 Oct 2026). With ours near us it
    // does not hold: never taken, as by BESIDE_M alone — and with a fix only
    // every 6 s too, when reports wait for the fixes after them.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val ride = Mover(p, s0).stand(20.0).drive(s0 + 3500, 11.0)
        val bad = ArrayList<String>()
        var n = 0
        for (gap in listOf(36.0, 40.0, 45.0, 50.0)) for (chosen in listOf("A99", "A1")) for (seed in 1..seeds)
            for (gaps in listOf(false, true)) {
                n++
                val r = Sim({ ride.at(it).shift(1.0, 1.0) }, listOf(
                    SimVehicle("V213", "A85-A4508-2-6-1", "A85", { ride.at(it) }, phase = (seed * 7 % 30).toDouble()),
                    SimVehicle("VX", "A99-A1-1-1-1", "A99", { t -> p.at(ride.alongAt(t) + gap) },
                        phase = (seed * 11 % 30).toDouble())
                ), until = 340.0, gps = Gps(seed = seed, gaps = if (gaps) gapsEvery(6.0) else emptyList()),
                    chosenRoute = chosen).run()
                if (r.events.any { (it.second as? Event.Identified)?.key == "VX" || (it.second as? Event.Switched)?.toKey == "VX" })
                    bad += "${gap.toInt()} m, $chosen chosen, seed $seed${if (gaps) ", a fix every 6 s" else ""}: ${story(r)}"
            }
        check("a bus 36-50 m ahead of ours, with us all the way: never taken", bad.isEmpty()) {
            "${bad.size} of $n: " + bad.take(3).joinToString(" | ") }
        // The price, accepted on 20 Oct 2026: ours not in the feed, the only
        // vehicle near us riding 45 m ahead with us is taken.
        val r = Sim({ ride.at(it).shift(1.0, 1.0) }, listOf(
            SimVehicle("VX", "A99-A1-1-1-1", "A99", { t -> p.at(ride.alongAt(t) + 45.0) }, phase = 11.0)
        ), until = 340.0, chosenRoute = "A1").run()
        check("known limit: ours not in the feed, the only vehicle near us 45 m ahead with us: taken",
            ident(r).firstOrNull()?.second?.key == "VX") { story(r) }
    }
    // The zone holds only while we move, and only with nothing else near.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val side = p.shifted(35.0)
        /**
         * Fixes every second for 200 s on 213's road, save within [gap]:
         * standing 100 s, then at [mps]. A reading at 200.2 s with VT's
         * report at [vtAt] s and maybe VO's, each placed by where we were
         * along the road at its moment. Whether VT is then beside us.
         */
        fun beside(mps: Double = 8.0, vtAt: Double = 198.0, vt: (Double) -> Pt = { s -> p.at(s + 45) },
                   vo: Pair<Double, (Double) -> Pt>? = null, gap: ClosedFloatingPointRange<Double>? = null): Boolean {
            val m = RideModel()
            val along = { t: Double -> s0 + mps * maxOf(0.0, t - 100.0) }
            for (k in 0..200) {
                if (gap != null && k.toDouble() in gap) continue
                val q = p.at(along(k.toDouble()))
                m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 6.0))
            }
            fun report(key: String, at: Double, where: (Double) -> Pt) = where(along(at)).let {
                RideModel.Report(key, key, key, T0 + (at * 1000).toLong(), it.lat, it.lon) }
            m.onReading(T0 + 200_200L, 0L, listOfNotNull(report("VT", vtAt, vt), vo?.let { report("VO", it.first, it.second) }))
            return m.besideUs().any { it.key == "VT" }
        }
        check("zone ahead: a vehicle 45 m ahead while we move, nothing else near — beside us", beside())
        check("zone ahead: the same while we stand — not beside us", !beside(mps = 0.0))
        check("zone ahead: the same while we creep at 1 m/s — not beside us", !beside(mps = 1.0))
        check("zone ahead: a vehicle 35 m to the side while we move — not beside us",
            !beside(vt = { s -> side.at(side.along(p.at(s))) }))
        check("zone ahead: another vehicle 55 m behind us — not beside us", !beside(vo = 198.0 to { s -> p.at(s - 55) }))
        check("zone ahead: another 70 m behind — beside us", beside(vo = 198.0 to { s -> p.at(s - 70) }))
        // Our fixes end at 194 s: VO's report at 198 s cannot be placed yet,
        // and from where we were at 194 s it is 82 m.
        check("zone ahead: another 50 m ahead of us at a moment our fixes cannot place yet — not beside us",
            !beside(vtAt = 190.0, vo = 198.0 to { s -> p.at(s + 50) }, gap = 194.5..200.0))
        // VO beside us at its report 150 s before: too old to say where it is.
        check("zone ahead: another whose only report, from beside us, is 150 s old — beside us",
            beside(vo = 50.0 to { s -> p.at(s) }))
    }

    // ── The early rule by the boarding stop (stage 3, decided 24 Oct 2026) ──
    // Waiting at УМБАЛ СВ. АННА; the feed's vehicles report every 5 s, and the
    // model hears of them only from departure, as the app reads the feed only
    // from then; the rule is asked after every reading. A third line chosen.
    run {
        val p = Feed.path("A4508")
        val sStop = p.along(Feed.stop("A1196"))
        fun dueOf(vararg trips: String): (String) -> Boolean = { it in trips }
        fun board(dep: Double, ours: (Double) -> Pt, vehicles: List<SimVehicle>, due: (String) -> Boolean,
                  until: Double = dep + 150) = Sim(ours, vehicles, until, chosenRoute = "A1", due = due).run()
        fun v(key: String, trip: String, dep: Double, phase: Double, pos: (Double) -> Pt?,
              glitch: (Double) -> Double = { 0.0 }) =
            SimVehicle(key, trip, "R$key", pos, reportEvery = 5.0, phase = phase, silent = listOf(-1.0..(dep - 0.5)),
                glitch = glitch)
        // The first reading after departure comes 9-25 s after it.
        val starts = listOf(40.0, 44.0, 48.0, 52.0, 56.0).flatMap { d -> (0..4).map { d to it.toDouble() } }

        // Ours alone near us, due: taken at that first reading, without metres.
        run {
            val bad = ArrayList<String>()
            for ((dep, ph) in starts) {
                val bus = Mover(p, sStop).stand(dep).drive(sStop + 3000, 10.0)
                val r = board(dep, { bus.at(it).shift(1.0, 1.0) }, listOf(v("V213", "T213", dep, ph, { bus.at(it) })),
                    dueOf("T213"))
                val id = ident(r).firstOrNull()
                if (id?.second?.key != "V213" || !id.second.byBoardingStop || id.first != 65.0 || r.events.size != 1)
                    bad += "departure ${dep.toInt()} s, phase $ph: ${story(r)}"
            }
            check("early rule: ours the only vehicle near us, due — taken at the first reading after departure",
                bad.isEmpty()) { "${bad.size} of ${starts.size}: " + bad.take(3).joinToString(" | ") }
        }
        // Another, due too, leaves the stop with us 35 m behind (24 Oct
        // 2026: 185 of the chosen line, 41 m behind us; 14 Oct, ХОТЕЛ ПЛИСКА:
        // 183 34-61 m behind): no early pick, ours by metres, never it.
        run {
            val bad = ArrayList<String>()
            for ((dep, ph) in starts) {
                val bus = Mover(p, sStop).stand(dep).drive(sStop + 3000, 10.0)
                val r = board(dep, { bus.at(it).shift(1.0, 1.0) }, listOf(v("V213", "T213", dep, ph, { bus.at(it) }),
                    v("VX", "TX", dep, 4.0 - ph, { t -> p.at(bus.alongAt(t) - 35) })), dueOf("T213", "TX"))
                val id = ident(r).firstOrNull()
                if (id?.second?.key != "V213" || id.second.byBoardingStop || r.events.size != 1)
                    bad += "departure ${dep.toInt()} s, phase $ph: ${story(r)}"
            }
            check("early rule: another due 35 m behind us — no early pick; ours by metres, never it", bad.isEmpty()) {
                "${bad.size} of ${starts.size}: " + bad.take(3).joinToString(" | ") }
        }
        // Only while we move. In a vehicle not in the feed, we pull 25 m out
        // of the stop and stand at a light for a minute while a bus due
        // there stands at the stop, beside us; then we go on: never taken.
        run {
            val bad = ArrayList<String>()
            for (ph in 0..4) {
                val bus = Mover(p, sStop).stand(40.0).drive(sStop + 25, 5.0).stand(55.0).drive(sStop + 3000, 10.0)
                val r = board(40.0, { bus.at(it).shift(1.0, 1.0) },
                    listOf(v("VX", "TX", 40.0, ph.toDouble(), { p.at(sStop).shift(-3.0, 0.0) })), dueOf("TX"))
                if (r.events.isNotEmpty()) bad += "phase $ph: ${story(r)}"
            }
            check("early rule: a bus due there beside us while we stand at a light, ours not in the feed — never taken",
                bad.isEmpty()) { bad.joinToString(" | ") }
        }
        // Seated at the back of a long tram, its report 50 m ahead of us,
        // alone: beside us in the zone ahead, taken at the first reading.
        run {
            val bad = ArrayList<String>()
            for ((dep, ph) in starts) {
                val tram = Mover(p, sStop).stand(dep).drive(sStop + 3000, 10.0)
                val r = board(dep, { t -> p.at(tram.alongAt(t) - 50).shift(1.0, 1.0) },
                    listOf(v("VT", "TT", dep, ph, { tram.at(it) })), dueOf("TT"))
                val id = ident(r).firstOrNull()
                if (id?.second?.key != "VT" || !id.second.byBoardingStop || id.first != 65.0 || r.events.size != 1)
                    bad += "departure ${dep.toInt()} s, phase $ph: ${story(r)}"
            }
            check("early rule: seated 50 m behind the front of a long tram, alone — taken at the first reading",
                bad.isEmpty()) { "${bad.size} of ${starts.size}: " + bad.take(3).joinToString(" | ") }
        }
        // Judged once: at the first reading it can be, and never after —
        // nor once ours has been taken by metres.
        run {
            fun rideOf(stand: List<ClosedFloatingPointRange<Double>>): (Double) -> Double = { t ->
                var s = sStop; var x = 0.0
                while (x < t) { if (stand.none { x in it }) s += 1.0; x += 0.1 }
                s }
            fun model(along: (Double) -> Double, upTo: Int, m: RideModel = RideModel("A1"), from: Int = 0): RideModel {
                for (k in from..upTo) { val q = p.at(along(k.toDouble())); m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 5.0)) }
                return m
            }
            fun rep(key: String, at: Double, along: (Double) -> Double, off: Double = 0.0) = p.at(along(at) + off).let {
                RideModel.Report(key, "T$key", "R$key", T0 + (at * 1000).toLong(), it.lat, it.lon) }
            // Moving at 10 m/s: V and W beside us, then V alone 10 s later (100 m together), W 200 m behind.
            val go = rideOf(emptyList())
            val m1 = model(go, 20)
            m1.onReading(T0 + 20_200, 0L, listOf(rep("V", 18.0, go), rep("W", 18.0, go, -10.0)))
            val e1 = m1.onBoarding { true }
            model(go, 30, m1, from = 21)
            m1.onReading(T0 + 30_200, 0L, listOf(rep("V", 28.0, go), rep("W", 28.0, go, -200.0)))
            val e2 = m1.onBoarding { true }
            check("early rule: judged once — V and W near us, then V alone: no early pick at all",
                e1.isEmpty() && e2.isEmpty() && m1.boarding?.pick == null && m1.ours == null) {
                "${m1.boarding?.why}; ${e1 + e2}" }
            // Standing, moving 200 m, standing: V taken by metres at a reading
            // the rule cannot judge (standing); moving again, it takes nothing.
            val stop = rideOf(listOf(0.0..20.0, 40.0..80.0))
            val m2 = model(stop, 10)
            m2.onReading(T0 + 10_200, 0L, listOf(rep("V", 8.0, stop)))
            val a = m2.onBoarding { true }
            model(stop, 50, m2, from = 11)
            val b = m2.onReading(T0 + 50_200, 0L, listOf(rep("V", 48.0, stop))) + m2.onBoarding { true }
            model(stop, 100, m2, from = 51)
            val c = m2.onReading(T0 + 100_200, 0L, listOf(rep("V", 98.0, stop))) + m2.onBoarding { true }
            check("early rule: once ours is taken by metres, it takes nothing",
                a.isEmpty() && b.singleOrNull()?.let { it is Event.Identified && !it.byBoardingStop } == true && c.isEmpty()) {
                "${a + b + c}" }
        }
        // Review of 24 Oct 2026: B, not ours (we ride a vehicle not in the
        // feed), beside us at two reports as we stand at the stop and at the
        // next one 200 m on: taken by metres, the rule never judged; B turns
        // off and is withdrawn; W, due at the boarding stop, then comes beside
        // us alone. Mid-ride, that is no evidence: not taken by the rule.
        run {
            val us = { t: Double -> sStop + when { t < 30 -> 0.0; t < 50 -> 10 * (t - 30); t < 70 -> 200.0; else -> 200.0 + 10 * (t - 70) } }
            val m = RideModel("RX")
            val out = ArrayList<Event>()
            for (k in 0..260) {
                val q = p.at(us(k.toDouble()))
                out += m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 5.0))
                if (k % 30 == 0 && k > 0) {
                    val at = k - 2.0
                    val b = if (at <= 100) p.at(us(at)) else p.at(us(100.0)).shift(10 * (at - 100), 0.0)
                    val w = p.at(if (at < 170) us(at) - 600 else us(at) - 5)
                    out += m.onReading(T0 + k * 1000L + 200, 0L, listOf(
                        RideModel.Report("B", "TB", "RB", T0 + (at * 1000).toLong(), b.lat, b.lon),
                        RideModel.Report("W", "TW", "RW", T0 + (at * 1000).toLong(), w.lat, w.lon)))
                    out += m.onBoarding { it == "TW" }
                }
            }
            check("early rule: after a vehicle taken by metres is withdrawn mid-ride, never",
                out.none { it is Event.Identified && it.byBoardingStop } && out.any { it is Event.Withdrawn }) {
                out.joinToString { describe(it) } }
        }
        // Taken wrongly, put right: ours's first report after departure
        // 150 m out, and a bus due there arriving at the stop as we pull out
        // slowly — beside us, alone near us. It is taken; once it is apart at
        // two reports as we go on, ours, beside us 150 m and more, takes over.
        run {
            val bad = ArrayList<String>()
            // VX's report at the first reading 3-5 s after departure, when we have moved.
            for (ph in 0..4) for (px in 1..3) {
                val dep = 58.0
                val bus = Mover(p, sStop).stand(dep).drive(sStop + 100, 5.0).drive(sStop + 3000, 10.0)
                val r = board(dep, { bus.at(it).shift(1.0, 1.0) }, listOf(
                    v("V213", "T213", dep, ph.toDouble(), { bus.at(it) }, glitch = { rt -> if (rt < dep + 6) 150.0 else 0.0 }),
                    v("VX", "TX", dep, px.toDouble(), { p.at(sStop).shift(-3.0, 0.0) })), dueOf("T213", "TX"), until = dep + 200)
                val first = ident(r).firstOrNull()
                val sw = switched(r).firstOrNull()
                if (first?.second?.key != "VX" || !first.second.byBoardingStop || sw?.second?.toKey != "V213" ||
                    sw.first > dep + 100 || r.model.ours?.key != "V213" || alighted(r).isNotEmpty())
                    bad += "phases $ph, $px: ${story(r)}"
            }
            check("early rule: taken wrongly, ours's report 150 m out — put right once it is apart as we go on",
                bad.isEmpty()) { bad.joinToString(" | ") }
        }
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
    // ── A tie the chosen line is not in; a later beginning that is real (20 Oct 2026) ──
    // Tracking begun mid-ride, 213 and 304 abreast as far as ПЛ. ОРЛОВ МОСТ,
    // a third line chosen: level, one was taken on a few metres — 304 in
    // about half the runs. Now the choice waits for the roads to part (we
    // ride on past ПЛ. ОРЛОВ МОСТ; got off there, it may never be made).
    run {
        val bad = ArrayList<String>()
        for ((p213, p304) in phases) {
            val dep = Journey(startAtPliska = true).tLeavePliska
            val j = Journey(startAtPliska = true, alight = false, chosen = "A1",
                gps = Gps(gaps = listOf(-1.0..(dep + 30.0 - 0.5))), phase213 = p213, phase304 = p304)
            val r = j.sim().run()
            val id = ident(r)
            if (id.isEmpty() || id.any { it.second.key != "V213" } || switched(r).isNotEmpty())
                bad += "213 at $p213, 304 at $p304: ${story(r)}"
        }
        check("tracking begun mid-ride, 213 and 304 abreast, a third line chosen: 213 taken, never 304", bad.isEmpty()) {
            bad.joinToString(" | ") }
    }
    // In 213 from mid-ride, a slower bus C on the same road 200-250 m ahead,
    // overtaken: beside us at one report within a reading of 213's run, it
    // held the choice a reading (20 Oct 2026, b: 185). Seen clearly apart as
    // we rode, its later beginning is real: 213 taken as soon as without it.
    run {
        val bad = ArrayList<String>()
        for ((d, vc) in listOf(200.0 to 6.0, 250.0 to 4.0)) for (a in 0 until 30 step 3) for (c in 0 until 30 step 3) {
            val dep = Journey(startAtPliska = true).tLeavePliska
            val j = Journey(startAtPliska = true, chosen = "A1", gps = Gps(gaps = listOf(-1.0..(dep + 30.0 - 0.5))),
                phase213 = a.toDouble())
            val s0 = j.bus213.alongAt(dep + 30.0) + d - vc * (dep + 30.0)
            val cBus = Mover(j.p213, s0).drive(s0 + vc * 3000, vc)
            val alone = j.vehicles().filter { it.key == "V213" }
            val r0 = Sim({ j.ours(it) }, alone, j.tLeaveOrlov + 200, j.gps, j.chosen).run()
            val r1 = Sim({ j.ours(it) }, alone + SimVehicle("VC", "A99-c", "A99", { t -> cBus.at(t).shift(3.0, 0.0) },
                phase = c.toDouble()), j.tLeaveOrlov + 200, j.gps, j.chosen).run()
            val t0 = ident(r0).firstOrNull()?.first
            val i1 = ident(r1).firstOrNull()
            if (t0 == null || i1?.second?.key != "V213" || i1.first > t0)
                bad += "C ${d.toInt()} m ahead at $vc m/s, 213 at $a, C at $c: alone ${t0?.toInt()} s; ${story(r1)}"
        }
        check("in 213 from mid-ride, a slower bus 200-250 m ahead overtaken: 213 taken as soon as without it",
            bad.isEmpty()) { "${bad.size} of 200: " + bad.take(3).joinToString(" | ") }
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
    // The most ordinary boarding: waiting at ХОТЕЛ ПЛИСКА, the chosen 213
    // stops right at us for 10 or 15 s — less than a reading — and a 304
    // stops 12 m behind it and leaves with it. 213's report before its first
    // beside us is often one from its approach, far off. A rule that took
    // that as evidence — "seen away, so its later beginning is real" — gave
    // 304 the head start: beganApart (reverted 10 Oct 2026), and both the
    // rewording and the "joined" variant tried on 20 Oct 2026.
    run {
        val p213 = Feed.path("A4508"); val p304 = Feed.path("A2791")
        val sPl = p213.along(Feed.stop("A2327")); val qPl = p304.along(Feed.stop("A2327"))
        val stop = p213.at(sPl).shift(3.0, 3.0)
        val bad = ArrayList<String>()
        for (dwell in listOf(10.0, 15.0)) for (arrive in listOf(100.0, 106.0, 112.0, 118.0, 124.0))
            for (a in 0 until 30 step 3) for (b in 0 until 30 step 3) {
                val leave = arrive + dwell
                val bus213 = Mover(p213, sPl - 11.0 * arrive).drive(sPl, 11.0).stand(dwell).drive(sPl + 3000, 11.0)
                val bus304 = Mover(p304, qPl - 12.0 - 11.0 * arrive).drive(qPl - 12.0, 11.0).stand(dwell).drive(qPl + 3000, 11.0)
                val vs = listOf(SimVehicle("V213", "A85-x", "A85", { bus213.at(it) }, phase = a.toDouble()),
                    SimVehicle("V304", "A217-x", "A217", { bus304.at(it) }, phase = b.toDouble()))
                val r = Sim({ t -> if (t < leave) stop else bus213.at(t).shift(1.0, 1.0) }, vs, leave + 240.0, Gps(), "A85").run()
                if (ident(r).firstOrNull()?.second?.key != "V213")
                    bad += "213 stands ${dwell.toInt()} s from ${arrive.toInt()} s, 213 at $a, 304 at $b: ${story(r)}"
            }
        check("waiting at ХОТЕЛ ПЛИСКА, 213 (chosen) stops at us 10-15 s, 304 12 m behind leaves with it: 213 taken",
            bad.isEmpty()) { "${bad.size} of 1000: " + bad.take(3).joinToString(" | ") }
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

    // ── Getting off, our fixes gone vague after it; and tunnels (stage 3, 24 Oct 2026) ──
    // Down into an underpass or the metro just after getting off, our fixes
    // keep coming 50-90 m vague (14 Oct 2026, d; 20 Oct, c): once our accurate
    // fixes have seen ours leave us on foot, they do not hold up getting off.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val sStop = s0 + 1500
        val bus = Mover(p, s0).drive(sStop, 10.0).stand(20.0).drive(sStop + 3000, 10.0)   // there 150-170 s
        val spot = p.at(sStop).shift(4.0, 4.0)
        val ours = { t: Double -> if (t >= 158) spot else bus.at(t).shift(1.0, 1.0) }
        fun ride(phase: Double, vagueFrom: Double) = Sim(ours, listOf(
            SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus.at(it) }, phase = phase)
        ), until = 450.0, gps = Gps(vague = listOf(vagueFrom..1e9)), chosenRoute = "A85").run()
        // Its reports at 157 s (beside us), 187 s and 217 s (apart: parted at
        // the reading at 245 s); vague from 250 s, before two minutes from
        // our last vehicle speed — as on 20 Oct, c.
        val a = ride(7.0, 250.0)
        check("got off, fixes vague once ours was apart at two reports: got off concluded",
            a.events.size == 2 && alighted(a).singleOrNull()?.let { it.second.key == "V213" && it.first in 190.0..370.0 } == true) {
            story(a) }
        // At 175 s (50 m off), 205 s (apart), 235 s — vague from 220 s, that
        // one cannot be placed: as on 14 Oct, d.
        val b = ride(25.0, 220.0)
        check("got off, fixes vague after ours's first report apart, 50 m off at the one before: got off concluded",
            b.events.size == 2 && alighted(b).singleOrNull()?.let { it.second.key == "V213" && it.first in 190.0..370.0 } == true) {
            story(b) }
        // Vague from the moment it pulls away: never seen apart, nothing is
        // concluded (the app's timers then end the journey).
        val c = ride(7.0, 172.0)
        check("known limit: got off, fixes vague from as ours pulls away — nothing concluded",
            c.events.size == 1 && ident(c).single().second.key == "V213") { story(c) }
    }
    // Judged against where our fixes last placed us, by hand: we ride V at
    // 10 m/s from 0 s to 60 s, when we stop at S; V's reports 60 m and then
    // 130 m off (at 80 s and 110 s); our fixes vague from 112 s.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        /** Where V is, along the road, at its reports from 70 s on; and whether we ride on in it. */
        fun run(vAt: (Double) -> Double, ride: Boolean): List<Pair<Int, Event>> {
            val m = RideModel("A85")
            val out = ArrayList<Pair<Int, Event>>()
            val us = { t: Double -> s0 + 10.0 * (if (ride) t else minOf(t, 60.0)) }
            val rnd = kotlin.random.Random(5)
            for (k in 0..300) {
                val vague = k >= 112
                val q = p.at(us(k.toDouble())).let { if (vague) it.shift(rnd.nextDouble(-30.0, 30.0), rnd.nextDouble(-30.0, 30.0)) else it }
                m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, if (vague) 70.0 else 5.0)).forEach { out += k to it }
                if (k % 30 == 20) {
                    val at = k - 2.0
                    val v = p.at(if (at < 70) us(at) else vAt(at))
                    m.onReading(T0 + k * 1000L + 200, 0L, listOf(RideModel.Report("V", "TV", "A85", T0 + (at * 1000).toLong(), v.lat, v.lon)))
                        .forEach { out += k to it }
                }
            }
            return out
        }
        // V waits at a light 130-150 m beyond S until 200 s, then goes on:
        // not clearly apart from where we were while we might have walked
        // half way to it; once it goes on, got off.
        val light = run({ t -> s0 + 600 + when {
            t < 100 -> 60.0
            t < 200 -> 130.0 + (t - 100) / 5
            else -> 150.0 + 10 * (t - 200) } }, ride = false)
        check("seen on foot, ours waiting at a light 150 m off, fixes vague: got off only once it goes on",
            light.size == 2 && light[1].second is Event.Alighted && light[1].first in 230..250) {
            light.joinToString { "${it.first}s ${describe(it.second)}" } }
        // Never clearly apart at a report our fixes placed — 60 m, then 90 m
        // off — before they went vague: nothing concluded.
        val near = run({ t -> s0 + 600 + when {
            t < 100 -> 60.0
            t < 112 -> 90.0
            else -> 90.0 + 10 * (t - 112) } }, ride = false)
        check("seen on foot, ours 60 m and 90 m off, then fixes vague as it goes: nothing concluded",
            near.size == 1) { near.joinToString { "${it.first}s ${describe(it.second)}" } }
        // Riding on in V into a tunnel at 112 s, its reports 70 m and 130 m
        // out just before (poor ones, beyond the zone ahead): we moved at
        // vehicle speed, never got off.
        val tunnel = run({ t -> s0 + 10 * t + when {
            t < 100 -> 70.0
            t < 112 -> 130.0
            else -> 0.0 } }, ride = true)
        check("in ours, two poor reports just before a tunnel, fixes vague in it: never taken for getting off",
            tunnel.none { it.second is Event.Alighted }) { tunnel.joinToString { "${it.first}s ${describe(it.second)}" } }
    }
    // Review of 24 Oct 2026: in a vehicle not in the feed, B taken for it;
    // B turns off while we wait 65 s at a light; we ride on, our fixes 70 m
    // vague but following us: they show we did not stay — not got off.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).drive(s0 + 1200, 10.0).stand(65.0).drive(s0 + 4000, 10.0)
        val b = { t: Double -> if (t <= 120) ride.at(t) else ride.at(120.0).shift(10 * (t - 120), 0.0) }
        val r = Sim({ ride.at(it) }, listOf(SimVehicle("VB", "A99-A1-1-1-1", "A99", b, phase = 7.0)), 420.0,
            gps = Gps(vague = listOf(190.0..1e9)), chosenRoute = "A1").run()
        check("not ours left as we stood at a light, we ride on with vague fixes: not taken for getting off",
            ident(r).firstOrNull()?.second?.key == "VB" && alighted(r).isEmpty()) { story(r) }
    }
    // In ours through a tunnel, our fixes vague or none, its reports as it
    // goes, stuck at the entrance, or none: never taken for getting off. Also
    // standing 20 s at a stop just before it and 3 min in a jam inside.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val sIn = s0 + 1200
        val bad = ArrayList<String>()
        var n = 0
        for (stopFirst in listOf(false, true)) for (fixes in listOf("vague", "none"))
            for (reports in listOf("as it goes", "stuck at the entrance", "none")) {
                n++
                val bus = Mover(p, s0).drive(sIn - 30, 10.0)
                if (stopFirst) bus.stand(20.0)
                bus.drive(sIn, 10.0)
                val tIn = bus.endT
                if (stopFirst) bus.drive(sIn + 300, 10.0).stand(180.0)
                bus.drive(sIn + 1000, 10.0)
                val tOut = bus.endT
                bus.drive(sIn + 3000, 10.0)
                val pos = { t: Double -> if (reports == "stuck at the entrance" && t in tIn..tOut) p.at(sIn) else bus.at(t) }
                val r = Sim({ bus.at(it).shift(1.0, 1.0) }, listOf(SimVehicle("V213", "A85-A4508-2-6-1", "A85", pos, phase = 7.0,
                    silent = if (reports == "none") listOf(tIn..tOut) else emptyList())), until = tOut + 200,
                    gps = if (fixes == "vague") Gps(vague = listOf(tIn..tOut)) else Gps(gaps = listOf(tIn..tOut)),
                    chosenRoute = "A85").run()
                if (r.events.size != 1 || ident(r).singleOrNull()?.second?.key != "V213")
                    bad += "${if (stopFirst) "a stop before it, a jam inside" else "through"}, fixes $fixes, reports $reports: ${story(r)}"
            }
        check("in ours through a tunnel: never taken for getting off, never withdrawn", bad.isEmpty()) {
            "${bad.size} of $n: " + bad.take(3).joinToString(" | ") }
        // One report of ours 150 m out as it stands at a stop just before a
        // 2 km tunnel: it seems to leave us as we stand, and to be far off
        // from where our fixes last placed us — but it was beside us at the
        // report before: never taken for getting off.
        val bad2 = ArrayList<String>()
        for (fixes in listOf("vague", "none")) for (phase in listOf(1.0, 7.0, 13.0, 19.0, 25.0)) {
            val bus = Mover(p, s0).drive(sIn - 30, 10.0).stand(30.0)
            val tStop = bus.endT - 30.0
            bus.drive(sIn, 10.0)
            val tIn = bus.endT
            bus.drive(sIn + 2000, 10.0)
            val tOut = bus.endT
            bus.drive(sIn + 4000, 10.0)
            val r = Sim({ bus.at(it).shift(1.0, 1.0) }, listOf(SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus.at(it) },
                phase = phase, glitch = { rt -> if (rt >= tStop && rt < tStop + 30) 150.0 else 0.0 })), until = tOut + 200,
                gps = if (fixes == "vague") Gps(vague = listOf(tIn..tOut)) else Gps(gaps = listOf(tIn..tOut)),
                chosenRoute = "A85").run()
            if (alighted(r).isNotEmpty() || withdrawn(r).isNotEmpty() || switched(r).isNotEmpty())
                bad2 += "fixes $fixes, phase $phase: ${story(r)}"
        }
        check("in ours, one report 150 m out at a stop just before a 2 km tunnel: never taken for getting off",
            bad2.isEmpty()) { bad2.joinToString(" | ") }
    }

    // ── Off ours's route and ours not with us (stage 3, decided 24 Oct 2026) ──
    // The app tells when we are off the route of the vehicle taken as ours
    // (RoadWatch, as its OffRoadWatch); not beside us for about a minute,
    // it has left us. Ours gone silent could never be seen apart: it was
    // held for good (and, with another beside us, for 5 min).
    fun offFrom(road: Path, ours: (Double) -> Pt, until: Double): Double {
        val w = RoadWatch(road)
        var t = 0.0
        while (t <= until) { if (w.off(ours(t))) return t; t += 1.0 }
        return -1.0
    }
    run {
        // 304 taken (chosen, tie at ХОТЕЛ ПЛИСКА), then silent for good; we
        // ride 213 on past ПЛ. ОРЛОВ МОСТ, where 304's road turns off.
        val j = Journey(startAtPliska = true, alight = false, chosen = "A217")
        val v = j.vehicles().map { sv ->
            if (sv.key != "V304") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                silent = listOf(70.0..1e9))
        }
        val r = Sim({ j.ours(it) }, v, j.tLeaveOrlov + 300, j.gps, j.chosen,
            offRoute = offRouteBy(mapOf("V304" to j.p304, "V213" to j.p213)) { j.ours(it) }).run()
        val tOff = offFrom(j.p304, { j.ours(it) }, j.tLeaveOrlov + 300)
        val sw = switched(r).firstOrNull()
        // Within 90 s of leaving its route (25 Oct 2026: counted from then,
        // not from its last report beside us) — here sooner, 304 having been
        // silent 5 min (SILENT_HOLD_MS).
        check("304 taken wrongly, silent; off its route, 213 beside us: 213 takes over within 90 s of leaving it",
            ident(r).firstOrNull()?.second?.key == "V304" && sw?.second?.toKey == "V213" && tOff > 0 &&
                sw.first in tOff..(tOff + 92) && r.events.size == 2) { "off its route ${tOff.toInt()} s; ${story(r)}" }
    }
    run {
        // In a vehicle not in the feed: 304, with us from ХОТЕЛ ПЛИСКА, is
        // taken (a known limit), then silent; we leave its road: withdrawn.
        val j = Journey(startAtPliska = true, alight = false, chosen = "A1", report213 = false)
        val v = j.vehicles().map { sv ->
            if (sv.key != "V304") sv else SimVehicle(sv.key, sv.tripId, sv.routeId, sv.pos, phase = sv.phase,
                silent = listOf(100.0..1e9))
        }
        val r = Sim({ j.ours(it) }, v, j.tLeaveOrlov + 300, j.gps, j.chosen,
            offRoute = offRouteBy(mapOf("V304" to j.p304)) { j.ours(it) }).run()
        val tOff = offFrom(j.p304, { j.ours(it) }, j.tLeaveOrlov + 300)
        val w = withdrawn(r).firstOrNull()
        check("304 taken wrongly, silent; off its route, nothing beside us: withdrawn 90 s after leaving it",
            ident(r).firstOrNull()?.second?.key == "V304" && w?.second?.key == "V304" && tOff > 0 &&
                w.first in (tOff + 88)..(tOff + 92) && r.events.size == 2 && r.model.ours == null) {
            "off its route ${tOff.toInt()} s; ${story(r)}" }
    }
    run {
        // Ours on a detour (road works): 120 m off its road for 700 m, then
        // back — or for 1.7 km.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val d1 = s0 + 800
        fun aside(s: Double, m: Double): Pt {
            val a = p.at(s - 5); val b = p.at(s + 5)
            val dn = (b.lat - a.lat) * KY; val de = (b.lon - a.lon) * KY * kotlin.math.cos(Math.toRadians(a.lat))
            val l = kotlin.math.hypot(dn, de).takeIf { it > 0 } ?: 1.0
            return p.at(s).shift(-de / l * m, dn / l * m)
        }
        for (d2 in listOf(s0 + 1500, s0 + 2500)) {
            val detour = { s: Double ->
                aside(s, when {
                    s < d1 || s > d2 + 200 -> 0.0
                    s < d1 + 100 -> 120.0 * (s - d1) / 100
                    s < d2 + 100 -> 120.0
                    else -> 120.0 * (d2 + 200 - s) / 100
                }) }
            val bus = Mover(p, s0).drive(s0 + 4000, 10.0)
            val ours = { t: Double -> detour(bus.alongAt(t)).shift(1.0, 1.0) }
            val tOff = offFrom(p, ours, 400.0)
            val long = d2 > s0 + 2000
            for (silent in listOf(false, true)) {
                val gap = (tOff - 15)..(tOff + if (long) 150 else 100)
                val r = Sim(ours, listOf(SimVehicle("V213", "A85-A4508-2-6-1", "A85", { detour(bus.alongAt(it)) }, phase = 7.0,
                    silent = if (silent) listOf(gap) else emptyList())), 400.0, chosenRoute = "A85",
                    offRoute = offRouteBy(mapOf("V213" to p), ours)).run()
                val label = if (long) "a 1.7 km detour" else "a detour"
                if (!silent || !long) {
                    // Silent through a detour shorter than 90 s off its road: kept
                    // (25 Oct 2026: the 90 s run from leaving its route; before,
                    // from its last report beside us — withdrawn, taken again).
                    check("ours on $label, ${if (silent) "silent through it" else "reporting"}: kept",
                        r.events.size == 1 && ident(r).singleOrNull()?.second?.key == "V213") {
                        "off its route ${tOff.toInt()} s; ${story(r)}" }
                } else {
                    val w = withdrawn(r).firstOrNull()
                    val again = ident(r).getOrNull(1)
                    check("ours on a 1.7 km detour, silent: withdrawn 90 s after we left its route, then taken again by metres",
                        r.events.size == 3 && ident(r).first().second.key == "V213" && w?.second?.key == "V213" &&
                            w.first in (tOff + 88)..(tOff + 92) && again?.second?.key == "V213" &&
                            again.first <= gap.endInclusive + 95) { "off its route ${tOff.toInt()} s; ${story(r)}" }
                }
            }
        }
    }
    run {
        // On the road 213 and 304 share, ours silent and 304 beside us: we
        // are on ours's route all the while — kept (as without being told).
        val j0 = Journey()
        val j = Journey(silent213 = listOf((j0.tLeavePliska + 10)..(j0.tArriveOrlov - 20)))
        val r = Sim({ j.ours(it) }, j.vehicles(), j.tLeaveOrlov + 420, j.gps, j.chosen,
            offRoute = offRouteBy(mapOf("V213" to j.p213, "V304" to j.p304)) { j.ours(it) }).run()
        check("213 silent on the road it shares with 304, the app telling the route: kept, got off",
            switched(r).isEmpty() && withdrawn(r).isEmpty() && alighted(r).singleOrNull()?.second?.key == "V213") {
            story(r) }
    }
    run {
        // Not beside us at two readings in a row (90 s), and only ours's route counts.
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val m = RideModel("A85")
        val out = ArrayList<Pair<Int, Event>>()
        fun rep(at: Double) = p.at(s0 + 10 * at).let { RideModel.Report("V", "TV", "A85", T0 + (at * 1000).toLong(), it.lat, it.lon) }
        for (k in 0..260) {
            val q = p.at(s0 + 10.0 * k)
            m.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 5.0)).forEach { out += k to it }
            if (k == 20) m.onReading(T0 + 20_200, 0L, listOf(rep(18.0))).forEach { out += k to it }
            if (k == 50) m.onReading(T0 + 50_200, 0L, listOf(rep(48.0))).forEach { out += k to it }
            if (k >= 60) m.onOffRoute(if (k < 150) "W" else "V")
        }
        // V last beside us at 48 s; off W's route from 60 s, off V's from 150 s:
        // withdrawn 90 s after we left its route (25 Oct 2026; before, on the
        // fix after — it had been silent 100 s by then).
        check("off another's route 90 s: nothing; off ours's, silent long before: withdrawn 90 s after leaving it",
            out.size == 2 && out[0].second is Event.Identified && out[1].second is Event.Withdrawn && out[1].first == 240) {
            out.joinToString { "${it.first}s ${describe(it.second)}" } }
        val m2 = RideModel("A85")
        val out2 = ArrayList<Pair<Int, Event>>()
        for (k in 0..200) {
            val q = p.at(s0 + 10.0 * k)
            m2.onFix(RideModel.Fix(T0 + k * 1000L, T0 + k * 1000L, q.lat, q.lon, 5.0)).forEach { out2 += k to it }
            if (k == 20 || k == 50 || k == 150 || k == 180)
                m2.onReading(T0 + k * 1000L + 200, 0L, listOf(rep(k - 2.0))).forEach { out2 += k to it }
            if (k >= 40) m2.onOffRoute("V")
        }
        // V last beside us at 48 s, off its route from 40 s: withdrawn at 138 s.
        // Beside us again at 148 s, 100 s after 48 s: its run starts afresh —
        // taken again at the next report, not at once on the metres between.
        check("off ours's route: withdrawn 90 s after it was last beside us; taken again from nothing",
            out2.size == 3 && out2[0].second is Event.Identified && out2[1].second is Event.Withdrawn && out2[1].first == 138 &&
                out2[2].second is Event.Identified && out2[2].first == 180) {
            out2.joinToString { "${it.first}s ${describe(it.second)}" } }
    }

    // Review of 24 Oct 2026: on a 1.7 km detour, ours reporting but one
    // reading missed, or one report 45 m off — at 60 s it was withdrawn and
    // taken again. Not beside us at two readings in a row: kept.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196")) - 300
        val d1 = s0 + 800; val d2 = s0 + 2500
        fun aside(s: Double, m: Double): Pt {
            val a = p.at(s - 5); val b = p.at(s + 5)
            val dn = (b.lat - a.lat) * KY; val de = (b.lon - a.lon) * KY * kotlin.math.cos(Math.toRadians(a.lat))
            val l = kotlin.math.hypot(dn, de).takeIf { it > 0 } ?: 1.0
            return p.at(s).shift(-de / l * m, dn / l * m)
        }
        val detour = { s: Double ->
            aside(s, when {
                s < d1 || s > d2 + 200 -> 0.0
                s < d1 + 100 -> 120.0 * (s - d1) / 100
                s < d2 + 100 -> 120.0
                else -> 120.0 * (d2 + 200 - s) / 100
            }) }
        val bus = Mover(p, s0).drive(s0 + 4000, 10.0)
        val ours = { t: Double -> detour(bus.alongAt(t)).shift(1.0, 1.0) }
        val bad = ArrayList<String>()
        for (variant in listOf("one reading missed", "one report 45 m off")) for (phase in listOf(1.0, 7.0, 13.0, 19.0, 25.0)) {
            // The first report at least 40 s after we leave its road (about 110 s).
            val rt = phase + kotlin.math.ceil((150.0 - phase) / 30.0) * 30.0
            val r = Sim(ours, listOf(SimVehicle("V213", "A85-A4508-2-6-1", "A85", { detour(bus.alongAt(it)) }, phase = phase,
                silent = if (variant == "one reading missed") listOf((rt - 0.5)..(rt + 0.5)) else emptyList(),
                glitch = { t -> if (variant == "one report 45 m off" && kotlin.math.abs(t - rt) < 0.5) 45.0 else 0.0 })),
                400.0, chosenRoute = "A85", offRoute = offRouteBy(mapOf("V213" to p), ours)).run()
            if (r.events.size != 1) bad += "$variant, phase $phase: ${story(r)}"
        }
        check("ours on a detour, one reading missed or one report 45 m off: kept", bad.isEmpty()) {
            bad.joinToString(" | ") }
    }
    // Review of 24 Oct 2026: riding on a detour, told off ours's route, we
    // get off at a stop on it and stand while it goes on: got off, not
    // withdrawn — off the route counts only while we move on.
    run {
        val p = Feed.path("A4508")
        val s0 = p.along(Feed.stop("A1196"))
        val bus = Mover(p, s0).drive(s0 + 4000, 10.0)
        val spot = p.at(s0 + 600).shift(4.0, 4.0)
        val r = Sim({ t -> if (t < 60) bus.at(t).shift(1.0, 1.0) else spot }, listOf(
            SimVehicle("V213", "A85-A4508-2-6-1", "A85", { bus.at(it) }, phase = 7.0)
        ), 420.0, chosenRoute = "A85", offRoute = { t, m -> m.ours?.key?.takeIf { t >= 30 } }).run()
        check("got off on a detour, the app telling we are off ours's route: got off, not withdrawn",
            r.events.size == 2 && alighted(r).singleOrNull()?.second?.key == "V213") { story(r) }
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

    // ── The model in the app (service/RideShadow.kt; stage 2, acting since stage 3) ──
    // We pull out of A1196 on 213's road at 10 m/s with 213 (due at the stop
    // we boarded at) and whatever [others] are; a reading every 30 s, from
    // 35 s, of reports 2 s old. The early rule by the boarding stop is asked
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
                if (read == 1 && judge) shadow.onBoarding(ms + 400, "БУЛ. ЦАРИГРАДСКО ШОСЕ", due.associateWith { T0 / 1000 })
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
        // Only one vehicle near us at all (24 Oct 2026): of two beside us,
        // one due is no pick — at ХОТЕЛ ПЛИСКА (14 Oct) the other was ours.
        val (_, lines) = shadowRide(listOf(opposite), due = setOf("T213"))
        check("stage 2, early rule: of two beside us, one due — no early pick",
            lines.any { it.startsWith("Model (boarding stop БУЛ. ЦАРИГРАДСКО ШОСЕ): no early pick: 2 vehicles near us") &&
                "A99/VOPP" in it && "A85/V213 0 m (due there)" in it }) { lines.joinToString(" | ") }
    }
    run {
        val (_, lines) = shadowRide(listOf(opposite), due = setOf("T213", "TOPP"))
        check("stage 2, early rule: two beside us both due — no early pick",
            lines.any { it.startsWith("Model (boarding stop") && "no early pick: 2 vehicles near us" in it }) {
            lines.joinToString(" | ") }
    }
    run {
        val (shadow, lines) = shadowRide(emptyList(), due = setOf("T213"))
        check("stage 3, early rule: the only vehicle near us, due — taken at once",
            lines.any { it.startsWith("Model (boarding stop БУЛ. ЦАРИГРАДСКО ШОСЕ): took A85 T213 at once") &&
                "near us: A85/V213 0 m (due there)" in it } &&
                lines.any { Regex("""^Model @\d\d:\d\d:\d\d: identified A85 T213 \(by the boarding stop\)$""").matches(it) } &&
                shadow.summary().startsWith("Model at the end: ours A85/V213 T213") &&
                shadow.summary().endsWith("early rule by the boarding stop: took A85 T213 at once, " +
                    "due there at ${RideShadow.clock(T0 / 1000 * 1000)}")) {
            lines.joinToString(" | ") + " || " + shadow.summary() }
    }
    run {
        // What the app follows: the model's conclusions come back from each call.
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 10.0)
        val got = ArrayList<RideModel.Event>()
        for (t in 0..40) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            got += shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0)
            if (t == 35) got += shadow.onReading(ms + 300, ms, listOf(RideShadow.Seen("V,213", "T,213", "A,85", T0 / 1000 + 33,
                ride.at(33.0).lat, ride.at(33.0).lon)))
        }
        got += shadow.onBoarding(T0 + 35_400, "X, Y", mapOf("T;213" to T0 / 1000))
        val e = got.singleOrNull() as? Event.Identified
        check("stage 3: the early rule's pick returned to the app, with the trace's ids, and the feed's kept",
            e != null && e.key == "V;213" && e.tripId == "T;213" && e.byBoardingStop &&
                shadow.feedIds("V;213") == ("T,213" to "A,85") && shadow.feedIds("V,213") == null) {
            "$got ${shadow.feedIds("V;213")}" }
        check("stage 3: the boarding stop's name logged as the trace writes it (comma escaped)",
            lines.any { it.startsWith("Model (boarding stop X; Y): took") }) { lines.joinToString(" | ") }
        check("stage 3: the vehicle taken is with us, at a report up to 90 s back by the phone's clock",
            shadow.withUs(T0 + 40_060, 90_000L) && shadow.withUs(T0 + 33_000 + 60 + 90_000, 90_000L) &&
                !shadow.withUs(T0 + 33_000 + 60 + 90_001, 90_000L)) { "" }
    }
    run {
        // Off the route of ours, as the app tells (onOffRoute), ours silent
        // and we ride on: it left us — the app is handed the withdrawal.
        val shadow = RideShadow("A85", { })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 4000, 10.0)
        val got = ArrayList<Pair<Int, RideModel.Event>>()
        for (t in 0..300) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            if (t == 120) shadow.onOffRoute("V213")
            shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0).forEach { got += t to it }
            if (t >= 35 && (t - 35) % 30 == 0) {
                val r = ride.at(t - 2.0)
                val seen = if (t < 100) listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + t - 2, r.lat, r.lon)) else emptyList()
                shadow.onReading(ms + 300, ms, seen).forEach { got += t to it }
            }
        }
        val w = got.firstOrNull { it.second is Event.Withdrawn }
        // Last beside us at 93 s, off its route from 120 s: withdrawn 90 s after that.
        check("stage 3: off its route, ours silent, riding on — withdrawn and handed to the app",
            got.any { it.second is Event.Identified } && w != null && w.first in 205..215 &&
                !shadow.withUs(T0 + 300_000, 90_000L)) { got.toString() }
    }
    run {
        val (_, lines) = shadowRide(emptyList(), due = emptySet())
        check("stage 2, early rule: the only vehicle near us, not due — no early pick",
            lines.any { it == "Model (boarding stop БУЛ. ЦАРИГРАДСКО ШОСЕ): no early pick: the only vehicle near us " +
                "was not due there; near us: A85/V213 0 m" }) { lines.joinToString(" | ") }
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
        val none = shadow.onBoarding(T0 + 40_400, "X", mapOf("T213" to T0 / 1000))
        check("stage 2, early rule: nothing beside us — not judged yet",
            none.isEmpty() && lines.none { it.startsWith("Model (boarding stop") } && shadow.wantsBoarding()) {
            lines.joinToString(" | ") }
        for (t in 41..70) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0)
        }
        val q = ride.at(68.0)
        shadow.onReading(T0 + 70_300, T0 + 70_000, listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + 68, q.lat, q.lon)))
        val took = shadow.onBoarding(T0 + 70_400, "X", mapOf("T213" to T0 / 1000)) +
            shadow.onBoarding(T0 + 70_500, "X", mapOf("T213" to T0 / 1000))
        shadow.boardingUntold()
        check("stage 2, early rule: judged once a journey, once something is beside us",
            lines.count { it.startsWith("Model (boarding stop") } == 1 && took.size == 1 &&
                lines.any { it.startsWith("Model (boarding stop X): took A85 T213") }) { lines.joinToString(" | ") }
    }
    run {
        // A fix every 6 s, reports 3 s old: the model takes 213 by metres on
        // the fix after the second reading (the report waited for it), before
        // the early rule is asked on that reading — the boarding stop known
        // only then, the lookup taking a moment. Stage 3: the rule is closed
        // then, as the model has taken a vehicle; nothing is judged, and the
        // summary says why.
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 11.0)
        var late = emptyList<RideModel.Event>()
        for (t in 0..70) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            if (t % 6 == 0) shadow.onFix(ms, ms + 60, q.lat, q.lon, 8.0)
            if (t == 35 || t == 65) {
                val r = ride.at(t - 3.0)
                shadow.onReading(ms + 300, ms, listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + t - 3, r.lat, r.lon)))
            }
            if (t == 66) late = shadow.onBoarding(ms, "X", mapOf("T213" to T0 / 1000))
        }
        check("stage 3, early rule: closed once the model took a vehicle by metres on a fix after the reading",
            lines.any { "identified A85 T213" in it && "m together" in it } && late.isEmpty() &&
                lines.none { it.startsWith("Model (boarding stop") } && !shadow.wantsBoarding() &&
                shadow.summary().endsWith("early rule by the boarding stop: taken by metres first")) {
            lines.joinToString(" | ") + " || " + shadow.summary() }
    }
    run {
        // The boarding stop cannot be told (we waited over 60 m from any stop
        // of the line): the early rule is not judged for the journey, even
        // with a vehicle due there beside us after.
        val lines = ArrayList<String>()
        val shadow = RideShadow("A85", { lines += it })
        val p = Feed.path("A4508"); val s0 = p.along(Feed.stop("A1196"))
        val ride = Mover(p, s0).stand(10.0).drive(s0 + 2000, 10.0)
        var got = emptyList<RideModel.Event>()
        for (t in 0..40) {
            val ms = T0 + t * 1000L; val q = ride.at(t.toDouble())
            shadow.onFix(ms, ms + 60, q.lat, q.lon, 5.0)
            if (t == 35) {
                val r = ride.at(33.0)
                shadow.onReading(ms + 300, ms, listOf(RideShadow.Seen("V213", "T213", "A85", T0 / 1000 + 33, r.lat, r.lon)))
                shadow.boardingUntold()
                got = shadow.onBoarding(ms + 400, "X", mapOf("T213" to T0 / 1000))
            }
        }
        check("stage 3, early rule: the boarding stop not told — not judged for the journey",
            got.isEmpty() && !shadow.wantsBoarding() &&
                lines.count { it.startsWith("Model (boarding stop") } == 1 &&
                lines.any { it == "Model (boarding stop): not told — the early rule is not judged" } &&
                shadow.summary().endsWith("early rule by the boarding stop: boarding stop not told")) {
            lines.joinToString(" | ") + " || " + shadow.summary() }
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
                // The early rule with every vehicle taken as due at the
                // boarding stop, the worst it could be told: whatever it
                // takes, the metres take too.
                val ids = rp.events.mapNotNull { (it.event as? Event.Identified)?.key ?: (it.event as? Event.Switched)?.toKey }
                val early = replay(start, lines) { true }
                val taken = early.events.mapNotNull { (it.event as? Event.Identified)?.key ?: (it.event as? Event.Switched)?.toKey }
                println("TRACE ${f.name}, early rule, all due: " +
                    early.events.joinToString("; ") { "${clock(it.atMs)} ${describe(it.event)}" })
                check("${f.name}, early rule with every vehicle due: nothing the metres do not take",
                    taken.all { it in ids } && (ids.isEmpty() || taken.isNotEmpty())) {
                    early.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
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
    },
    // 12 Oct 2026: 76 (A81) from МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ, 111 chosen,
    // tracking started while waiting. 76 pulled out 44 m at 08:55:20 and
    // stood 24 s; the app took it at 08:55:58 by its boarding-stop check, the
    // model at its third reading. The trace ends aboard at 09:02:49. On the
    // way the app's age correction put 87 at 17 m (59 m at the same moment)
    // and trolleybus 3, its report 83 s old, at 0 m (over 150 m off): neither
    // may earn anything.
    "2026-10-12_76.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("12.10 bus 76: 76 identified by 08:56:30", (id?.event as? Event.Identified)?.routeId == "A81" &&
            clock(id.atMs) <= "08:56:30") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("12.10 bus 76: nothing else", rp.events.size == 1) { rp.events.joinToString { describe(it.event) } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A81" }
        check("12.10 bus 76: 87 and trolleybus 3 earn nothing",
            others.none { rp.routeOf[it.key] in setOf("A87", "TB32") && it.value > 0.0 }) {
            others.joinToString { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } }
        check("12.10 bus 76: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 12 Oct 2026, the same 76 a stop later: 305 chosen, tracking begun aboard
    // near УМБАЛ СВ. АННА. The app took 76 at its second reading (09:05:04),
    // the model too. Off at ПЛ. ОРЛОВ МОСТ about 09:11:20 and stood there a
    // minute while 204 and others behind 76 came by; 76 left about 09:12:00.
    // 204 came beside us as we pulled in: 71 m together, no more.
    "2026-10-12_76b.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("12.10 76 from aboard: 76 identified by 09:05:05", (id?.event as? Event.Identified)?.routeId == "A81" &&
            clock(id.atMs) <= "09:05:05") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("12.10 76 from aboard: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("12.10 76 from aboard: got off between 09:12:30 and 09:14:30", off?.event is Event.Alighted &&
            clock(off.atMs) in "09:12:30".."09:14:30") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A81" }
        check("12.10 76 from aboard: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 14 Oct 2026, four short rides in one 76 (A81), ended by hand aboard
    // but the last. (a) from МЕТРОСТАНЦИЯ АЛ. МАЛИНОВ, 111 chosen. (b) begun
    // aboard on the bend before ДЪРЖАВНА ПЕЧАТНИЦА, 185 chosen: across the
    // bend the straight line between two reports is 97 m of 183 m driven, so
    // the model takes 76 a reading after the app. (c) begun aboard near УМБАЛ
    // СВ. АННА, 305 chosen; 304 passed the other way 35 m off. (d) from ХОТЕЛ
    // ПЛИСКА, 76 chosen: 183 (A270) 16 m and 76 20 m from us as we pulled
    // out, both due there; 183 then drove 34-61 m behind us to ПЛ. ОРЛОВ МОСТ
    // and earns nothing. Off there about 09:11:20; our fixes went vague
    // (68-89 m, gaps of 5-9 s) from about 09:13, so 76's reports after the
    // first one apart could not be placed, and the app ended the journey
    // (09:13:52) before the model concluded getting off. Now: seen on foot
    // after 76 was 66 m and then 223 m off, its next report, 09:13:16, is
    // judged against where our accurate fixes last placed us — clearly apart:
    // got off concluded at 09:13:38. The passenger sat at the last door: 76's
    // reports 15-27 m ahead of us.
    "2026-10-14_76a.txt" to { rp -> onlyOurs(rp, "14.10 a", "A81", "08:56:35") },
    "2026-10-14_76b.txt" to { rp -> onlyOurs(rp, "14.10 b", "A81", "09:01:10") },
    "2026-10-14_76c.txt" to { rp -> onlyOurs(rp, "14.10 c", "A81", "09:03:50") },
    "2026-10-14_76d.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("14.10 d: 76 identified by 09:07:15", (id?.event as? Event.Identified)?.routeId == "A81" &&
            clock(id.atMs) <= "09:07:15") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        val off = rp.events.getOrNull(1)
        check("14.10 d: got off between 09:13:00 and 09:13:52 (the app's end), nothing else", rp.events.size == 2 &&
            off?.event is Event.Alighted && clock(off.atMs) in "09:13:00".."09:13:52") {
            rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != "A81" }
        check("14.10 d: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
        val c = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] == "A270" }
        check("14.10 d: 183, beside us as we pulled out, earns nothing", c.all { it.value <= 0.0 }) {
            c.joinToString { "${it.key} ${it.value.toInt()}" } }
    },
    // 19 Oct 2026: tram 22 (two trams coupled), 22 chosen, from УЛ. ОПЪЛЧЕНСКА
    // to ЦЕНТРАЛНИ ХАЛИ, the passenger at the very back. The tram's reports
    // were 37-40 m ahead of us at the same moment whenever it moved, 1-9 m to
    // the side, and under BESIDE_M only standing: beside us by BESIDE_M alone
    // it earned nothing and the model took nothing. Alone near us, in the
    // zone ahead, it is taken at the second reading after departure (18:11:07)
    // and getting off is concluded: off at ЦЕНТРАЛНИ ХАЛИ about 18:16:30, the
    // tram over 100 m away from 18:17:11; the app ended at 18:18:17.
    "2026-10-19_22.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("19.10 tram 22: 22 identified by 18:11:45", (id?.event as? Event.Identified)?.key == "TM4170" &&
            clock(id.atMs) <= "18:11:45") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        val off = rp.events.getOrNull(1)
        check("19.10 tram 22: got off between 18:17:30 and 18:18:30, nothing else", rp.events.size == 2 &&
            off?.event is Event.Alighted && clock(off.atMs) in "18:17:30".."18:18:30") {
            rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { it.key != "TM4170" }
        check("19.10 tram 22: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 19 Oct 2026, evening: tram 18 (TM34, TM2344), 27 chosen, from
    // ЦЕНТРАЛНИ ХАЛИ to УАСГ, the passenger 2 m from the back seat; tracking
    // begun as the tram pulled out (no boarding stop). Its reports 27-42 m
    // ahead of us (once 55 m): bridged over mostly by BESIDE_M alone (taken
    // at 18:22:35, 1.5 min after the app, 650 m together of some 1.8 km);
    // alone near us in the zone ahead, at 18:21:34, 1800 m. Off at УАСГ; the
    // tram left about 18:30:06 while trams 10 and 13 came by the stop.
    "2026-10-19_18.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("19.10 tram 18: 18 identified by 18:21:40", (id?.event as? Event.Identified)?.key == "TM2344" &&
            clock(id.atMs) <= "18:21:40") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("19.10 tram 18: nothing else until getting off", rp.events.size == 2 &&
            rp.events[1].event is Event.Alighted) { rp.events.joinToString { describe(it.event) } }
        val off = rp.events.lastOrNull()
        check("19.10 tram 18: got off between 18:30:00 and 18:31:30", off?.event is Event.Alighted &&
            clock(off.atMs) in "18:30:00".."18:31:30") { off?.let { clock(it.atMs) } ?: "none" }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { it.key != "TM2344" }
        check("19.10 tram 18: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 20 Oct 2026: 76 (A3118), 111 chosen, tracking begun aboard as it crawled
    // where the routes of 111 and 76 part (taken for waiting; no boarding stop). The
    // app took 76 at its second reading (08:50:25), the model too. From
    // ДЪРЖАВНА ПЕЧАТНИЦА to УМБАЛ СВ. АННА 185 (A2807) ran beside us, 491 m
    // together; 76 had far more and was never contested. Ended aboard.
    "2026-10-20_76.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("20.10 bus 76: 76 identified by 08:50:30", (id?.event as? Event.Identified)?.key == "A3118" &&
            clock(id.atMs) <= "08:50:30") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("20.10 bus 76: nothing else — 185 beside us never taken", rp.events.size == 1) {
            rp.events.joinToString { describe(it.event) } }
    },
    // 20 Oct 2026, minutes later: the same 76 (A3118), 305 chosen, tracking
    // begun aboard at 55 km/h. We overtook 185 (A2807): 174 m ahead, then
    // 26 m at one report (08:57:39), then 124 m behind. Its run began within
    // a reading of 76's, so the stretch compared was nil and the choice
    // waited a reading: 76 taken at the third (08:58:13), as by the app — which
    // meanwhile announced 305's ПЛОЩАД НА АВИАЦИЯТА, not on 76's way. Seen
    // 174 m off as we rode, 185's later beginning is real: now at the second
    // (08:57:43), before that announcement.
    "2026-10-20_76b.txt" to { rp -> onlyOurs(rp, "20.10 b", "A81", "08:57:45") },
    // 20 Oct 2026, the same 76 (A3118) once more, 76 chosen, tracking begun
    // as it pulled out of ХОТЕЛ ПЛИСКА (never under 10 km/h: no boarding
    // stop). The app took it at once (its line chosen), the model at its
    // second reading. An opposite 305 and an opposite 76, at 0-11 m by the
    // app's age correction, earned nothing. Got off at ПЛ. ОРЛОВ МОСТ about
    // 09:07:45; ours was apart at two reports by 09:09:01, but the 120 s
    // without vehicle speed ran out at 09:09:31, as the fixes had turned
    // poor (57-77 m from 09:09:17) and the app ended: not concluded. (By the
    // fix clock the 120 s are up 1.2 s after the trace's last fix; see the
    // variant with the vague fixes going on.)
    "2026-10-20_76c.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("20.10 c: 76 identified by 09:01:00", (id?.event as? Event.Identified)?.key == "A3118" &&
            clock(id.atMs) <= "09:01:00") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        check("20.10 c: no other vehicle taken", rp.events.drop(1).none { it.event !is Event.Alighted }) {
            rp.events.joinToString { describe(it.event) } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { it.key != "A3118" }
        check("20.10 c: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    },
    // 24 Oct 2026: 181 (A2069), 185 chosen, tracking begun waiting at
    // МЕТРОСТАНЦИЯ ЦАРИГРАДСКО ШОСЕ. The early rule by the boarding stop
    // would have taken 181 at once (due there, the only one beside us), as
    // the app's boarding-stop check did at
    // 10:03:08; by metres the model took it at 10:03:39. A trolleybus the
    // other way at 5 m, and later at 0 m on a report 76 s old by the app's age
    // correction, earned nothing. Got off at БАНЯ ПАНЧАРЕВО (181 left about
    // 10:18:43): the model at 10:20:07, the app at 10:20:14.
    "2026-10-24_181.txt" to { rp ->
        val id = rp.events.firstOrNull()
        check("24.10 bus 181: 181 identified by 10:03:40", (id?.event as? Event.Identified)?.key == "A2069" &&
            clock(id.atMs) <= "10:03:40") { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
        val off = rp.events.getOrNull(1)
        check("24.10 bus 181: got off between 10:19:30 and 10:20:15, nothing else", rp.events.size == 2 &&
            off?.event is Event.Alighted && clock(off.atMs) in "10:19:30".."10:20:15") {
            rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
        val others = rp.metresAt.flatMap { it.second.entries }.filter { it.key != "A2069" }
        check("24.10 bus 181: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
            others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
    }
)

/**
 * The app's off-route watch by the road (OffRoadWatch), on our true places:
 * over 50 m from [road] for 150 m travelled; back within 50 m, on it again.
 */
class RoadWatch(private val road: Path) {
    private var travelled = 0.0
    private var prev: Pt? = null
    fun off(q: Pt): Boolean {
        if (q.dist(road.at(road.along(q))) <= 50.0) travelled = 0.0 else travelled += prev?.dist(q) ?: 0.0
        prev = q
        return travelled >= 150.0
    }
}

/** What the app tells the model (RideModel.onOffRoute): off the road of ours, from [roads] by vehicle. */
fun offRouteBy(roads: Map<String, Path>, ours: (Double) -> Pt): (Double, RideModel) -> String? {
    val watches = roads.mapValues { RoadWatch(it.value) }
    return { t, m ->
        val q = ours(t)
        val off = watches.filterValues { it.off(q) }.keys
        m.ours?.key?.takeIf { it in off }
    }
}

/** A recorded ride where only [route] is concluded, by [by], and no other vehicle reaches 150 m. */
fun onlyOurs(rp: Replayed, label: String, route: String, by: String) {
    val id = rp.events.firstOrNull()
    check("$label: ${route} identified by $by", (id?.event as? Event.Identified)?.routeId == route &&
        clock(id.atMs) <= by) { id?.let { "${clock(it.atMs)} ${describe(it.event)}" } ?: "none" }
    check("$label: nothing else", rp.events.size == 1) { rp.events.joinToString { describe(it.event) } }
    val others = rp.metresAt.flatMap { it.second.entries }.filter { rp.routeOf[it.key] != route }
    check("$label: no other vehicle reaches 150 m", others.all { it.value < 150.0 }) {
        others.maxByOrNull { it.value }?.let { "${rp.routeOf[it.key]}/${it.key} ${it.value.toInt()}" } ?: "" }
}

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

/**
 * The trace with our fixes moved [back] metres back along the way we went
 * (from our fixes 5 s either side; standing, the way last seen): as if we
 * had sat further back in the vehicle.
 */
fun movedBack(lines: List<String>, back: Double): List<String> {
    val fixes = lines.filter { it.startsWith("F,") }.map { it.split(",") }
    val t = fixes.map { it[2].toLong() }
    val lat = fixes.map { it[3].toDouble() }
    val lon = fixes.map { it[4].toDouble() }
    var way: Pair<Double, Double>? = null
    val moved = HashMap<String, String>()
    for (i in fixes.indices) {
        val a = t.indexOfFirst { it >= t[i] - 5000 }
        val b = t.indexOfLast { it <= t[i] + 5000 }
        val kx = KY * kotlin.math.cos(Math.toRadians(lat[i]))
        val dx = (lon[b] - lon[a]) * kx
        val dy = (lat[b] - lat[a]) * KY
        val l = kotlin.math.hypot(dx, dy)
        if (l >= 5.0) way = dx / l to dy / l
        val p = fixes[i].toMutableList()
        way?.let { (ex, ny) ->
            p[3] = String.format(java.util.Locale.US, "%.6f", lat[i] - ny * back / KY)
            p[4] = String.format(java.util.Locale.US, "%.6f", lon[i] - ex * back / kx)
        }
        moved[fixes[i].joinToString(",")] = p.joinToString(",")
    }
    return lines.map { moved[it] ?: it }
}

/** Recorded journeys replayed as they would have been with poorer positions, by file name. */
val variants: Map<String, (String, List<String>) -> Unit> = mapOf(
    // 20 Oct 2026, c: had the journey gone on, the fixes going on as vague in
    // the metro underpass (70 m, one every 6 s, where the last one was): got
    // off concluded once the 120 s are up — our accurate fixes last placed us
    // at 09:09:14, near where we stood as 76 left. Without that, never.
    "2026-10-20_76c.txt" to { start, lines ->
        val last = lines.last { it.startsWith("F,") }.split(",")
        val more = (1..5).map { k ->
            listOf("F", (last[1].toLong() + 6000L * k).toString(), (last[2].toLong() + 6000L * k).toString(),
                last[3], last[4], "70.0", "", "").joinToString(",") }
        val rp = replay(start, lines + more)
        val off = rp.events.getOrNull(1)
        check("20.10 c, the vague fixes going on: got off between 09:09:33 and 09:09:45, nothing else",
            rp.events.size == 2 && off?.event is Event.Alighted && clock(off.atMs) in "09:09:33".."09:09:45") {
            rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" } }
    },
    // 24 Oct 2026, 181: "had I sat at the very back?" (the owner). Our fixes
    // moved 10-25 m back: 185 (A2517) of the chosen line, which left the
    // stop just behind us, is nearer our place than 181 at the first reading
    // from 15 m on (21 m against 32 m at 20 m) — the early rule by "one due
    // among those beside us" would have taken it at 20 m. Both due there:
    // never 185, by the early rule or by metres; 181 taken.
    "2026-10-24_181.txt" to { start, lines ->
        val bad = ArrayList<String>()
        for (back in listOf(10.0, 15.0, 17.0, 20.0, 25.0)) {
            val rp = replay(start, movedBack(lines, back)) { it in setOf("A269-A3105-4-8-7102987730", "A234-A2619-2-8-25198859290") }
            val id = rp.events.firstOrNull()
            if ((id?.event as? Event.Identified)?.key != "A2069" || rp.events.any {
                    (it.event as? Event.Identified)?.key == "A2517" || (it.event as? Event.Switched)?.toKey == "A2517" })
                bad += "${back.toInt()} m back: " + rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" }
        }
        check("24.10 bus 181, seated 10-25 m further back, 181 and 185 due: 181, never 185", bad.isEmpty()) {
            bad.joinToString(" | ") }
    },
    // 20 Oct 2026, 76 with 111 chosen: tracking begun every 10 s from
    // 08:53:29 to 08:54:19 on the stretch shared with 185. Level with 76
    // there, 185 was taken on a few metres in all six (in three, seen 110 m
    // off just before as we crawled on). Now the choice waits: 76, never 185.
    "2026-10-20_76.txt" to { start, lines ->
        val bad = ArrayList<String>()
        val first = lines.first { it.startsWith("F,") }.split(",")[1].toLong()
        for (k in 0 until 6) {
            val from = first + (4 * 60 + 10 * k) * 1000L   // 08:53:29 + 10 s each
            val i = lines.indexOfFirst { it.startsWith("F,") && it.split(",")[1].toLong() >= from }
            val rp = replay(start, lines.drop(i))
            val id = rp.events.firstOrNull()
            if ((id?.event as? Event.Identified)?.key != "A3118" || rp.events.size != 1)
                bad += "from ${clock(from)}: " + rp.events.joinToString { "${clock(it.atMs)} ${describe(it.event)}" }
        }
        check("20.10 bus 76, tracking begun 08:53:29-08:54:19 by 185: 76 taken, never 185", bad.isEmpty()) {
            bad.joinToString(" | ") }
    },
    // 12 Oct 2026, 76 from aboard: tracking begun on foot at ПЛ. ОРЛОВ МОСТ,
    // every 10 s from just after getting off, while 76 leaves and 204, 84 and
    // 9 come by the stop: nothing may be taken.
    "2026-10-12_76b.txt" to { start, lines ->
        val bad = ArrayList<String>()
        val first = lines.first { it.startsWith("F,") }.split(",")[1].toLong()
        var from = first + (7 * 60 + 0) * 1000L   // 09:11:20
        while (true) {
            val i = lines.indexOfFirst { it.startsWith("F,") && it.split(",")[1].toLong() >= from }
            if (i < 0) break
            val rp = replay(start, lines.drop(i))
            if (rp.events.isNotEmpty()) bad += "from ${clock(from)}: " + rp.events.joinToString { describe(it.event) }
            from += 10_000L
        }
        check("12.10 76 from aboard, tracking begun on foot at the stop: nothing taken", bad.isEmpty()) {
            bad.joinToString(" | ") }
    },
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
