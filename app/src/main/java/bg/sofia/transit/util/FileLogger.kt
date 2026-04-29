package bg.sofia.transit.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-app log file writer. Captures every Log.i / Log.e / Log.w / Log.d call
 * made through this object and persists it to a text file inside the app's
 * private files directory:
 *
 *   filesDir/sofia_transit.log
 *
 * Writes are batched on a single background thread, so calling Log.* from
 * the main thread does not block the UI.
 *
 * The user can share this file via the diagnostic screen.
 */
object FileLogger {

    private const val FILE_NAME  = "sofia_transit.log"
    private const val MAX_BYTES  = 2_000_000L   // ~2 MB cap; older lines get rotated out

    private val queue = ConcurrentLinkedQueue<String>()
    private val initialised = AtomicBoolean(false)
    private var logFile: File? = null
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "FileLogger").apply { isDaemon = true }
    }
    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Call once at app startup (e.g. from Application.onCreate). */
    fun init(context: Context) {
        if (!initialised.compareAndSet(false, true)) return
        logFile = File(context.filesDir, FILE_NAME)
        rotateIfNeeded()
        write("=== Log started at ${Date()} ===")
    }

    fun i(tag: String, msg: String) { Log.i(tag, msg); enqueue("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, msg); enqueue("W", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, msg, t)
        val full = if (t == null) msg else "$msg\n${stackTrace(t)}"
        enqueue("E", tag, full)
    }
    fun d(tag: String, msg: String) { Log.d(tag, msg); enqueue("D", tag, msg) }

    private fun enqueue(level: String, tag: String, msg: String) {
        val ts = timestampFmt.format(Date())
        queue.add("$ts $level/$tag: $msg")
        executor.execute { flush() }
    }

    private fun flush() {
        val f = logFile ?: return
        try {
            PrintWriter(FileWriter(f, true)).use { writer ->
                while (true) {
                    val line = queue.poll() ?: break
                    writer.println(line)
                }
            }
            // Cheap rotation: every flush, check size
            if (f.length() > MAX_BYTES) rotateIfNeeded()
        } catch (e: Exception) {
            Log.e("FileLogger", "flush failed: ${e.message}")
        }
    }

    private fun rotateIfNeeded() {
        val f = logFile ?: return
        if (!f.exists() || f.length() < MAX_BYTES) return
        try {
            // Keep last ~1 MB
            val keepBytes = MAX_BYTES / 2
            val all = f.readBytes()
            val tail = all.copyOfRange((all.size - keepBytes).toInt(), all.size)
            // Find first newline so we don't start mid-line
            val firstNl = tail.indexOf('\n'.code.toByte())
            val safe = if (firstNl >= 0) tail.copyOfRange(firstNl + 1, tail.size) else tail
            f.writeBytes(safe)
        } catch (e: Exception) {
            Log.e("FileLogger", "rotate failed: ${e.message}")
        }
    }

    private fun write(line: String) {
        queue.add(line)
        executor.execute { flush() }
    }

    private fun stackTrace(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    /** Returns the underlying file (call only after init). */
    fun file(): File? = logFile

    /** Empties the log file. */
    fun clear() {
        executor.execute {
            try {
                logFile?.writeText("=== Log cleared at ${Date()} ===\n")
                queue.clear()
            } catch (_: Exception) {}
        }
    }
}
