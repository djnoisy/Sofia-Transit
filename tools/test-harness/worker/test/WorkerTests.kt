import android.content.Context
import androidx.work.*
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.worker.GtfsUpdateWorker
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// ───────────────────────── test server ─────────────────────────
object Srv {
    var server: HttpServer? = null
    @Volatile var status = 200
    @Volatile var body: ByteArray = ByteArray(0)
    @Volatile var lastModified: Long? = null      // epoch ms
    @Volatile var etag: String? = null
    @Volatile var bytesPerSec: Int = 0            // 0 = full speed
    @Volatile var truncateAfter: Int = -1         // close after N bytes
    @Volatile var honorIms = true
    @Volatile var onServe: () -> Unit = {}
    val requests = mutableListOf<Map<String, String?>>()
    @Volatile var bodyBytesSent = 0

    fun reset() { status = 200; body = ByteArray(0); lastModified = null; etag = null; bytesPerSec = 0
        truncateAfter = -1; honorIms = true; onServe = {}; requests.clear(); bodyBytesSent = 0 }

    fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 18765), 0)
        s.executor = java.util.concurrent.Executors.newCachedThreadPool()
        s.createContext("/static") { ex ->
            try {
                val ims = ex.requestHeaders.getFirst("If-Modified-Since")
                val inm = ex.requestHeaders.getFirst("If-None-Match")
                synchronized(requests) { requests += mapOf("ims" to ims, "inm" to inm) }
                onServe()
                lastModified?.let { ex.responseHeaders.add("Last-Modified", http(it)) }
                etag?.let { ex.responseHeaders.add("ETag", it) }
                val lm = lastModified
                val notMod = honorIms && ((ims != null && lm != null && parse(ims) >= lm / 1000 * 1000) ||
                                          (inm != null && inm == etag))
                if (status != 200) { ex.sendResponseHeaders(status, -1); ex.close(); return@createContext }
                if (notMod) { ex.sendResponseHeaders(304, -1); ex.close(); return@createContext }
                ex.sendResponseHeaders(200, body.size.toLong())
                val out = ex.responseBody
                var i = 0
                val chunk = if (bytesPerSec > 0) maxOf(1, bytesPerSec / 10) else 8192
                while (i < body.size) {
                    if (truncateAfter in 0..i) { ex.close(); return@createContext }
                    val n = minOf(chunk, body.size - i)
                    out.write(body, i, n); out.flush(); i += n; bodyBytesSent = i
                    if (bytesPerSec > 0) Thread.sleep(100)
                }
                out.close()
            } catch (e: Exception) { try { ex.close() } catch (_: Exception) {} }
        }
        s.start(); server = s
    }
    fun stop() { server?.stop(0); server = null; Thread.sleep(100) }
    private val fmt = DateTimeFormatter.RFC_1123_DATE_TIME
    fun http(ms: Long) = fmt.format(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC))
    fun parse(s: String) = ZonedDateTime.parse(s, fmt).toInstant().toEpochMilli()
}

fun zipOf(files: Map<String, String>): ByteArray {
    val bo = ByteArrayOutputStream()
    ZipOutputStream(bo).use { z -> files.forEach { (n, c) -> z.putNextEntry(ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() } }
    return bo.toByteArray()
}
/** Deterministic, hard-to-compress filler so large bodies stay large inside the ZIP. */
fun noise(n: Int): String { val r = java.util.Random(42); val cs = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString(n) { repeat(n) { append(cs[r.nextInt(cs.length)]) } } }
fun feed(tag: String, big: Int = 0, dropStopTimes: Boolean = false, broken: Boolean = false): Map<String, String> {
    val m = linkedMapOf(
        "agency.txt" to "agency",
        "stops.txt" to (if (broken) "BROKEN " else "") + "stops-$tag",
        "routes.txt" to "routes-$tag",
        "trips.txt" to "trips-$tag",
        "stop_times.txt" to "stop_times-$tag" + noise(big),
        "calendar_dates.txt" to "cal-$tag",
        "shapes.txt" to "shapes-$tag",
        "feed_info.txt" to "generated ${System.nanoTime()}")   // differs every build
    if (dropStopTimes) m.remove("stop_times.txt")
    return m
}

// ───────────────────────── fixture ─────────────────────────
val BUNDLE_DATE = "2026-08-28"
val bundleMs: Long = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(BUNDLE_DATE)!!.time
const val DAY = 86_400_000L
const val MIN = 60_000L

class Fx {
    val root = kotlin.io.path.createTempDirectory("gtfs-test").toFile()
    val files = File(root, "files").apply { mkdirs() }
    val assets = File(root, "assets").apply { File(this, "gtfs").mkdirs() }
    val ctx = Context(files, assets)
    val repo = GtfsRepository(ctx)
    init {
        File(assets, "gtfs/stops.txt").writeText("stops-BUNDLED")
        File(assets, "gtfs/bundle_date.txt").writeText(BUNDLE_DATE)
        JourneyService._trackingState.value = JourneyService.TrackingState.Idle
        WorkManager.INSTANCE.enqueued.clear(); WorkManager.INSTANCE.infos = emptyList()
        FileLogger.lines.clear(); Srv.reset(); android.net.NetState.online = true; android.net.NetState.metered = true
    }
    val prefs get() = ctx.getSharedPreferences("gtfs_update", 0)
    fun p(k: String): Any? = (prefs as android.content.MemPrefs).map[k]
    fun set(k: String, v: Any) { (prefs as android.content.MemPrefs).map[k] = v }
    fun worker(install: Boolean, replace: Boolean = false) = GtfsUpdateWorker(ctx,
        WorkerParameters(input = if (install) workDataOf("install" to true, "replace_local" to replace) else Data.EMPTY,
                         tags = if (install) setOf(GtfsUpdateWorker.TAG_INSTALL) else emptySet()), repo)
    fun run(install: Boolean, replace: Boolean = false): Pair<ListenableWorker.Result, GtfsUpdateWorker> {
        val w = worker(install, replace)
        val r = runBlocking { w.doWork() }
        return r to w
    }
    /** Simulates "installed from bundle" state, as after a first run with 304. */
    fun installedBundled() { runBlocking { repo.loadStaticData() }; GtfsUpdateWorker.recordBundledDate(ctx) }
    /** Simulates "installed from a download with given markers". */
    fun installedDownloaded(tag: String, lm: Long) {
        Srv.body = zipOf(feed(tag)); Srv.lastModified = lm
        val (r, _) = run(install = true); check(out(r)["result"] == "installed_new") { "setup failed: $r" }
        Srv.requests.clear()
    }
    fun tmpDirs() = files.listFiles()!!.filter { it.name.startsWith("gtfs.tmp") }
    fun gtfsFiles() = File(files, "gtfs").list()?.sorted() ?: emptyList()
}
fun out(r: ListenableWorker.Result): Map<String, Any?> = when (r) {
    is ListenableWorker.Result.Success -> r.output.map + ("_state" to "SUCCESS")
    is ListenableWorker.Result.Failure -> r.output.map + ("_state" to "FAILURE")
    else -> mapOf("_state" to "RETRY")
}
fun phases(w: GtfsUpdateWorker) = w.progressLog.mapNotNull { it.getString("phase") }

// ───────────────────────── runner ─────────────────────────
var passed = 0; val failures = mutableListOf<String>()
fun test(name: String, body: () -> Unit) {
    try { body(); passed++; println("PASS  $name") }
    catch (e: Throwable) { failures += "$name: ${e.message}"; println("FAIL  $name\n      ${e.message}")
        FileLogger.lines.takeLast(12).forEach { println("        log> $it") } }
}
fun eq(what: String, exp: Any?, act: Any?) { if (exp != act) throw AssertionError("$what: expected <$exp> but was <$act>") }
fun yes(what: String, c: Boolean) { if (!c) throw AssertionError(what) }

fun main() {
    Srv.start()
    val NEW = bundleMs + 5 * DAY
    val OLD = bundleMs - 3 * DAY

    // ════════════ FIRST INSTALL ════════════
    test("I1 install: server 304 (nothing newer) → bundled, 'current'") {
        val f = Fx(); Srv.body = zipOf(feed("S")); Srv.lastModified = OLD
        val (r, w) = f.run(true); val o = out(r)
        eq("state", "SUCCESS", o["_state"]); eq("result", "installed_current", o["result"]); eq("visible", true, o["visible"])
        eq("db", "bundled:stops-BUNDLED", f.repo.db)
        eq("IMS sent = bundle date", bundleMs, Srv.parse(Srv.requests.single()["ims"]!!))
        eq("freshness clock = bundle date", bundleMs, f.p("last_success_ms"))
        yes("attempt recorded", f.p("last_attempt_ms") != null)
        eq("no download phase", false, "download" in phases(w))
        eq("no body sent", 0, Srv.bodyBytesSent); eq("no tmp left", 0, f.tmpDirs().size)
    }
    test("I2 install: newer on server → installed directly, bundled never imported") {
        val f = Fx(); Srv.body = zipOf(feed("NEW")); Srv.lastModified = NEW; Srv.etag = "\"e1\""
        val (r, w) = f.run(true); val o = out(r)
        eq("result", "installed_new", o["result"]); eq("db", "downloaded:stops-NEW", f.repo.db)
        eq("imports", 1, f.repo.loadCount)
        eq("only used files kept", listOf("calendar_dates.txt","routes.txt","stop_times.txt","stops.txt","trips.txt"), f.gtfsFiles())
        eq("LM stored", NEW / 1000 * 1000, f.p("last_modified_ms")); eq("etag stored", "\"e1\"", f.p("etag"))
        yes("hash stored", f.p("feed_hash") != null); yes("download day stored", f.p("last_download_ms") != null)
        eq("phases", listOf("download", "import"), phases(w)); eq("no tmp left", 0, f.tmpDirs().size)
    }
    test("I3 install: server ignores IMS, sends older feed with date → body not read, bundled") {
        val f = Fx(); Srv.honorIms = false; Srv.body = zipOf(feed("OLD", big = 3_000_000)); Srv.lastModified = OLD
        val (r, w) = f.run(true); val o = out(r)
        eq("result", "installed_current", o["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
        eq("no download phase (body not read)", false, "download" in phases(w)); eq("nothing extracted", 0, f.tmpDirs().sumOf { it.list()!!.size })
        eq("LM remembered for next 304", OLD / 1000 * 1000, f.p("last_modified_ms"))
    }
    test("I4 install: server gives no date/etag → downloads and installs server data, daily mode on") {
        val f = Fx(); Srv.body = zipOf(feed("ND"))
        val (r, _) = f.run(true)
        eq("result", "installed_new", out(r)["result"]); eq("db", "downloaded:stops-ND", f.repo.db)
        eq("no_validator", true, f.p("no_validator"))
    }
    test("I5 install: offline → bundled 'fallback', no throttle recorded") {
        val f = Fx(); Srv.stop()
        try {
            val (r, _) = f.run(true); val o = out(r)
            eq("result", "installed_fallback", o["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
            eq("attempt not recorded", null, f.p("last_attempt_ms")); eq("no dl-fail backoff", null, f.p("download_fail_ms"))
        } finally { Srv.start() }
    }
    test("I6 install: HTTP 500 → bundled 'fallback'") {
        val f = Fx(); Srv.status = 500
        val (r, _) = f.run(true)
        eq("result", "installed_fallback", out(r)["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
    }
    test("I7 install: download slower than the limit → aborted, bundled 'fallback'") {
        val f = Fx(); Srv.body = zipOf(feed("SLOW", big = 400_000)); Srv.lastModified = NEW; Srv.bytesPerSec = 50_000
        val t0 = System.currentTimeMillis(); val (r, _) = f.run(true); val dt = System.currentTimeMillis() - t0
        eq("result", "installed_fallback", out(r)["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
        yes("stopped near the limit (${dt} ms)", dt < 6_000)
        eq("no 2 h wait (follow-up retries instead)", null, f.p("download_fail_ms")); eq("no gtfs dir", emptyList<String>(), f.gtfsFiles())
        eq("nothing started behind the user's back", 0, WorkManager.INSTANCE.enqueued.size)
        eq("no tmp left", 0, f.tmpDirs().size); eq("validators not stored", null, f.p("last_modified_ms"))
    }
    test("I8 install: feed missing stop_times → rejected, bundled, 24 h bad-feed wait") {
        val f = Fx(); Srv.body = zipOf(feed("BAD", dropStopTimes = true)); Srv.lastModified = NEW
        val (r, _) = f.run(true)
        eq("result (no immediate refetch)", "installed_current", out(r)["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
        yes("bad feed marked", f.p("bad_feed_ms") != null); eq("no gtfs dir", emptyList<String>(), f.gtfsFiles())
    }
    test("I9 install: new data fails to parse → rolled back, bundled installed") {
        val f = Fx(); Srv.body = zipOf(feed("P", broken = true)); Srv.lastModified = NEW
        val (r, _) = f.run(true)
        eq("result (no immediate refetch)", "installed_current", out(r)["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
        eq("imports (failed + bundled)", 2, f.repo.loadCount); yes("bad feed marked", f.p("bad_feed_ms") != null)
        eq("no gtfs dir", emptyList<String>(), f.gtfsFiles()); eq("validators not stored", null, f.p("last_modified_ms"))
        eq("freshness = bundle", bundleMs, f.p("last_success_ms"))
    }
    test("I10 install: connection drops mid-download → bundled 'fallback'") {
        val f = Fx(); Srv.body = zipOf(feed("T", big = 500_000)); Srv.lastModified = NEW; Srv.truncateAfter = 10_000
        val (r, _) = f.run(true)
        eq("result", "installed_fallback", out(r)["result"]); eq("db", "bundled:stops-BUNDLED", f.repo.db)
        eq("no tmp left", 0, f.tmpDirs().size)
    }
    test("I11 install: database already filled → silent skip, no request") {
        val f = Fx(); f.installedBundled(); f.repo.loadCount = 0
        val (r, _) = f.run(true); val o = out(r)
        eq("result", "skipped", o["result"]); eq("visible", false, o["visible"]); eq("requests", 0, Srv.requests.size)
    }
    test("I12 install: another import never ends → failure shown (retry)") {
        val f = Fx(); f.repo.busy = true
        val (r, _) = f.run(true); val o = out(r)
        eq("state", "FAILURE", o["_state"]); eq("result", "failed", o["result"]); eq("visible", true, o["visible"])
    }
    test("I13 install with emptied DB but earlier download present: 304 → earlier download installed") {
        val f = Fx(); f.installedDownloaded("PREV", NEW); f.repo.db = null; f.repo.loadCount = 0
        val (r, _) = f.run(true)
        eq("result", "installed_current", out(r)["result"]); eq("db", "downloaded:stops-PREV", f.repo.db)
        eq("IMS = stored LM", NEW / 1000 * 1000, Srv.parse(Srv.requests.single()["ims"]!!))
    }
    test("I14 install: worker stopped by the system mid-download → stops at once (cancellation)") {
        val f = Fx(); Srv.body = zipOf(feed("C", big = 400_000)); Srv.lastModified = NEW; Srv.bytesPerSec = 100_000
        val w = f.worker(true)
        Thread { Thread.sleep(700); w.stoppedFlag = true }.start()
        var cancelled = false
        try { runBlocking { w.doWork() } } catch (e: java.util.concurrent.CancellationException) { cancelled = true }
        yes("CancellationException propagated", cancelled); eq("no data swapped in", emptyList<String>(), f.gtfsFiles())
    }

    // ════════════ LATER UPDATES ════════════
    test("U1 update: 304 → silent, nothing imported") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0; f.set("markers_unreliable", true)
        f.set("last_download_ms", 0L)   // mobile data: no download today yet
        val (r, w) = f.run(false); val o = out(r)
        eq("result", "unchanged", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("phases", emptyList<String>(), phases(w)); eq("304 clears unreliable flag", false, f.p("markers_unreliable"))
    }
    test("U2 update: new feed → download silent, then install shown, markers stored after import") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW + DAY
        val (r, w) = f.run(false); val o = out(r)
        eq("result", "updated", o["result"]); eq("visible", true, o["visible"]); eq("db", "downloaded:stops-B", f.repo.db)
        eq("phases", listOf("download", "import"), phases(w)); eq("LM", (NEW + DAY) / 1000 * 1000, f.p("last_modified_ms"))
        eq("no backup left", false, File(f.files, "gtfs.bak").exists())
    }
    test("U3 update: new feed fails to parse → old data back, failure shown, markers NOT stored") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("X", broken = true)); Srv.lastModified = NEW + DAY
        val (r, _) = f.run(false); val o = out(r)
        eq("state", "FAILURE", o["_state"]); eq("visible (install had started)", true, o["visible"])
        eq("old data back", "downloaded:stops-A", f.repo.db); eq("old LM kept", NEW / 1000 * 1000, f.p("last_modified_ms"))
        yes("bad feed marked", f.p("bad_feed_ms") != null)
    }
    test("U4 update: server ignores conditions but ETag unchanged → silent, body not read") {
        val f = Fx(); Srv.etag = "\"same\""; f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.honorIms = false; Srv.body = zipOf(feed("A", big = 3_000_000))
        val (r, w) = f.run(false)
        eq("result", "unchanged", out(r)["result"]); eq("imports", 0, f.repo.loadCount)
        eq("no download phase (body not read)", false, "download" in phases(w)); eq("nothing extracted", 0, f.tmpDirs().sumOf { it.list()!!.size })
    }
    test("U5 update: server ignores IMS, same date → silent") {
        val f = Fx(); f.installedDownloaded("A", NEW); Srv.honorIms = false; f.repo.loadCount = 0
        val (r, _) = f.run(false)
        eq("result", "unchanged", out(r)["result"]); eq("imports", 0, f.repo.loadCount)
    }
    test("U6 update: server says 'new' but content identical → silent, no import, once-a-day mode on") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("A")); Srv.lastModified = NEW + DAY   // rebuilt archive, same timetable
        val (r, _) = f.run(false); val o = out(r)
        eq("result", "unchanged", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("unreliable", true, f.p("markers_unreliable")); yes("download day stored", f.p("last_download_ms") != null)
    }
    test("U7 update: download breaks off → silent failure, markers untouched, next opening may try (after an hour)") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.set("last_download_ms", 0L); f.repo.loadCount = 0
        Srv.body = zipOf(feed("B", big = 500_000)); Srv.lastModified = NEW + DAY; Srv.truncateAfter = 20_000
        val (r, _) = f.run(false); val o = out(r)
        eq("state (no retry)", "FAILURE", o["_state"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("LM unchanged", NEW / 1000 * 1000, f.p("last_modified_ms"))
        eq("failed download not counted for the day", 0L, f.p("last_download_ms")); eq("data intact", "downloaded:stops-A", f.repo.db)
    }
    test("U8 update: journey in progress → skipped, no request") {
        val f = Fx(); f.installedBundled(); JourneyService._trackingState.value = JourneyService.TrackingState.Tracking()
        val (r, _) = f.run(false)
        eq("result", "skipped", out(r)["result"]); eq("requests", 0, Srv.requests.size)
    }
    test("U9 update: journey starts during download → install not done, silent, markers not stored") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW + DAY
        Srv.onServe = { JourneyService._trackingState.value = JourneyService.TrackingState.Tracking() }
        val (r, _) = f.run(false); val o = out(r)
        eq("result", "deferred", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("LM unchanged", NEW / 1000 * 1000, f.p("last_modified_ms")); eq("no tmp left", 0, f.tmpDirs().size)
        eq("data intact", "downloaded:stops-A", f.repo.db)
    }
    test("U10 update: another import starts during download → no swap, silent") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW + DAY; Srv.onServe = { f.repo.busy = true }
        val (r, _) = f.run(false); val o = out(r)
        eq("state", "FAILURE", o["_state"]); eq("visible", false, o["visible"]); eq("data intact", "downloaded:stops-A", f.repo.db)
    }
    test("U11 update: marker saved by the old version is not trusted, removed after update") {
        val f = Fx(); f.installedBundled(); f.set("last_modified", Srv.http(NEW))
        File(f.files, "gtfs").mkdirs(); listOf("stops.txt","routes.txt","trips.txt","stop_times.txt").forEach { File(f.files, "gtfs/$it").writeText("old-$it") }
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW
        val (r, _) = f.run(false)
        eq("no IMS from old marker", null, Srv.requests.single()["ims"]); eq("result", "updated", out(r)["result"])

    }
    test("U12 update on still-bundled data: baseline = bundle date") {
        val f = Fx(); f.installedBundled(); Srv.body = zipOf(feed("B")); Srv.lastModified = OLD
        val (r, _) = f.run(false)
        eq("IMS = bundle", bundleMs, Srv.parse(Srv.requests.single()["ims"]!!)); eq("result", "unchanged", out(r)["result"])
    }
    test("U13 update: stale temp folders from killed runs are removed") {
        val f = Fx(); f.installedDownloaded("A", NEW)
        File(f.files, "gtfs.tmp").mkdirs(); File(f.files, "gtfs.tmp-dead").mkdirs(); File(f.files, "gtfs.tmp-dead/x").writeText("x")
        f.run(false); eq("tmp dirs", 0, f.tmpDirs().size)
    }
    test("U14 update: slow download over the limit → silent failure, data intact") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0
        Srv.body = zipOf(feed("B", big = 400_000)); Srv.lastModified = NEW + DAY; Srv.bytesPerSec = 50_000
        val (r, _) = f.run(false); val o = out(r)
        eq("visible", false, o["visible"]); eq("state", "FAILURE", o["_state"]); eq("data intact", "downloaded:stops-A", f.repo.db)
    }

    // ════════════ SCHEDULING GATES (checkForUpdate) ════════════
    fun gate(setup: (Fx) -> Unit): Boolean { val f = Fx(); setup(f); GtfsUpdateWorker.checkForUpdate(f.ctx); return WorkManager.INSTANCE.enqueued.isNotEmpty() }
    val now = System.currentTimeMillis()
    val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    test("C1 fresh → check enqueued, no constraints") { yes("enqueued", gate { }) }
    test("C2 journey active → no check") { yes("not enqueued", !gate { JourneyService._trackingState.value = JourneyService.TrackingState.Tracking() }) }
    test("C3 last check 10/59 min ago → no; 61 min ago → yes (Wi-Fi and mobile)") {
        for (m in listOf(true, false)) {
            yes("10 min $m", !gate { android.net.NetState.metered = m; it.set("last_attempt_ms", now - 10 * MIN) })
            yes("59 min $m", !gate { android.net.NetState.metered = m; it.set("last_attempt_ms", now - 59 * MIN) })
            yes("61 min $m", gate { android.net.NetState.metered = m; it.set("last_attempt_ms", now - 61 * MIN) })
        }
        android.net.NetState.metered = true
    }
    test("C4 bad feed 23 h ago → no; 25 h → yes") {
        yes("23h", !gate { it.set("bad_feed_ms", now - 23 * 60 * MIN) }); yes("25h", gate { it.set("bad_feed_ms", now - 25 * 60 * MIN) })
    }
    test("C6 once-a-day mode: downloaded today → no; last download before today → yes (any network)") {
        yes("today", !gate { it.set("no_validator", true); it.set("last_download_ms", now) })
        yes("yesterday", gate { it.set("no_validator", true); it.set("last_download_ms", startOfToday - MIN) })
        yes("unreliable, today", !gate { it.set("markers_unreliable", true); it.set("last_download_ms", startOfToday + 1) })
        yes("unreliable, never", gate { it.set("markers_unreliable", true) })
    }
    test("C6w Wi-Fi: no day limit — downloaded today, last check 61 min ago → check") {
        android.net.NetState.metered = false
        yes("no_validator, today", gate { android.net.NetState.metered = false; it.set("no_validator", true); it.set("last_download_ms", now); it.set("last_attempt_ms", now - 61 * MIN) })
        yes("unreliable, today", gate { android.net.NetState.metered = false; it.set("markers_unreliable", true); it.set("last_download_ms", now) })
        android.net.NetState.metered = true
    }
    test("C6m mobile: a download today (made on Wi-Fi or mobile — same record) blocks; a failed one does not") {
        yes("downloaded today", !gate { it.set("no_validator", true); it.set("last_download_ms", startOfToday + 1); it.set("last_attempt_ms", now - 5 * 60 * MIN) })
        yes("no download today", gate { it.set("no_validator", true); it.set("last_download_ms", 0L); it.set("last_attempt_ms", now - 61 * MIN) })
    }
    test("W1 run allowed on Wi-Fi but phone now on mobile data, downloaded today → skipped silently, no request") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.set("no_validator", true); f.set("last_download_ms", System.currentTimeMillis())
        f.repo.loadCount = 0; Srv.body = zipOf(feed("B")); Srv.lastModified = null
        val o = out(f.run(false).first)
        eq("result", "skipped", o["result"]); eq("visible", false, o["visible"]); eq("requests", 0, Srv.requests.size)
        eq("no import", 0, f.repo.loadCount); eq("data intact", "downloaded:stops-A", f.repo.db)
    }
    test("W2 same state on Wi-Fi → downloads and installs the corrected data the same day") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.set("no_validator", true); f.set("last_download_ms", System.currentTimeMillis())
        android.net.NetState.metered = false; f.set("last_modified_ms", 0L)
        Srv.body = zipOf(feed("B")); Srv.lastModified = null
        val o = out(f.run(false).first)
        eq("result", "updated", o["result"]); eq("db", "downloaded:stops-B", f.repo.db)
        android.net.NetState.metered = true
    }
    test("C7 normal mode ignores the day limit") { yes("enqueued", gate { it.set("last_download_ms", now) }) }
    test("C8 enqueueInstall policy: KEEP only over an unfinished install") {
        val f = Fx(); val wm = WorkManager.INSTANCE
        fun pol(infos: List<WorkInfo>): ExistingWorkPolicy { wm.enqueued.clear(); wm.infos = infos; GtfsUpdateWorker.enqueueInstall(f.ctx); return wm.enqueued.single().policy }
        val id = java.util.UUID.randomUUID()
        eq("none", ExistingWorkPolicy.REPLACE, pol(emptyList()))
        eq("running install", ExistingWorkPolicy.KEEP, pol(listOf(WorkInfo(id, WorkInfo.State.RUNNING, setOf("gtfs_install")))))
        eq("enqueued install", ExistingWorkPolicy.KEEP, pol(listOf(WorkInfo(id, WorkInfo.State.ENQUEUED, setOf("gtfs_install")))))
        eq("running update", ExistingWorkPolicy.REPLACE, pol(listOf(WorkInfo(id, WorkInfo.State.RUNNING, emptySet()))))
        eq("failed install", ExistingWorkPolicy.REPLACE, pol(listOf(WorkInfo(id, WorkInfo.State.FAILED, setOf("gtfs_install")))))
        val req = wm.enqueued.single().req
        eq("tag", setOf("gtfs_install"), req.tags); eq("input", true, req.input.getBoolean("install", false))
    }

    // ════════════ MULTI-STEP FLOWS ════════════
    test("F1 install offline → later update online brings new data") {
        val f = Fx(); Srv.stop(); try { f.run(true) } finally { Srv.start() }
        eq("bundled", "bundled:stops-BUNDLED", f.repo.db)
        GtfsUpdateWorker.checkForUpdate(f.ctx); eq("not before the message is closed", 0, WorkManager.INSTANCE.enqueued.size)
        GtfsUpdateWorker.acknowledge(f.ctx, GtfsUpdateWorker.pendingResult(f.ctx)!!.id)
        GtfsUpdateWorker.checkForUpdate(f.ctx); yes("then checks (offline not throttled)", WorkManager.INSTANCE.enqueued.isNotEmpty())
        Srv.body = zipOf(feed("N")); Srv.lastModified = NEW
        val (r, _) = f.run(false); eq("result", "updated", out(r)["result"]); eq("db", "downloaded:stops-N", f.repo.db)
    }
    test("F2 no-date server: install downloads; same content next day → no reinstall; changed → reinstall") {
        val f = Fx(); Srv.body = zipOf(feed("D1"))
        eq("install", "installed_new", out(f.run(true).first)["result"])
        f.set("last_download_ms", startOfToday - MIN); f.repo.loadCount = 0
        Srv.body = zipOf(feed("D1"))
        eq("same content", "unchanged", out(f.run(false).first)["result"]); eq("no import", 0, f.repo.loadCount)
        f.set("last_download_ms", startOfToday - MIN)
        Srv.body = zipOf(feed("D2"))
        eq("changed", "updated", out(f.run(false).first)["result"]); eq("db", "downloaded:stops-D2", f.repo.db)
    }
    test("F3 once-a-day mode ends when the server later answers 304 itself") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.set("markers_unreliable", true)
        f.set("last_download_ms", 0L)
        f.run(false); eq("flag cleared", false, f.p("markers_unreliable"))
    }


    // ════════════ REINSTALL (new app version with newer bundled data) ════════════
    val NB_DATE = java.time.LocalDate.parse(BUNDLE_DATE).plusDays(10).toString()
    val NB = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(NB_DATE)!!.time
    /** Data downloaded earlier (dated NEW), then an app update ships a bundle dated NB > NEW. */
    fun Fx.olderDownloadThenNewApk() { installedDownloaded("OLDDL", NEW); File(assets, "gtfs/bundle_date.txt").writeText(NB_DATE)
        File(assets, "gtfs/stops.txt").writeText("stops-NEWBUNDLE"); repo.loadCount = 0 }
    test("R1 reinstall, server has nothing newer → old download dropped, new bundle installed, shown only from import") {
        val f = Fx(); f.olderDownloadThenNewApk()
        yes("detected", GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
        val (r, w) = f.run(true, replace = true); val o = out(r)     // server still has the NEW(<NB) feed
        eq("result", "installed_current", o["result"]); eq("visible", true, o["visible"]); eq("db", "bundled:stops-NEWBUNDLE", f.repo.db)
        eq("IMS = new bundle date", NB, Srv.parse(Srv.requests.single()["ims"]!!))
        eq("downloaded set removed", emptyList<String>(), f.gtfsFiles()); eq("data date = bundle", NB, f.p("data_date_ms"))
        eq("not detected again", false, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
        eq("phases", listOf("import"), phases(w))
    }
    test("R2 reinstall, server has something newer than the bundle → that is installed") {
        val f = Fx(); f.olderDownloadThenNewApk(); Srv.body = zipOf(feed("NEWER")); Srv.lastModified = NB + 5 * DAY
        val (r, _) = f.run(true, replace = true)
        eq("result", "installed_new", out(r)["result"]); eq("db", "downloaded:stops-NEWER", f.repo.db)
        eq("data date = server date", (NB + 5 * DAY) / 1000 * 1000, f.p("data_date_ms"))
    }
    test("R3 reinstall during a journey → silently postponed, nothing touched") {
        val f = Fx(); f.olderDownloadThenNewApk()
        JourneyService._trackingState.value = JourneyService.TrackingState.Tracking()
        val (r, _) = f.run(true, replace = true); val o = out(r)
        eq("result", "skipped", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("download kept", 5, f.gtfsFiles().size); eq("requests", 0, Srv.requests.size)
    }
    test("R4 reinstall offline → bundle installed, shown") {
        val f = Fx(); f.olderDownloadThenNewApk(); Srv.stop()
        try { val (r, _) = f.run(true, replace = true); val o = out(r)
            eq("result", "installed_fallback", o["result"]); eq("visible", true, o["visible"]); eq("db", "bundled:stops-NEWBUNDLE", f.repo.db)
        } finally { Srv.start() }
    }
    test("R5 newer bundle detected even after 'not modified' checks moved the check time forward") {
        val f = Fx(); f.installedBundled()                                   // data = bundle date
        Srv.lastModified = OLD; f.run(false)                                  // a 304 check today
        yes("check time moved to now", (f.p("last_success_ms") as Long) > bundleMs + DAY)
        eq("same bundle → not newer", false, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
        File(f.assets, "gtfs/bundle_date.txt").writeText("2026-09-20")      // new APK
        eq("newer bundle → reinstall", true, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
    }
    test("R6 bundle vs downloaded data uses the server date of the download") {
        val f = Fx(); f.installedDownloaded("DL", NEW)                       // NEW = bundle + 5 days
        File(f.assets, "gtfs/bundle_date.txt").writeText(java.time.LocalDate.parse(BUNDLE_DATE).plusDays(3).toString())
        eq("bundle older than download", false, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
        File(f.assets, "gtfs/bundle_date.txt").writeText(java.time.LocalDate.parse(BUNDLE_DATE).plusDays(9).toString())
        eq("bundle newer than download", true, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
    }
    test("R7 data date unknown → no reinstall (nothing to compare)") {
        val f = Fx(); eq("unknown date", false, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
    }
    test("R7b same feed in bundle (dated next midnight) and download (evening before) → no reinstall") {
        val f = Fx(); f.set("data_date_ms", bundleMs - 2 * 3_600_000L)   // downloaded feed dated 22:00 the day before
        eq("tolerance", false, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
        f.set("data_date_ms", bundleMs - 3 * DAY); eq("really older → reinstall", true, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
    }
    test("R10 journey starts while a reinstall downloads → tables untouched, silent") {
        val f = Fx(); f.olderDownloadThenNewApk(); Srv.body = zipOf(feed("NEWER")); Srv.lastModified = NB + 5 * DAY
        Srv.onServe = { JourneyService._trackingState.value = JourneyService.TrackingState.Tracking() }
        val (r, _) = f.run(true, replace = true); val o = out(r)
        eq("result", "deferred", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
        eq("db untouched", "downloaded:stops-OLDDL", f.repo.db); eq("still due next start", true, GtfsUpdateWorker.bundleIsNewerThanData(f.ctx))
    }
    test("R11 journey starts while a reinstall checks the server (bundle path) → tables untouched") {
        val f = Fx(); f.olderDownloadThenNewApk()
        Srv.onServe = { JourneyService._trackingState.value = JourneyService.TrackingState.Tracking() }
        val (r, _) = f.run(true, replace = true)
        eq("result", "deferred", out(r)["result"]); eq("imports", 0, f.repo.loadCount)
    }
    test("R12 old install (date unknown) + offline at upgrade → nothing replaced, silent, retried later") {
        val f = Fx(); f.olderDownloadThenNewApk(); (f.prefs as android.content.MemPrefs).map.remove("data_date_ms"); Srv.stop()
        try { val (r, _) = f.run(true, replace = true); val o = out(r)
            eq("result", "deferred", o["result"]); eq("visible", false, o["visible"]); eq("imports", 0, f.repo.loadCount)
            eq("download kept", 5, f.gtfsFiles().size); eq("db", "downloaded:stops-OLDDL", f.repo.db)
        } finally { Srv.start() }
    }
    test("R13 old install (date unknown), server has nothing newer than the bundle → bundle installed") {
        val f = Fx(); f.olderDownloadThenNewApk(); (f.prefs as android.content.MemPrefs).map.remove("data_date_ms")
        val (r, _) = f.run(true, replace = true)
        eq("result", "installed_current", out(r)["result"]); eq("db", "bundled:stops-NEWBUNDLE", f.repo.db)
        eq("no ETag of the old set sent", null, Srv.requests.single()["inm"])
    }
    test("R14 old install (date unknown), server newer than the bundle → server data installed") {
        val f = Fx(); f.olderDownloadThenNewApk(); (f.prefs as android.content.MemPrefs).map.remove("data_date_ms")
        Srv.body = zipOf(feed("S2")); Srv.lastModified = NB + 3 * DAY
        val (r, _) = f.run(true, replace = true)
        eq("result", "installed_new", out(r)["result"]); eq("db", "downloaded:stops-S2", f.repo.db)
    }
    test("R15 reinstall: newer server feed fails to parse → previous download restored, not the bundle") {
        val f = Fx(); f.olderDownloadThenNewApk(); Srv.body = zipOf(feed("BRK", broken = true)); Srv.lastModified = NB + 3 * DAY
        val (r, _) = f.run(true, replace = true)
        eq("db", "downloaded:stops-OLDDL", f.repo.db); eq("files kept", 5, f.gtfsFiles().size)
    }
    test("P1 result to be shown is kept by the app: visible runs yes, silent runs no, cleared on close") {
        val f = Fx(); f.installedDownloaded("A", NEW)
        eq("install kept", "install", GtfsUpdateWorker.pendingResult(f.ctx)?.kind)
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW + DAY
        val (_, w) = f.run(false)
        val p = GtfsUpdateWorker.pendingResult(f.ctx)!!
        eq("id", w.id, p.id); eq("kind", "update", p.kind); eq("result", "updated", p.result)
        f.run(false)   // 304 → silent
        eq("silent run leaves it", w.id, GtfsUpdateWorker.pendingResult(f.ctx)?.id)
        GtfsUpdateWorker.acknowledge(f.ctx, w.id); eq("cleared", null, GtfsUpdateWorker.pendingResult(f.ctx))
    }
    test("P2 reinstall result kept with its kind") {
        val f = Fx(); f.olderDownloadThenNewApk(); val (_, w) = f.run(true, replace = true)
        eq("kind", "reinstall", GtfsUpdateWorker.pendingResult(f.ctx)?.kind); eq("id", w.id, GtfsUpdateWorker.pendingResult(f.ctx)?.id)
    }
    test("N1 update check declares it needs the network") {
        val f = Fx(); GtfsUpdateWorker.checkForUpdate(f.ctx)
        eq("constraint", NetworkType.CONNECTED, WorkManager.INSTANCE.enqueued.single().req.constraints?.network)
    }
    test("N2 install results: offline → fallback (look again after closing); nothing newer → current; bad feed → current") {
        val f = Fx(); Srv.stop(); val r1 = try { f.run(true).first } finally { Srv.start() }
        eq("offline", "installed_fallback", out(r1)["result"]); eq("nothing queued", 0, WorkManager.INSTANCE.enqueued.size)
        val g = Fx(); Srv.lastModified = OLD; eq("nothing newer", "installed_current", out(g.run(true).first)["result"])
        val h = Fx(); Srv.body = zipOf(feed("P", broken = true)); Srv.lastModified = NEW
        eq("bad feed", "installed_current", out(h.run(true).first)["result"])
    }
    test("N2b no check while the previous message is not closed; closing allows it") {
        val f = Fx(); f.installedDownloaded("A", NEW)          // leaves an unseen 'installed' message
        GtfsUpdateWorker.checkForUpdate(f.ctx); eq("blocked", 0, WorkManager.INSTANCE.enqueued.size)
        GtfsUpdateWorker.acknowledge(f.ctx, GtfsUpdateWorker.pendingResult(f.ctx)!!.id)
        f.set("last_attempt_ms", System.currentTimeMillis() - 61 * MIN)
        GtfsUpdateWorker.checkForUpdate(f.ctx); eq("after closing", 1, WorkManager.INSTANCE.enqueued.size)
    }
    test("N2c after closing a fallback install: update at once, ignoring the spacing, needs network") {
        val f = Fx(); f.set("last_attempt_ms", System.currentTimeMillis())   // 30-min spacing would block
        GtfsUpdateWorker.updateAfterFallbackInstall(f.ctx)
        val e = WorkManager.INSTANCE.enqueued.single(); eq("constraint", NetworkType.CONNECTED, e.req.constraints?.network)
        eq("policy", ExistingWorkPolicy.KEEP, e.policy)
    }
    test("N3 run stopped (connection lost) during install → install finished anyway, result kept; rerun does nothing") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.loadCount = 0; f.repo.loadDelayMs = 800
        Srv.body = zipOf(feed("B")); Srv.lastModified = NEW + DAY
        val params = WorkerParameters()
        val w = GtfsUpdateWorker(f.ctx, params, f.repo)
        // Like WorkManager on a lost constraint: mark stopped AND cancel the coroutine mid-import.
        var cancelled = false
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                try { w.doWork() } catch (c: java.util.concurrent.CancellationException) { cancelled = true }
            }
            while (f.repo.loadCount == 0) kotlinx.coroutines.delay(20)
            kotlinx.coroutines.delay(200); w.stoppedFlag = true; job.cancel(); job.join()
        }
        yes("run was cancelled", cancelled)
        eq("install finished anyway", "downloaded:stops-B", f.repo.db)
        eq("markers stored", (NEW + DAY) / 1000 * 1000, f.p("last_modified_ms"))
        eq("result kept for the user", params.id, GtfsUpdateWorker.pendingResult(f.ctx)?.id)
        Srv.requests.clear(); f.repo.loadCount = 0
        val rerun = GtfsUpdateWorker(f.ctx, params, f.repo); val r2 = runBlocking { rerun.doWork() }
        eq("rerun silent", false, out(r2)["visible"]); eq("no request", 0, Srv.requests.size); eq("no import", 0, f.repo.loadCount)
        eq("result still kept", params.id, GtfsUpdateWorker.pendingResult(f.ctx)?.id)
    }
    test("N4 first install stopped during its import → finished anyway") {
        val f = Fx(); f.repo.loadDelayMs = 800; Srv.stop()
        try { val w = f.worker(true)
            runBlocking {
                val job = launch(kotlinx.coroutines.Dispatchers.Default) {
                    try { w.doWork() } catch (c: java.util.concurrent.CancellationException) { }
                }
                while (f.repo.loadCount == 0) kotlinx.coroutines.delay(20)
                kotlinx.coroutines.delay(200); w.stoppedFlag = true; job.cancel(); job.join()
            }
            eq("data", "bundled:stops-BUNDLED", f.repo.db); eq("install result kept", "install", GtfsUpdateWorker.pendingResult(f.ctx)?.kind)
        } finally { Srv.start() }
    }
    test("N5 slow download during update → silent failure") {
        val f = Fx(); f.installedDownloaded("A", NEW)
        Srv.body = zipOf(feed("B", big = 400_000)); Srv.lastModified = NEW + DAY; Srv.bytesPerSec = 50_000
        val (r, _) = f.run(false); eq("state", "FAILURE", out(r)["_state"]); eq("visible", false, out(r)["visible"])
    }
    test("U15 update rerun after its import was cut off → local data installed first, even offline") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.db = null; f.repo.loadCount = 0; Srv.stop()
        try { val (r, _) = f.run(false); val o = out(r)
            eq("result (neutral)", "repaired", o["result"]); eq("visible", true, o["visible"]); eq("db", "downloaded:stops-A", f.repo.db)
        } finally { Srv.start() }
    }
    test("R8 queue policy: reinstall keeps a pending install/reinstall; install replaces a pending reinstall") {
        val f = Fx(); val wm = WorkManager.INSTANCE; val id = java.util.UUID.randomUUID()
        fun pol(replace: Boolean, infos: List<WorkInfo>): ExistingWorkPolicy { wm.enqueued.clear(); wm.infos = infos
            GtfsUpdateWorker.enqueueInstall(f.ctx, replace); return wm.enqueued.single().policy }
        eq("reinstall over reinstall", ExistingWorkPolicy.KEEP, pol(true, listOf(WorkInfo(id, WorkInfo.State.RUNNING, setOf("gtfs_reinstall")))))
        eq("reinstall over install", ExistingWorkPolicy.KEEP, pol(true, listOf(WorkInfo(id, WorkInfo.State.RUNNING, setOf("gtfs_install")))))
        eq("reinstall over update", ExistingWorkPolicy.REPLACE, pol(true, listOf(WorkInfo(id, WorkInfo.State.RUNNING, emptySet()))))
        eq("install over reinstall", ExistingWorkPolicy.REPLACE, pol(false, listOf(WorkInfo(id, WorkInfo.State.RUNNING, setOf("gtfs_reinstall")))))
        eq("reinstall tag", setOf("gtfs_reinstall"), wm.enqueued.single().req.tags.let { pol(true, emptyList()); wm.enqueued.single().req.tags })
    }
    test("R9 update: parse failure data date unchanged; success sets it") {
        val f = Fx(); f.installedDownloaded("A", NEW)
        Srv.body = zipOf(feed("X", broken = true)); Srv.lastModified = NEW + DAY; f.run(false)
        eq("unchanged after failure", NEW / 1000 * 1000, f.p("data_date_ms"))
        Srv.body = zipOf(feed("Y")); Srv.lastModified = NEW + 2 * DAY; f.set("bad_feed_ms", 0L); f.run(false)
        eq("set after success", (NEW + 2 * DAY) / 1000 * 1000, f.p("data_date_ms"))
    }

    // ════════════ FIXES 2026-10-01 ════════════
    test("Z1 at most 3 runs of one update (reruns after lost connection), silent") {
        val f = Fx(); f.installedDownloaded("A", NEW); Srv.requests.clear()
        val w = f.worker(false).also { it.runAttemptCount = 3 }; val r = runBlocking { w.doWork() }; val o = out(r)
        eq("state", "FAILURE", o["_state"]); eq("visible", false, o["visible"]); eq("no request", 0, Srv.requests.size)
    }
    fun damagedZip(): ByteArray { val z = zipOf(feed("D", big = 200_000)); val mid = z.size / 2
        for (i in mid until mid + 400) z[i] = (z[i].toInt() xor 0x5A).toByte(); return z }
    test("Z8 no internet at opening → nothing queued; with internet → queued") {
        val f = Fx(); android.net.NetState.online = false
        GtfsUpdateWorker.checkForUpdate(f.ctx); eq("offline", 0, WorkManager.INSTANCE.enqueued.size)
        android.net.NetState.online = true
        GtfsUpdateWorker.checkForUpdate(f.ctx); eq("online", 1, WorkManager.INSTANCE.enqueued.size)
    }
    test("Z9 closing a fallback install message offline → nothing queued (next opening checks)") {
        val f = Fx(); android.net.NetState.online = false
        GtfsUpdateWorker.updateAfterFallbackInstall(f.ctx); eq("offline", 0, WorkManager.INSTANCE.enqueued.size)
    }
    test("Z3 damaged archive during update → bad feed (24 h), no retries") {
        val f = Fx(); f.installedDownloaded("A", NEW); Srv.body = damagedZip(); Srv.lastModified = NEW + DAY
        val (r, _) = f.run(false); val o = out(r)
        eq("state (no retry)", "FAILURE", o["_state"]); yes("bad feed", f.p("bad_feed_ms") != null); eq("data intact", "downloaded:stops-A", f.repo.db)
    }
    test("Z3b damaged archive at first install → bundled, result 'current' (closing won't refetch)") {
        val f = Fx(); Srv.body = damagedZip(); Srv.lastModified = NEW
        val (r, _) = f.run(true); eq("result", "installed_current", out(r)["result"]); yes("bad feed", f.p("bad_feed_ms") != null)
    }
    fun Fx.fullBundle(tag: String) { val m = feed(tag); listOf("stops.txt","routes.txt","trips.txt","stop_times.txt","calendar_dates.txt")
        .forEach { File(assets, "gtfs/$it").writeText(m[it]!!) } }
    test("Z4 server sends the same timetable as the bundled data → not installed, not announced") {
        val f = Fx(); f.fullBundle("SAME"); Srv.stop(); try { f.run(true) } finally { Srv.start() }
        eq("bundled installed", "bundled:stops-SAME", f.repo.db); yes("bundled fingerprint stored", f.p("feed_hash") != null)
        f.repo.loadCount = 0; Srv.body = zipOf(feed("SAME")); Srv.lastModified = null   // no-date server, other files differ
        val (r, _) = f.run(false); val o = out(r)
        eq("result", "unchanged", o["result"]); eq("visible", false, o["visible"]); eq("no import", 0, f.repo.loadCount)
        Srv.body = zipOf(feed("OTHER")); f.set("last_attempt_ms", 0L)
        eq("same day on mobile → skipped", "skipped", out(f.run(false).first)["result"])
        android.net.NetState.metered = false
        val (r2, _) = f.run(false); eq("same day on Wi-Fi: different → updated", "updated", out(r2)["result"])
        android.net.NetState.metered = true
    }
    test("Z5 update fails and the old data cannot be put back → 'no data' result (retry offered)") {
        val f = Fx(); f.installedDownloaded("A", NEW); Srv.body = zipOf(feed("X")); Srv.lastModified = NEW + DAY
        f.repo.failLoads = 2
        val (r, _) = f.run(false); val o = out(r)
        eq("result", "failed_no_data", o["result"]); eq("visible", true, o["visible"])
        eq("kept for the user", "failed_no_data", GtfsUpdateWorker.pendingResult(f.ctx)?.result)
    }
    test("Z5b old data put back after a failed update → normal failure text") {
        val f = Fx(); f.installedDownloaded("A", NEW); Srv.body = zipOf(feed("X")); Srv.lastModified = NEW + DAY
        f.repo.failLoads = 1
        val (r, _) = f.run(false); eq("result", "failed", out(r)["result"]); eq("old data back", "downloaded:stops-A", f.repo.db)
    }
    test("Z6 repair of an unfinished install fails → 'no data' result") {
        val f = Fx(); f.installedDownloaded("A", NEW); f.repo.db = null; f.repo.failLoads = 1
        val (r, _) = f.run(false); eq("result", "failed_no_data", out(r)["result"])
    }

    Srv.stop()
    println("\n$passed passed, ${failures.size} failed")
    failures.forEach { println("  ✗ $it") }
    kotlin.system.exitProcess(if (failures.isEmpty()) 0 else 1)
}
