package bg.sofia.transit.util

import android.content.Context
import android.location.Location
import android.util.Log
import bg.sofia.transit.data.repository.VehicleInfo
import bg.sofia.transit.data.repository.VehicleSnapshot
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A record of each journey as raw data — every position of ours and the
 * vehicles around us at every reading of the feed — so that a ride can be
 * replayed later, off the phone, exactly as the app saw it:
 *
 *   filesDir/journey_trace.txt
 *
 * For testing only. Nothing in the app reads it back, and writing it can
 * never affect tracking: lines are queued and written on a background
 * thread every few seconds, and whatever goes wrong stays here. Shared
 * together with the log from the diagnostic screen.
 *
 * Plain text, one record per line, times in epoch milliseconds unless said
 * otherwise:
 *
 *   # start <ms> <local time> <what was chosen>
 *   F,<received ms>,<fix time ms>,<lat>,<lon>,<accuracy m>,<speed m/s>,<bearing °>
 *   V,<received ms>,<server Date ms>,<feed header s>,<vehicles listed>,<our lat>,<our lon>
 *   v,<trip_id>,<route_id>,<vehicle id>,<report s>,<lat>,<lon>,<bearing °>
 *   # end <ms>
 *
 * An empty field is a value the source did not give. The fix time is the
 * receiver's own (satellite time for GPS), unlike the received time, which
 * is the phone's clock; the server's Date and the report times are the
 * feed's. Together they show how far apart the clocks are.
 *
 * A journey left open — a new journey started over it — is closed with
 * "# end <ms> unclosed" before the next start. If the whole app was killed,
 * nothing is left to write that line, and the last few seconds of records
 * are lost with it: a start line then also ends whatever came before it.
 * When the file is cut down to size, or cleared, during a journey, that
 * journey's start line is written again at the top, so its records keep
 * their label.
 */
object JourneyTrace {

    private const val FILE_NAME = "journey_trace.txt"
    /** About ten hours of riding; the oldest half goes when it is reached. */
    private const val MAX_BYTES = 4_000_000L
    /**
     * Vehicles further than this from us are left out of a reading. The
     * vehicle we follow is always listed: getting off was judged at up to
     * 900 m on past rides.
     */
    private const val VEHICLE_RADIUS_M = 1000.0
    /**
     * Lines are written this long after the first of them is queued, not one
     * by one: a fix a second would otherwise open and close the file every
     * second of the journey. A journey's end is written at once.
     */
    private const val FLUSH_DELAY_MS = 5_000L

    private val queue = ConcurrentLinkedQueue<String>()
    private val initialised = AtomicBoolean(false)
    private val flushPending = AtomicBoolean(false)
    private var traceFile: File? = null
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "JourneyTrace").apply { isDaemon = true }
    }
    /** The start line of the journey in progress. On the writer thread only. */
    private var openStart: String? = null
    private val localFmt: java.time.format.DateTimeFormatter =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** Call once at app startup, next to FileLogger.init. */
    fun init(context: Context) {
        if (!initialised.compareAndSet(false, true)) return
        traceFile = File(context.filesDir, FILE_NAME)
    }

    fun start(chosen: String) {
        val now = System.currentTimeMillis()
        val local = try {
            localFmt.format(java.time.LocalDateTime.now())
        } catch (_: Throwable) { "" }
        // One line per record: the chosen line's label must not break it.
        enqueue("# start $now $local ${chosen.replace('\n', ' ')}")
    }

    fun end() = enqueueAll(listOf("# end ${System.currentTimeMillis()}"), now = true)

    /** One fix of ours, which arrived at [receivedMs] by the phone's clock. */
    fun fix(loc: Location, receivedMs: Long = System.currentTimeMillis()) {
        try {
            enqueue("F,$receivedMs,${loc.time}," +
                "${fmt6(loc.latitude)},${fmt6(loc.longitude)}," +
                (if (loc.hasAccuracy()) fmt1(loc.accuracy.toDouble()) else "") + "," +
                (if (loc.hasSpeed()) fmt1(loc.speed.toDouble()) else "") + "," +
                (if (loc.hasBearing()) fmt1(loc.bearing.toDouble()) else ""))
        } catch (_: Throwable) { }
    }

    /**
     * The vehicles of a reading that the trace lists: those within
     * [VEHICLE_RADIUS_M] of us ([lat], [lon]), and the one followed
     * ([trackedTripId]) wherever it is. The model in stage 2 is given the same.
     */
    fun listed(s: VehicleSnapshot, lat: Double, lon: Double, trackedTripId: String): List<VehicleInfo> =
        s.vehicles.filter { v ->
            (trackedTripId.isNotBlank() && v.tripId == trackedTripId) ||
                LocationHelper.distanceMetres(lat, lon, v.lat, v.lon) <= VEHICLE_RADIUS_M
        }

    /** One reading of the feed: the vehicles [near] of it (see [listed]), taken at [lat], [lon]. */
    fun vehicles(s: VehicleSnapshot, lat: Double, lon: Double, near: List<VehicleInfo>) {
        try {
            val lines = ArrayList<String>(near.size + 1)
            lines.add("V,${s.fetchedAtMs},${s.serverDateMs},${s.headerTimestamp},${near.size}," +
                "${fmt6(lat)},${fmt6(lon)}")
            for (v in near) {
                lines.add("v,${clean(v.tripId)},${clean(v.routeId)},${clean(v.vehicleId)}," +
                    "${v.timestamp},${fmt6(v.lat)},${fmt6(v.lon)}," +
                    (v.bearing?.let { fmt1(it.toDouble()) } ?: ""))
            }
            // Queued together, so that a reading is never split by a fix.
            enqueueAll(lines)
        } catch (_: Throwable) { }
    }

    /** Returns the underlying file (call only after init). */
    fun file(): File? = traceFile

    /**
     * Empties the trace, together with the log. A journey in progress keeps
     * its start line.
     */
    fun clear() {
        try {
            executor.execute {
                try {
                    // What was queued is dropped, but a start or end among it still counts.
                    while (true) noteMarker(queue.poll() ?: break)
                    traceFile?.let { f ->
                        replace(f, "# cleared ${System.currentTimeMillis()} ${Date()}\n" +
                            (openStart?.let { "$it\n" } ?: ""))
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Throwable) { }
    }

    private fun fmt6(v: Double) = String.format(Locale.US, "%.6f", v)
    private fun fmt1(v: Double) = String.format(Locale.US, "%.1f", v)
    /** Ids come from the feed; a comma or line break in one would break the line. */
    private fun clean(s: String?) = s.orEmpty().replace(',', ';').replace('\n', ' ')

    private fun enqueue(line: String) = enqueueAll(listOf(line))

    private fun enqueueAll(lines: List<String>, now: Boolean = false) {
        // Tracing must never affect the app: whatever goes wrong here stays here.
        try {
            if (traceFile == null) return
            queue.add(lines.joinToString("\n"))
            if (now) {
                executor.execute { flush() }
            } else if (flushPending.compareAndSet(false, true)) {
                executor.schedule({ flush() }, FLUSH_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        } catch (_: Throwable) { }
    }

    /** Keeps [openStart] up to date with a block about to be written or dropped. */
    private fun noteMarker(block: String) {
        if (block.startsWith("# start ")) openStart = block
        else if (block.startsWith("# end ")) openStart = null
    }

    private fun flush() {
        // Cleared before the queue is read: a line queued from here on
        // schedules a flush of its own.
        flushPending.set(false)
        val f = traceFile ?: return
        try {
            val failed = PrintWriter(FileWriter(f, true)).use { writer ->
                while (true) {
                    val block = queue.poll() ?: break
                    if (block.startsWith("# start ") && openStart != null) {
                        writer.println("# end ${System.currentTimeMillis()} unclosed")
                    }
                    noteMarker(block)
                    writer.println(block)
                }
                // PrintWriter keeps write errors to itself.
                writer.checkError()
            }
            if (failed) Log.e("JourneyTrace", "write failed — records lost")
            if (f.length() > MAX_BYTES) rotate(f)
        } catch (e: Exception) {
            Log.e("JourneyTrace", "flush failed: ${e.message}")
        }
    }

    /**
     * Keeps the newer half, from the first whole record in it — a vehicle
     * line whose reading header was cut off is dropped — with the start line
     * of the journey its first records belong to, if that line was in the
     * older half and the journey had not ended there.
     */
    private fun rotate(f: File) {
        try {
            val whole = f.readText()
            fun nextLine(at: Int): Int = whole.indexOf('\n', at).let { if (it < 0) whole.length else it + 1 }
            var from = nextLine(whole.length / 2)
            while (whole.startsWith("v,", from)) from = nextLine(from)
            val older = whole.substring(0, from)
            val lastStart = if (older.startsWith("# start ") && !older.contains("\n# start "))
                0 else older.lastIndexOf("\n# start ").let { if (it < 0) -1 else it + 1 }
            val carried = if (lastStart >= 0 && older.indexOf("\n# end ", lastStart) < 0)
                older.substring(lastStart, nextLine(lastStart)) else ""
            replace(f, carried + whole.substring(from))
        } catch (e: Exception) {
            Log.e("JourneyTrace", "rotate failed: ${e.message}")
        }
    }

    /**
     * Rewrites [f] whole by way of a new file renamed over it, so that it is
     * never seen half-written — by a share in progress, or after the app is
     * killed in the middle.
     */
    private fun replace(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            tmp.delete()
            throw java.io.IOException("rename failed")
        }
    }
}
