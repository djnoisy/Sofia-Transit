package bg.sofia.transit.worker

import android.content.Context
import bg.sofia.transit.util.FileLogger
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bg.sofia.transit.data.repository.GtfsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * On-demand task that:
 *   1. Asks the official Sofia portal whether the GTFS static feed has
 *      changed since the data we hold (If-Modified-Since / If-None-Match)
 *   2. Only if it has: downloads the ZIP and atomically extracts it into
 *      filesDir/gtfs/ (via a tmp dir + rename)
 *   3. Re-imports the new data into the Room database
 *
 * Triggered whenever the app comes to the foreground (see [checkForUpdate]),
 * over any connection, mobile data included. A server that answers "not
 * modified" costs one tiny request. The current server gives no version
 * marker, so a check means a full download: on Wi-Fi at most one an hour,
 * on mobile data at most one successful download a day.
 *
 * The UI (MainActivity) follows the run through [KEY_PHASE] progress and the
 * [KEY_RESULT] / [KEY_VISIBLE] output, and shows a dialog only for runs that
 * actually downloaded something.
 *
 * On any failure (network, malformed ZIP, parsing error) the previous data
 * is preserved untouched — guaranteeing the app always has a working dataset.
 */
@HiltWorker
class GtfsUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val gtfsRepo: GtfsRepository
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GtfsUpdateWorker"
        const val WORK_NAME = "gtfs_refresh"
        private const val FEED_URL  = "https://gtfs.sofiatraffic.bg/api/v1/static"

        private const val PREFS_NAME     = "gtfs_update"
        private const val KEY_LAST_OK_MS = "last_success_ms"
        private const val KEY_LAST_MODIFIED_MS = "last_modified_ms"
        private const val KEY_ETAG = "etag"
        /** Start time of the last run that actually contacted the server. */
        private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
        /** Set when the server sent neither Last-Modified nor ETag. */
        private const val KEY_NO_VALIDATOR = "no_validator"
        /** Fingerprint of the data we hold (see fingerprintOf). */
        private const val KEY_FEED_HASH = "feed_hash"
        /**
         * Set when the last feed the server announced as new turned out to be
         * the same as the one we had — its change markers cannot be trusted,
         * and without this every check would pull the full ZIP again. Cleared
         * as soon as the server itself answers "unchanged" (304 / same ETag /
         * older date), which proves its markers work.
         */
        private const val KEY_MARKERS_UNRELIABLE = "markers_unreliable"
        /**
         * When the full feed was last downloaded successfully (complete, and
         * either imported or found identical). A download that broke off,
         * was rejected, or was discarded because a journey started does not
         * count, so on mobile data a new try is allowed the same day.
         */
        private const val KEY_LAST_DOWNLOAD_MS = "last_download_ms"
        /**
         * When a downloaded feed was rejected (files missing, parse error).
         * The same broken feed would otherwise be pulled again at every
         * check; after such a failure we wait [BAD_FEED_BACKOFF_MS].
         */
        private const val KEY_BAD_FEED_MS = "bad_feed_ms"
        /** Run whose install was finished although WorkManager had stopped it. */
        private const val KEY_DONE_WHILE_STOPPED = "done_while_stopped"
        /**
         * Runs of one update in all: Android stops a run when the connection
         * drops and starts it again when it returns, each time downloading
         * from zero. On a flapping connection that must not go on and on.
         */
        private const val MAX_RUNS = 3
        private const val KEY_PENDING_ID = "pending_id"
        private const val KEY_PENDING_KIND = "pending_kind"
        private const val KEY_PENDING_RESULT = "pending_result"
        const val KIND_INSTALL = "install"
        const val KIND_REINSTALL = "reinstall"
        const val KIND_UPDATE = "update"
        /** Id of the finished run whose result dialog the user has closed. */
        private const val KEY_ACK_WORK_ID = "ack_work_id"

        // Progress / output data read by MainActivity.
        const val KEY_PHASE   = "phase"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_IMPORT   = "import"
        const val KEY_RESULT  = "result"
        const val RESULT_UPDATED   = "updated"
        const val RESULT_UNCHANGED = "unchanged"
        const val RESULT_SKIPPED   = "skipped"
        const val RESULT_DEFERRED  = "deferred"
        const val RESULT_FAILED    = "failed"
        /** An update failed AND the old data could not be put back: no usable data. */
        const val RESULT_FAILED_NO_DATA = "failed_no_data"
        /** A rerun completed an install another run left unfinished. */
        const val RESULT_REPAIRED  = "repaired"
        /**
         * True when the run got as far as installing (importing), i.e. the
         * user saw the dialog. Checking and downloading run silently in the
         * background; only the install itself is shown.
         */
        const val KEY_VISIBLE = "visible"

        /** Tag of the first-run install run (see [enqueueInstall]). */
        const val TAG_INSTALL = "gtfs_install"
        /** Tag of a reinstall because a newer app version ships newer data. */
        const val TAG_REINSTALL = "gtfs_reinstall"
        private const val KEY_INSTALL = "install"
        private const val KEY_REPLACE_LOCAL = "replace_local"
        /**
         * Date of the data currently installed: the bundle's date, or the
         * server's Last-Modified of a downloaded feed (download time when the
         * server gives none). Decides whether a new app version's bundled
         * data is newer — unlike the time of the last check, which a mere
         * "not modified" answer moves forward.
         */
        private const val KEY_DATA_DATE_MS = "data_date_ms"
        /** Install: the newest data was downloaded and installed. */
        const val RESULT_INSTALLED_NEW      = "installed_new"
        /** Install: local data installed; the server had nothing newer. */
        const val RESULT_INSTALLED_CURRENT  = "installed_current"
        /** Install: local data installed; newer data could not be fetched. */
        const val RESULT_INSTALLED_FALLBACK = "installed_fallback"

        /**
         * Minimum spacing between two checks. Coming back to the app from
         * another screen or app a few times a minute must not fire a request
         * each time. On Wi-Fi this is what limits checks (an hour still
         * catches same-day corrections); on mobile data see
         * [mobileDayLimitReached].
         */
        private val MIN_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(60)


        private val BAD_FEED_BACKOFF_MS = TimeUnit.HOURS.toMillis(24)

        /** How long a run waits for another import to finish before giving up. */
        private const val BUSY_WAIT_MAX_MS = 180_000L
        /**
         * Shorter for an update, so that wait + download + a blocked read +
         * import + rollback stay under WorkManager's 10-minute limit.
         */
        private const val UPDATE_BUSY_WAIT_MAX_MS = 60_000L

        /**
         * Overall limits for downloading the ZIP. The connect/read timeouts
         * only bound each single read; on a crawling connection a download
         * could otherwise outlast WorkManager's 10-minute limit, be stopped,
         * rescheduled and started again from zero, over and over. On first
         * run the user is waiting with no data, so the limit is shorter and
         * the bundled data is installed instead.
         */
        private const val INSTALL_DOWNLOAD_MAX_MS = 180_000L
        private const val UPDATE_DOWNLOAD_MAX_MS  = 240_000L

        /** Required files — if any of these are missing, the update is rejected. */
        private val REQUIRED_FILES = setOf(
            "stops.txt", "routes.txt", "trips.txt", "stop_times.txt"
        )

        /**
         * Files we actually parse. After extraction we delete everything else
         * (shapes.txt, transfers.txt, translations.txt, pathways.txt, ...)
         * to save tens of MB of disk space.
         */
        private val FILES_TO_KEEP = setOf(
            "stops.txt",
            "routes.txt",
            "trips.txt",
            "stop_times.txt",
            "calendar_dates.txt"
        )

        private fun prefs(context: Context) =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** Timestamp of the last successful refresh or check, 0 if never. */
        fun lastSuccessMs(context: Context): Long =
            prefs(context).getLong(KEY_LAST_OK_MS, 0L)

        private fun recordSuccess(context: Context) {
            prefs(context).edit().putLong(KEY_LAST_OK_MS, System.currentTimeMillis()).apply()
        }

        /** Whether the result dialog of run [id] has already been closed. */
        fun isAcknowledged(context: Context, id: java.util.UUID): Boolean =
            prefs(context).getString(KEY_ACK_WORK_ID, null) == id.toString()

        fun acknowledge(context: Context, id: java.util.UUID) {
            val e = prefs(context).edit().putString(KEY_ACK_WORK_ID, id.toString())
            if (prefs(context).getString(KEY_PENDING_ID, null) == id.toString()) {
                e.remove(KEY_PENDING_ID).remove(KEY_PENDING_KIND).remove(KEY_PENDING_RESULT)
            }
            e.apply()
        }

        /** Result of a finished run the user should still be told about. */
        class PendingResult(val id: java.util.UUID, val kind: String, val result: String?)

        /**
         * The last run the user must be told about, kept by the app itself.
         * WorkManager keeps only the latest run under the unique name, so a
         * new check queued on a cold start can delete a finished run's record
         * before the Activity has read it; this copy survives that.
         */
        fun pendingResult(context: Context): PendingResult? {
            val p = prefs(context)
            val id = p.getString(KEY_PENDING_ID, null) ?: return null
            val uuid = try { java.util.UUID.fromString(id) } catch (e: Exception) { return null }
            return PendingResult(uuid, p.getString(KEY_PENDING_KIND, KIND_UPDATE) ?: KIND_UPDATE,
                p.getString(KEY_PENDING_RESULT, null))
        }

        private fun sameDay(a: Long, b: Long): Boolean {
            if (a <= 0L) return false
            val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
        }

        /** Whether the phone has an internet connection right now. */
        private fun isOnline(context: Context): Boolean = try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            // VALIDATED too: a network Android has confirmed actually works —
            // what WorkManager's CONNECTED waits for. Without it (captive
            // portal, unconfirmed Wi-Fi) a queued check could wait for hours.
            caps != null &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) { true }   // unknown → try; the run fails quietly if offline

        /**
         * Whether the current connection may cost the user money: mobile
         * data, or a Wi-Fi that Android marks as metered (e.g. a phone's
         * hotspot). Unknown counts as metered.
         */
        private fun isMetered(context: Context): Boolean = try {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
            caps == null ||
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (e: Exception) { true }

        private fun journeyActive(): Boolean =
            bg.sofia.transit.service.JourneyService.trackingState.value is
                bg.sofia.transit.service.JourneyService.TrackingState.Tracking

        /**
         * First run (empty database): one run that installs the data. It asks
         * the server for anything newer than the bundled data; if there is,
         * that is downloaded and installed directly, without first installing
         * the bundled set. Otherwise — nothing newer, no internet, or the
         * download fails — the local data is installed. Always followed by a
         * dialog in MainActivity.
         */
        fun enqueueInstall(context: Context, replaceLocal: Boolean = false) {
            val wm = WorkManager.getInstance(context)
            // An unfinished install is kept (Activity recreated, rerun after
            // process death). Anything else under the name — e.g. an update
            // whose import was cut off by process death, leaving the tables
            // half empty — is replaced: it would not show the install dialog
            // nor fall back to local data. A reinstall also keeps a pending
            // reinstall; a real install (no usable data) replaces it.
            val keepTags = if (replaceLocal) setOf(TAG_INSTALL, TAG_REINSTALL) else setOf(TAG_INSTALL)
            val installPending = try {
                wm.getWorkInfosForUniqueWork(WORK_NAME).get()
                    .any { info -> !info.state.isFinished && info.tags.any { it in keepTags } }
            } catch (e: Exception) { false }
            // A reinstall is not tagged TAG_INSTALL: the app has data, so it
            // behaves like an update — silent until the import starts.
            val req = androidx.work.OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .addTag(if (replaceLocal) TAG_REINSTALL else TAG_INSTALL)
                .setInputData(workDataOf(KEY_INSTALL to true, KEY_REPLACE_LOCAL to replaceLocal))
                .build()
            if (!replaceLocal) {
                // A (re)install from scratch: any older unseen result is moot.
                prefs(context).edit().remove(KEY_PENDING_ID).remove(KEY_PENDING_KIND)
                    .remove(KEY_PENDING_RESULT).apply()
            }
            wm.enqueueUniqueWork(WORK_NAME,
                if (installPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
        }

        /** A finished install/update whose message the user has not closed yet. */
        fun hasUnseenResult(context: Context): Boolean {
            val p = pendingResult(context) ?: return false
            return !isAcknowledged(context, p.id)
        }

        /** Why a check must not start now, or null if it may. */
        private fun whyNoCheck(context: Context): String? {
            // While the message about the previous install/update is still
            // waiting to be closed, nothing new starts: the user always knows
            // which data is installed, and dialogs never replace one another.
            // Closing the message calls checkForUpdate again.
            if (hasUnseenResult(context)) return "Previous result not closed yet"
            if (journeyActive()) return "Journey in progress"
            val p = prefs(context)
            val now = System.currentTimeMillis()
            val sinceAttempt = now - p.getLong(KEY_LAST_ATTEMPT_MS, 0L)
            if (sinceAttempt in 0 until MIN_CHECK_INTERVAL_MS) {
                return "Checked ${TimeUnit.MILLISECONDS.toMinutes(sinceAttempt)} min ago"
            }
            val sinceBad = now - p.getLong(KEY_BAD_FEED_MS, 0L)
            if (sinceBad in 0 until BAD_FEED_BACKOFF_MS) {
                return "Last downloaded feed was rejected ${TimeUnit.MILLISECONDS.toHours(sinceBad)} h ago"
            }
            if (mobileDayLimitReached(context)) {
                return "On mobile data and already downloaded today"
            }
            return null
        }

        /**
         * The once-a-day rule for mobile data. The server cannot reliably
         * tell us "unchanged" (no date / version marker, or a marker that
         * announced the same data as new), so a check may mean a full
         * download. On mobile data (any metered network) at most one
         * successful download per calendar day — on Wi-Fi or mobile data,
         * whichever came first. On Wi-Fi there is no daily limit, only the
         * spacing between checks. A day on which the app is not opened has
         * no download.
         */
        private fun mobileDayLimitReached(context: Context): Boolean {
            val p = prefs(context)
            if (!p.getBoolean(KEY_NO_VALIDATOR, false) &&
                !p.getBoolean(KEY_MARKERS_UNRELIABLE, false)) return false
            if (!sameDay(p.getLong(KEY_LAST_DOWNLOAD_MS, 0L), System.currentTimeMillis())) return false
            return isMetered(context)
        }

        /**
         * Called whenever the app comes to the foreground, once the database
         * is known to be populated. Enqueues one check unless [whyNoCheck]
         * gives a reason (unclosed message, journey, last check less than
         * [MIN_CHECK_INTERVAL_MS] ago, rejected feed, mobile-data daily
         * limit) or there is no internet now.
         *
         * Deliberately NOT a PeriodicWorkRequest: nothing is ever scheduled
         * behind the user's back — no launch, no refresh.
         */
        fun checkForUpdate(context: Context) {
            whyNoCheck(context)?.let { FileLogger.d(TAG, "$it — no check now"); return }
            // No connection now: nothing is queued (a queued check would wait
            // for one, possibly until another day). The next opening checks.
            if (!isOnline(context)) { FileLogger.d(TAG, "No internet — no check now"); return }

            FileLogger.i(TAG, "Enqueuing data check")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, updateRequest())
        }

        /**
         * An update run. It declares that it needs the network: Android then
         * keeps the app's network access while the app is in the background
         * (without the declaration, Android 14+ may cut it the moment the user
         * presses Home — seen in the logs as "Software caused connection
         * abort") and runs the work only when there is a connection. If the
         * connection is lost, WorkManager stops the run; the download simply
         * starts again later, and an install already under way is finished
         * first (see [importUninterrupted]).
         */
        private fun updateRequest() = androidx.work.OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
            .setConstraints(Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()

        /**
         * After the user closed "Данните са инсталирани." for an install that
         * had to fall back to the local data (no internet, or the download
         * failed): look for the newer data right away — without the usual
         * spacing between checks. Without internet now, the next opening checks.
         */
        fun updateAfterFallbackInstall(context: Context) {
            if (journeyActive() || !isOnline(context)) return
            FileLogger.i(TAG, "Fallback install acknowledged — looking for newer data now")
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, updateRequest())
        }

        /**
         * Marks the bundled assets as the current data source, using the date
         * baked into assets/gtfs/bundle_date.txt. Called once after the
         * first-run import of bundled data so the freshness clock starts from
         * when the data was actually produced — not from install time. This is
         * used, among other things, to tell whether a newer APK ships
         * newer bundled data than the database holds.
         */
        fun recordBundledDate(context: Context) {
            val bundleMs = readBundleDateMs(context) ?: run {
                // No/invalid bundle date → treat as ancient so we refresh ASAP.
                FileLogger.w(TAG, "No bundle date found; will check for new data")
                0L
            }
            val e = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_OK_MS, bundleMs).putLong(KEY_DATA_DATE_MS, bundleMs)
            // The bundled data's fingerprint, computed the same way as for a
            // download: a later download with the very same timetable is then
            // recognised and not installed (nor announced) again.
            bundledFingerprint(context)?.let {
                e.putString(KEY_FEED_HASH, it)
            } ?: e.remove(KEY_FEED_HASH)
            e.apply()
            FileLogger.i(TAG, "Bundled data dated ${bundleMs}ms; freshness clock set")
        }

        /**
         * Fingerprint of a data set: SHA-256 over "name:sha256(content)" of each
         * file we use, sorted by name — independent of the order of the files
         * in the ZIP.
         */
        private fun fingerprintOf(fileHashes: Map<String, String>): String {
            val d = java.security.MessageDigest.getInstance("SHA-256")
            fileHashes.toSortedMap().forEach { (name, h) -> d.update("$name:$h\n".toByteArray()) }
            return d.digest().joinToString("") { "%02x".format(it) }
        }

        private fun bundledFingerprint(context: Context): String? =
            fingerprintOfFiles { name -> context.assets.open("gtfs/$name") }

        private fun fingerprintOfFiles(open: (String) -> java.io.InputStream): String? = try {
            val hashes = HashMap<String, String>()
            val buf = ByteArray(64 * 1024)
            for (name in FILES_TO_KEEP) {
                val d = java.security.MessageDigest.getInstance("SHA-256")
                val ok = try {
                    open(name).use { inp ->
                        while (true) { val n = inp.read(buf); if (n < 0) break; d.update(buf, 0, n) }
                    }
                    true
                } catch (e: java.io.FileNotFoundException) { false }
                if (ok) hashes[name] = d.digest().joinToString("") { "%02x".format(it) }
            }
            if (hashes.isEmpty()) null else fingerprintOf(hashes)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Fingerprint not computed: ${e.message}")
            null
        }

        /**
         * Whether this app version's bundled data is newer than the data
         * installed.
         */
        /** The installed data's date is known and the bundle is newer. */
        private fun bundleKnownNewer(context: Context): Boolean {
            val bundle = readBundleDateMs(context) ?: return false
            val data = prefs(context).getLong(KEY_DATA_DATE_MS, 0L)
            return data > 0L && bundle > data + TimeUnit.DAYS.toMillis(1)
        }

        fun bundleIsNewerThanData(context: Context): Boolean {
            val bundle = readBundleDateMs(context) ?: return false
            val data = prefs(context).getLong(KEY_DATA_DATE_MS, 0L)
            // Date unknown: nothing to compare — leave the data alone.
            if (data <= 0L) return false
            // A day's tolerance: the bundle is dated by its day (midnight),
            // a download by the server's exact time — the same feed built into
            // the bundle the next morning must not count as newer.
            return bundle > data + TimeUnit.DAYS.toMillis(1)
        }

        private fun recordDownloadedDataDate(context: Context, lastModifiedMs: Long) {
            prefs(context).edit().putLong(KEY_DATA_DATE_MS,
                if (lastModifiedMs > 0L) lastModifiedMs else System.currentTimeMillis()).apply()
        }

        /** Date of the data shipped inside the APK, epoch millis, or null. */
        fun bundledDateMs(context: Context): Long? = readBundleDateMs(context)

        /** Reads assets/gtfs/bundle_date.txt (ISO yyyy-MM-dd) → epoch millis, or null. */
        private fun readBundleDateMs(context: Context): Long? = try {
            val text = context.assets.open("gtfs/bundle_date.txt")
                .bufferedReader().use { it.readText().trim() }
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .parse(text)?.time
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not read bundle_date.txt: ${e.message}")
            null
        }
    }

    /** Outcome of the conditional request. */
    private class Validators(val lastModifiedMs: Long, val etag: String?, val hash: String? = null)

    private var downloadStarted = false
    /** The new data started replacing the tables — the dialog is up. */
    private var importStarted = false

    private fun out(result: String) = workDataOf(
        KEY_RESULT to result,
        KEY_VISIBLE to importStarted
    ).also { lastOutput = it }

    /** The output of this run, as last built by [out] / install's done(). */
    private var lastOutput: androidx.work.Data? = null

    override suspend fun doWork(): Result {
        val install = inputData.getBoolean(KEY_INSTALL, false)
        val p = prefs(applicationContext)
        // WorkManager reruns a run it had stopped (e.g. the connection was
        // lost). If that run still finished its install, there is nothing
        // left to do — its result has already been kept for the user.
        if (p.getString(KEY_DONE_WHILE_STOPPED, null) == id.toString()) {
            p.edit().remove(KEY_DONE_WHILE_STOPPED).apply()
            FileLogger.i(TAG, "Rerun of a run that already finished its install — nothing to do")
            return Result.success(out(RESULT_SKIPPED))
        }
        try {
            return if (install) install() else update()
        } finally {
            // Keep the result the user must see in the app's own storage too
            // (see [pendingResult]). Only for runs that showed a dialog — also
            // when the run itself was stopped after its install finished.
            val o = lastOutput
            if (o != null && o.getBoolean(KEY_VISIBLE, false)) {
                val kind = when {
                    !install -> KIND_UPDATE
                    inputData.getBoolean(KEY_REPLACE_LOCAL, false) -> KIND_REINSTALL
                    else -> KIND_INSTALL
                }
                p.edit()
                    .putString(KEY_PENDING_ID, id.toString())
                    .putString(KEY_PENDING_KIND, kind)
                    .putString(KEY_PENDING_RESULT, o.getString(KEY_RESULT))
                    .commit()
            }
        }
    }

    /**
     * Runs an install step (writing the tables, and the bookkeeping right
     * after) to the end even if WorkManager stops the run meanwhile — e.g.
     * because the connection was lost, which the install itself does not
     * need. Stopping it halfway would leave the tables half filled.
     */
    private suspend fun <T> importUninterrupted(block: suspend () -> T): T =
        withContext(NonCancellable) {
            block().also {
                if (isStopped) {
                    prefs(applicationContext).edit()
                        .putString(KEY_DONE_WHILE_STOPPED, id.toString()).commit()
                }
            }
        }

    /**
     * First-run install; see [enqueueInstall]. Never leaves the app without
     * data if it can help it: every path that does not install new data
     * falls back to the local set.
     */
    private suspend fun install(): Result = withContext(Dispatchers.IO) {
        val ctx        = applicationContext
        val finalDir   = File(ctx.filesDir, GtfsRepository.EXTERNAL_DIR_NAME)
        val tmpDir     = runTmpDir(ctx)
        val backupDir  = File(ctx.filesDir, "${GtfsRepository.EXTERNAL_DIR_NAME}.bak")
        val replaceLocal = inputData.getBoolean(KEY_REPLACE_LOCAL, false)
        // First run: the dialog is up for the whole install. A reinstall
        // (the app has data) is shown only once its import has started.
        fun done(result: String) = workDataOf(KEY_RESULT to result,
            KEY_VISIBLE to (!replaceLocal || importStarted)).also { lastOutput = it }

        try {
            setProgress(workDataOf())
            var waited = 0L
            while (gtfsRepo.isImportBusy()) {
                if (waited >= BUSY_WAIT_MAX_MS) {
                    FileLogger.e(TAG, "Install: another import still running — giving up")
                    return@withContext Result.failure(done(RESULT_FAILED))
                }
                kotlinx.coroutines.delay(1_000L); waited += 1_000L
            }
            // Never replace tables during a journey (possible for a reinstall,
            // or a repair after an unfinished import); the next app start
            // queues it again. A real first run has no data, so no journey.
            if (journeyActive()) {
                FileLogger.i(TAG, "Install: journey in progress — postponed")
                return@withContext Result.success(out(RESULT_SKIPPED))
            }
            if (replaceLocal) {
                // Reinstall because the app's bundled data is (or may be)
                // newer. The app works meanwhile. The downloaded set is kept
                // until it is known what replaces it — see below.
            } else if (gtfsRepo.isDatabaseReady()) {
                // Filled meanwhile (a duplicate run, or an import that just
                // ended): nothing to install, nothing to tell the user.
                FileLogger.i(TAG, "Install: database already populated — nothing to do")
                return@withContext Result.success(out(RESULT_SKIPPED))
            }
            FileLogger.i(TAG, "Install: checking the server for data newer than the local set")
            tmpDir.deleteRecursively()
            backupDir.deleteRecursively()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Install: could not start: ${e.message}", e)
            return@withContext Result.failure(done(RESULT_FAILED))
        }

        var serverSaidCurrent = false
        // The server's feed itself was bad (rejected / failed to parse): an
        // update right after the install would only fetch the same feed again.
        var badFeed = false
        try {
            tmpDir.mkdirs()
            // A reinstall asks for anything newer than the new bundle
            // (not than the downloaded set, whose markers it ignores).
            val v = downloadAndExtract(tmpDir, ctx, quick = true,
                maxDownloadMs = INSTALL_DOWNLOAD_MAX_MS,
                forceBaselineMs = if (replaceLocal) (readBundleDateMs(ctx) ?: 0L) else null)
            if (v == null) {
                serverSaidCurrent = true
            } else {
                tmpDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name !in FILES_TO_KEEP) f.delete()
                }
                val missing = REQUIRED_FILES.filter { !File(tmpDir, it).exists() }
                if (missing.isNotEmpty()) {
                    FileLogger.e(TAG, "Install: downloaded feed rejected, missing $missing")
                    markBadFeed(ctx)
                    badFeed = true
                } else {
                    mayNotImport()?.let { return@withContext it }
                    if (finalDir.exists() && !finalDir.renameTo(backupDir)) {
                        throw Exception("could not move old data to backup")
                    }
                    if (!tmpDir.renameTo(finalDir)) {
                        backupDir.renameTo(finalDir)
                        throw Exception("could not move new data into place")
                    }
                    importStarted = true
                    setProgress(workDataOf(KEY_PHASE to PHASE_IMPORT))
                    val installed: Result? = importUninterrupted {
                        try {
                            gtfsRepo.loadStaticData()
                            backupDir.deleteRecursively()
                            storeValidators(ctx, v)
                            recordDownloadedDataDate(ctx, v.lastModifiedMs)
                            prefs(ctx).edit()
                                .putLong(KEY_LAST_DOWNLOAD_MS, System.currentTimeMillis())
                                .apply()
                            recordSuccess(ctx)
                            FileLogger.i(TAG, "Install: newest data installed")
                            Result.success(done(RESULT_INSTALLED_NEW))
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "Install: new data failed to parse: ${e.message}")
                            finalDir.deleteRecursively()
                            backupDir.renameTo(finalDir)
                            markBadFeed(ctx)
                            badFeed = true
                            null
                        }
                    }
                    if (installed != null) return@withContext installed
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Closing the result message looks for newer
            // data at once. A damaged archive counts as a bad feed (24 h).
            FileLogger.w(TAG, "Install: newer data not available (${e.message}) — using local data")
            if (e is java.util.zip.ZipException) {
                markBadFeed(ctx)
                badFeed = true
            }
        } finally {
            tmpDir.deleteRecursively()
        }

        if (replaceLocal && !importStarted) {
            // Reinstall without newer server data. The new bundle replaces the
            // downloaded set only when it is known to be at least as new:
            // the server said it has nothing newer than the bundle, or the
            // installed data's date is known and older. Otherwise (offline,
            // download failed, date unknown) keep everything as it is and try
            // again at the next start — never trade newer data for older.
            if (serverSaidCurrent || bundleKnownNewer(ctx)) {
                finalDir.deleteRecursively()
                prefs(ctx).edit().remove(KEY_LAST_MODIFIED_MS).remove(KEY_ETAG)
                    .remove(KEY_FEED_HASH).apply()
            } else {
                FileLogger.i(TAG, "Reinstall: cannot tell whether the bundle is newer — keeping current data")
                return@withContext Result.success(out(RESULT_DEFERRED))
            }
        }

        // Local data: a previously downloaded set if there is one, else the
        // data bundled in the APK.
        mayNotImport()?.let { return@withContext it }
        importStarted = true
        setProgress(workDataOf(KEY_PHASE to PHASE_IMPORT))
        val usingBundled = gtfsRepo.getActiveDataDir() == null
        importUninterrupted {
            try {
                gtfsRepo.loadStaticData()
                if (usingBundled) recordBundledDate(ctx)
                FileLogger.i(TAG, "Install: local data installed (${if (usingBundled) "bundled" else "downloaded"})")
                // FALLBACK = newer data may exist but could not be fetched;
                // closing the message then looks for it at once. A bad feed
                // counts as CURRENT: fetching it again would not help (the
                // normal checks retry after the 24-hour wait).
                Result.success(done(
                    if (serverSaidCurrent || badFeed) RESULT_INSTALLED_CURRENT else RESULT_INSTALLED_FALLBACK))
            } catch (e: Exception) {
                FileLogger.e(TAG, "Install: local data failed: ${e.message}", e)
                Result.failure(done(RESULT_FAILED))
            }
        }
    }

    private suspend fun update(): Result = withContext(Dispatchers.IO) {
        val ctx        = applicationContext
        val finalDir   = File(ctx.filesDir, GtfsRepository.EXTERNAL_DIR_NAME)
        val tmpDir     = runTmpDir(ctx)
        val backupDir  = File(ctx.filesDir, "${GtfsRepository.EXTERNAL_DIR_NAME}.bak")
        try {
            // WorkManager keeps the last progress of a stopped run; clear it
            // so the UI never shows a phase this run has not reached.
            setProgress(workDataOf())
            if (runAttemptCount >= MAX_RUNS) {
                FileLogger.i(TAG, "Already $runAttemptCount attempts — giving up until the next opening")
                return@withContext Result.failure(out(RESULT_FAILED))
            }
            if (journeyActive()) {
                FileLogger.i(TAG, "Journey in progress — data check skipped")
                return@withContext Result.success(out(RESULT_SKIPPED))
            }
            // E.g. the first-run or bundled re-import at app start: it reads
            // the data directory this run would rename underneath it. Wait
            // for it (usually seconds, ~30 s for a full import) rather than
            // skip, or the check would not happen until the next opening.
            if (!waitUntilRepoIdle(UPDATE_BUSY_WAIT_MAX_MS)) {
                FileLogger.i(TAG, "Another import is still running — data check skipped")
                return@withContext Result.success(out(RESULT_SKIPPED))
            }
            // A rerun after this update was stopped mid-import: the tables
            // are half filled. Complete files are in place (the new set, or
            // the bundle), so install them first — offline too.
            if (!gtfsRepo.isDatabaseReady()) {
                FileLogger.w(TAG, "Previous import unfinished — installing the local data first")
                importStarted = true
                setProgress(workDataOf(KEY_PHASE to PHASE_IMPORT))
                return@withContext importUninterrupted {
                    try {
                        val usingBundled = gtfsRepo.getActiveDataDir() == null
                        gtfsRepo.loadStaticData()
                        if (usingBundled) recordBundledDate(ctx)
                        // Which set it was (new, old or bundled) is not known
                        // here: say only that the data is installed.
                        Result.success(out(RESULT_REPAIRED))
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Local install failed: ${e.message}", e)
                        Result.failure(out(RESULT_FAILED_NO_DATA))
                    }
                }
            }
            // The check was allowed on Wi-Fi, but the phone may be on mobile
            // data by now (Wi-Fi lost while the run waited): the mobile-data
            // rule applies again.
            if (mobileDayLimitReached(ctx)) {
                FileLogger.i(TAG, "On mobile data and already downloaded today — data check skipped")
                return@withContext Result.success(out(RESULT_SKIPPED))
            }
            FileLogger.i(TAG, "GTFS check starting")

            // Clean any leftovers from previous failed runs
            tmpDir.deleteRecursively()
            backupDir.deleteRecursively()

            // 1) Ask whether the feed changed; download + extract only if so.
            tmpDir.mkdirs()
            val validators = downloadAndExtract(tmpDir, ctx,
                maxDownloadMs = UPDATE_DOWNLOAD_MAX_MS)
            if (validators == null) {
                FileLogger.i(TAG, "Feed unchanged — keeping current data")
                tmpDir.deleteRecursively()
                // The server itself said "unchanged" (304, same ETag or an
                // older date): its markers work, so the mobile-data daily limit can
                // go. A real new feed being different proves nothing — a
                // server that stamps every response "new" does that too.
                prefs(ctx).edit().putBoolean(KEY_MARKERS_UNRELIABLE, false).apply()
                recordSuccess(ctx)
                return@withContext Result.success(out(RESULT_UNCHANGED))
            }

            // 1.2) Same content as the feed we already hold: the server's
            //      change markers misled us. Keep the current data (no
            //      re-import); on mobile data allow one download per day
            //      until the server itself answers "unchanged".
            val oldHash = prefs(ctx).getString(KEY_FEED_HASH, null)
            if (validators.hash != null && validators.hash == oldHash) {
                FileLogger.w(TAG, "Downloaded feed is identical to the current one — " +
                    "server change markers unreliable; on mobile data once a day")
                tmpDir.deleteRecursively()
                prefs(ctx).edit()
                    .putBoolean(KEY_MARKERS_UNRELIABLE, true)
                    .putLong(KEY_LAST_DOWNLOAD_MS, System.currentTimeMillis())
                    .apply()
                storeValidators(ctx, validators)
                recordSuccess(ctx)
                return@withContext Result.success(out(RESULT_UNCHANGED))
            }

            // 1.5) Delete unused files to save ~50 MB of disk space
            tmpDir.listFiles()?.forEach { f ->
                if (f.isFile && f.name !in FILES_TO_KEEP) {
                    val size = f.length()
                    if (f.delete()) FileLogger.d(TAG, "Discarded unused ${f.name} ($size bytes)")
                }
            }

            // 2) Sanity check — refuse to swap in a partial dataset
            val missing = REQUIRED_FILES.filter { !File(tmpDir, it).exists() }
            if (missing.isNotEmpty()) {
                FileLogger.e(TAG, "Update rejected: missing files $missing")
                tmpDir.deleteRecursively()
                markBadFeed(ctx)
                return@withContext Result.failure(out(RESULT_FAILED))
            }

            // A journey started while we were downloading: do not pull the
            // tables from under the tracker. The download is discarded and
            // the change markers are not stored, so the next check after the
            // journey downloads it again.
            if (journeyActive()) {
                FileLogger.i(TAG, "Journey started during download — import deferred")
                tmpDir.deleteRecursively()
                return@withContext Result.success(out(RESULT_DEFERRED))
            }

            if (gtfsRepo.isImportBusy()) {
                // Something started an import while we were downloading.
                // Never swap the directory under it; the next check downloads
                // again (markers not stored).
                FileLogger.i(TAG, "Another import started during download — update deferred")
                tmpDir.deleteRecursively()
                return@withContext Result.failure(out(RESULT_FAILED))
            }

            importStarted = true
            setProgress(workDataOf(KEY_PHASE to PHASE_IMPORT))

            // From here on nothing needs the network: finish even if the run
            // is stopped meanwhile (see importUninterrupted).
            return@withContext importUninterrupted { run {
            // 3) Atomic swap: keep old data as backup until new data parses OK
            if (finalDir.exists()) {
                if (!finalDir.renameTo(backupDir)) {
                    FileLogger.e(TAG, "Failed to move old data to backup")
                    tmpDir.deleteRecursively()
                    return@run Result.failure(out(RESULT_FAILED))
                }
            }
            if (!tmpDir.renameTo(finalDir)) {
                FileLogger.e(TAG, "Failed to rename tmp → final; restoring backup")
                backupDir.renameTo(finalDir)
                return@run Result.failure(out(RESULT_FAILED))
            }

            // 4) Re-import into Room
            try {
                gtfsRepo.loadStaticData()
                backupDir.deleteRecursively()
                // Only now, with the new data in place, remember the server's
                // change markers. Storing them earlier (as before) meant that
                // a download cut off halfway made every later check answer
                // "not modified", and the new feed was never fetched.
                storeValidators(ctx, validators)
                recordDownloadedDataDate(ctx, validators.lastModifiedMs)
                prefs(ctx).edit().putLong(KEY_LAST_DOWNLOAD_MS, System.currentTimeMillis()).apply()
                recordSuccess(ctx)
                FileLogger.i(TAG, "GTFS refresh complete")
                Result.success(out(RESULT_UPDATED))
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Not expected here (the install runs uninterrupted); if it
                // ever happens, it is not a parse failure — don't roll back.
                FileLogger.i(TAG, "Import cancelled")
                throw e
            } catch (e: Exception) {
                // Parse failed — roll back to old data
                FileLogger.e(TAG, "Parse failed, rolling back: ${e.message}")
                finalDir.deleteRecursively()
                backupDir.renameTo(finalDir)
                var reloadOk = false
                try {
                    gtfsRepo.loadStaticData()  // reload old data
                    reloadOk = true
                } catch (c: kotlinx.coroutines.CancellationException) {
                    // Stopped by WorkManager mid-rollback: let it stop. The
                    // unfinished-import flag makes the next start reinstall.
                    throw c
                } catch (reloadError: Exception) {
                    // The rollback reload itself failed. Don't let it mask the
                    // real parse error above — just log it. The DB may be
                    // empty until then, but isDatabaseReady() on next launch
                    // will re-import.
                    FileLogger.e(TAG, "Rollback reload also failed: ${reloadError.message}")
                }
                markBadFeed(ctx)
                // Only claim "continuing with the old data" if it is back.
                Result.failure(out(if (reloadOk) RESULT_FAILED else RESULT_FAILED_NO_DATA))
            }
            } }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Update failed: ${e.message}", e)
            tmpDir.deleteRecursively()
            // A damaged archive is a bad feed, not a broken connection: the
            // same file would come again — wait 24 h, no retries.
            if (e is java.util.zip.ZipException) {
                markBadFeed(ctx)
                return@withContext Result.failure(out(RESULT_FAILED))
            }
            // Anything else (connection lost, too slow, server error): quietly
            // give up; the next opening (an hour later at the earliest) tries again.
            Result.failure(out(RESULT_FAILED))
        }
    }

    /**
     * Last check before an install replaces the tables. The download may
     * have taken minutes, and with the app usable meanwhile (a reinstall)
     * a journey or another import may have started. Returns the result to
     * end the run with, or null to go ahead.
     */
    private suspend fun mayNotImport(): Result? {
        val replaceLocal = inputData.getBoolean(KEY_REPLACE_LOCAL, false)
        if (!replaceLocal) {
            // First run / repair: another import is filling the same tables;
            // wait for it rather than give up.
            var waited = 0L
            while (gtfsRepo.isImportBusy() && waited < UPDATE_BUSY_WAIT_MAX_MS) {
                kotlinx.coroutines.delay(1_000L); waited += 1_000L
            }
        }
        if (journeyActive()) {
            FileLogger.i(TAG, "Install: journey started meanwhile — postponed")
            return Result.success(out(RESULT_DEFERRED))
        }
        if (gtfsRepo.isImportBusy()) {
            FileLogger.i(TAG, "Install: another import is running — not replacing the tables")
            return if (replaceLocal) Result.success(out(RESULT_DEFERRED))
                   else Result.failure(workDataOf(KEY_RESULT to RESULT_FAILED, KEY_VISIBLE to true)
                                        .also { lastOutput = it })
        }
        return null
    }

    /** Waits (up to [maxMs]) for other imports to finish. */
    private suspend fun waitUntilRepoIdle(maxMs: Long): Boolean {
        var waited = 0L
        while (gtfsRepo.isImportBusy()) {
            if (waited >= maxMs) return false
            kotlinx.coroutines.delay(1_000L)
            waited += 1_000L
        }
        return !journeyActive()
    }

    /**
     * This run's own download folder. Per run, so that a run stopped by
     * WorkManager — which notices only at its next read — cannot delete the
     * folder its replacement is already writing into. Folders left by
     * earlier runs are removed here.
     */
    private fun runTmpDir(ctx: Context): File {
        val prefix = "${GtfsRepository.EXTERNAL_DIR_NAME}.tmp"
        val own = File(ctx.filesDir, "$prefix-$id")
        ctx.filesDir.listFiles()?.forEach { f ->
            if (f.isDirectory && f.name.startsWith(prefix) && f.name != own.name) {
                f.deleteRecursively()
            }
        }
        return own
    }

    private fun markBadFeed(ctx: Context) {
        prefs(ctx).edit().putLong(KEY_BAD_FEED_MS, System.currentTimeMillis()).apply()
    }

    private fun storeValidators(ctx: Context, v: Validators) {
        val e = prefs(ctx).edit()
        e.putLong(KEY_LAST_MODIFIED_MS, v.lastModifiedMs)
        e.putString(KEY_ETAG, v.etag)
        val noValidator = v.lastModifiedMs <= 0L && v.etag == null
        e.putBoolean(KEY_NO_VALIDATOR, noValidator)
        if (v.hash != null) e.putString(KEY_FEED_HASH, v.hash)
        e.apply()
        FileLogger.i(TAG, if (noValidator)
            "Server gives no version marker — on mobile data at most one download a day"
            else "Server version marker stored — later checks cost no download when unchanged")
    }

    /**
     * The date our current data corresponds to, as the server's
     * Last-Modified, or 0 if unknown. While the app still runs on the data
     * bundled in the APK, falls back to the bundle's own date, so a fresh
     * install whose bundled data is already current does not download it
     * again.
     */
    private fun baselineMs(ctx: Context): Long {
        val p = prefs(ctx)
        val stored = p.getLong(KEY_LAST_MODIFIED_MS, 0L)
        if (gtfsRepo.getActiveDataDir() == null) {
            val bundle = readBundleDateMs(ctx) ?: 0L
            return maxOf(stored, bundle)
        }
        return stored
    }

    /**
     * Asks the server for the feed ZIP, conditionally, and writes its entries
     * into [target]. Returns:
     *   - null → the feed is unchanged since our data; [target] left empty.
     *     That is either a 304 answer, or a 200 whose Last-Modified / ETag
     *     shows the same feed (in case the server ignores the conditions) —
     *     then the body is never read.
     *   - the server's change markers → new data is in [target]
     */
    private suspend fun downloadAndExtract(
        target: File, ctx: Context, quick: Boolean = false, maxDownloadMs: Long,
        forceBaselineMs: Long? = null
    ): Validators? {
        val p = prefs(ctx)
        val baseline = forceBaselineMs ?: baselineMs(ctx)
        val storedEtag = if (forceBaselineMs != null) null else p.getString(KEY_ETAG, null)
        val conn = URL(FEED_URL).openConnection() as HttpURLConnection
        // On first run the user is waiting in front of a dialog: a server
        // that does not answer quickly means installing the local data.
        conn.connectTimeout = if (quick) 10_000 else 30_000
        conn.readTimeout    = if (quick) 20_000 else 120_000
        conn.requestMethod  = "GET"
        if (baseline > 0L) conn.ifModifiedSince = baseline
        storedEtag?.let { conn.setRequestProperty("If-None-Match", it) }
        conn.connect()

        try {
            val code = conn.responseCode
            // The server answered: from here on this counts as a check for
            // the one-hour spacing. (No network → exception above → the
            // next opening of the app tries again.)
            p.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply()
            // Diagnostics: what the server tells us about its version, so the
            // log shows whether "unchanged" can be recognised without a
            // download.
            FileLogger.i(TAG, "Server answer: HTTP $code, " +
                "Last-Modified=${conn.getHeaderField("Last-Modified") ?: "none"}, " +
                "ETag=${conn.getHeaderField("ETag") ?: "none"}, " +
                "Content-Length=${conn.getHeaderField("Content-Length") ?: "none"} " +
                "(sent If-Modified-Since=${if (baseline > 0L) baseline else "none"}, " +
                "If-None-Match=${storedEtag ?: "none"})")
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return null
            }
            if (code != 200) {
                throw Exception("HTTP $code")
            }

            val lastModified = conn.getHeaderFieldDate("Last-Modified", 0L)
            val etag = conn.getHeaderField("ETag")
            if (storedEtag != null && etag != null && etag == storedEtag) {
                FileLogger.i(TAG, "Server sent the full feed but its ETag is unchanged")
                return null
            }
            if (baseline > 0L && lastModified > 0L && lastModified <= baseline) {
                FileLogger.i(TAG, "Server sent the full feed but it is not newer " +
                    "($lastModified ≤ $baseline)")
                // Remember it so the next check can be answered with 304.
                // Safe: our data is at least this recent.
                if (p.getLong(KEY_LAST_MODIFIED_MS, 0L) <= 0L) {
                    storeValidators(ctx, Validators(lastModified, etag))
                }
                return null
            }

            FileLogger.i(TAG, "New feed available (Last-Modified $lastModified, " +
                "size ${conn.contentLengthLong} bytes) — downloading")
            downloadStarted = true
            setProgress(workDataOf(KEY_PHASE to PHASE_DOWNLOAD))

            // Fingerprint of the files we actually use (see fingerprintOf).
            // Hashing the ZIP bytes instead would differ on every rebuild of
            // the archive (entry timestamps) even when the timetable is the same.
            val fileHashes = HashMap<String, String>()
            val deadline = System.currentTimeMillis() + maxDownloadMs
            // Counts the bytes actually received (the ZIP as sent), for the log.
            var received = 0L
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                override fun read(): Int = super.read().also { if (it >= 0) received++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it > 0) received += it }
            }
            val startMs = System.currentTimeMillis()
            ZipInputStream(counting.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Avoid path traversal — strip any leading dirs
                        val safeName = File(entry.name).name
                        val outFile  = File(target, safeName)
                        val fingerprint = safeName in FILES_TO_KEEP
                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                        outFile.outputStream().buffered().use { out ->
                            while (true) {
                                // Stopped by WorkManager: stop writing at once,
                                // or this orphaned download would keep writing
                                // into the folder the rerun starts afresh.
                                if (isStopped) throw kotlinx.coroutines.CancellationException("worker stopped")
                                if (System.currentTimeMillis() > deadline) {
                                    throw Exception("download too slow — over ${maxDownloadMs / 1000} s")
                                }
                                val n = zip.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                if (fingerprint) digest.update(buf, 0, n)
                            }
                        }
                        if (fingerprint) fileHashes[safeName] = digest.digest().joinToString("") { "%02x".format(it) }
                        FileLogger.d(TAG, "Extracted ${entry.name} (${outFile.length()} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val hash = fingerprintOf(fileHashes)
            FileLogger.i(TAG, "Downloaded ${received} bytes (%.1f MB) in %.1f s".format(
                java.util.Locale.US, received / 1_048_576.0,
                (System.currentTimeMillis() - startMs) / 1000.0))
            return Validators(lastModified, etag, hash)
        } finally {
            conn.disconnect()
        }
    }
}
