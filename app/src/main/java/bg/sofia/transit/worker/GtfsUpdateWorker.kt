package bg.sofia.transit.worker

import android.content.Context
import bg.sofia.transit.util.FileLogger
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import bg.sofia.transit.data.repository.GtfsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
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
 * over any connection — Wi-Fi or mobile data. An unchanged feed costs one
 * tiny request; the full download happens only when there is new data.
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
        /** Marker stored by earlier versions; no longer trusted, removed on the next update. */
        private const val KEY_LAST_MODIFIED = "last_modified"
        private const val KEY_LAST_MODIFIED_MS = "last_modified_ms"
        private const val KEY_ETAG = "etag"
        /** Start time of the last run that actually contacted the server. */
        private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
        /** Set when the server sent neither Last-Modified nor ETag. */
        private const val KEY_NO_VALIDATOR = "no_validator"
        /** SHA-256 of the ZIP behind the data we hold. */
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
         * count, so once-a-day mode may try again the same day.
         */
        private const val KEY_LAST_DOWNLOAD_MS = "last_download_ms"
        /**
         * When a downloaded feed was rejected (files missing, parse error).
         * The same broken feed would otherwise be pulled again at every
         * check; after such a failure we wait [BAD_FEED_BACKOFF_MS].
         */
        private const val KEY_BAD_FEED_MS = "bad_feed_ms"
        /**
         * When a download broke off (connection lost midway). On a flaky
         * mobile connection the next attempt waits [DOWNLOAD_FAIL_BACKOFF_MS]
         * instead of pulling tens of MB again every 30 minutes.
         */
        private const val KEY_DOWNLOAD_FAIL_MS = "download_fail_ms"
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
         * each time; half an hour still catches a new feed the same day.
         */
        private val MIN_CHECK_INTERVAL_MS = TimeUnit.MINUTES.toMillis(30)


        private val BAD_FEED_BACKOFF_MS = TimeUnit.HOURS.toMillis(24)
        private val DOWNLOAD_FAIL_BACKOFF_MS = TimeUnit.HOURS.toMillis(2)

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
            prefs(context).edit().putString(KEY_ACK_WORK_ID, id.toString()).apply()
        }

        private fun sameDay(a: Long, b: Long): Boolean {
            if (a <= 0L) return false
            val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
                ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
        }

        private fun journeyActive(): Boolean =
            bg.sofia.transit.service.JourneyService.trackingState.value is
                bg.sofia.transit.service.JourneyService.TrackingState.Tracking

        /**
         * Called whenever the app comes to the foreground, once the database
         * is known to be populated. Enqueues one check unless:
         *   - a journey is being tracked — replacing the tables mid-journey
         *     would leave the tracker querying half-empty tables; the check
         *     runs the next time the app is opened after the journey;
         *   - the last check was less than [MIN_CHECK_INTERVAL_MS] ago.
         *
         * The check itself is cheap: the server answers "not modified" when
         * the feed is unchanged, and only new data is downloaded. It runs on
         * any network, mobile data included.
         *
         * Deliberately NOT a PeriodicWorkRequest: nothing is ever scheduled
         * behind the user's back — no launch, no refresh.
         */
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
            wm.enqueueUniqueWork(WORK_NAME,
                if (installPending) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE, req)
        }

        fun checkForUpdate(context: Context) {
            if (journeyActive()) {
                FileLogger.d(TAG, "Journey in progress — data check postponed")
                return
            }
            val p = prefs(context)
            val now = System.currentTimeMillis()
            val sinceAttempt = now - p.getLong(KEY_LAST_ATTEMPT_MS, 0L)
            if (sinceAttempt in 0 until MIN_CHECK_INTERVAL_MS) {
                FileLogger.d(TAG, "Checked ${TimeUnit.MILLISECONDS.toMinutes(sinceAttempt)} min ago — no check now")
                return
            }
            val sinceBad = now - p.getLong(KEY_BAD_FEED_MS, 0L)
            if (sinceBad in 0 until BAD_FEED_BACKOFF_MS) {
                FileLogger.d(TAG, "Last downloaded feed was rejected " +
                    "${TimeUnit.MILLISECONDS.toHours(sinceBad)} h ago — no check now")
                return
            }
            val sinceDlFail = now - p.getLong(KEY_DOWNLOAD_FAIL_MS, 0L)
            if (sinceDlFail in 0 until DOWNLOAD_FAIL_BACKOFF_MS) {
                FileLogger.d(TAG, "Last download broke off " +
                    "${TimeUnit.MILLISECONDS.toMinutes(sinceDlFail)} min ago — no check now")
                return
            }
            if (p.getBoolean(KEY_NO_VALIDATOR, false) ||
                p.getBoolean(KEY_MARKERS_UNRELIABLE, false)) {
                // The server cannot reliably tell us "unchanged" (no date /
                // version marker, or a marker that announced the same data as
                // new), so a check may mean a full download. Allow one per
                // calendar day: the first opening of the app that day, on any
                // network. A day on which the app is not opened has none.
                if (sameDay(p.getLong(KEY_LAST_DOWNLOAD_MS, 0L), now)) {
                    FileLogger.d(TAG, "Server change markers unreliable; " +
                        "already downloaded today — no check now")
                    return
                }
            }

            FileLogger.i(TAG, "Enqueuing data check")
            // No network constraint on purpose. With one, WorkManager stops
            // the worker the moment the signal drops — including in the middle
            // of the import, after the tables were cleared, leaving the app
            // without data until the network returns. Without a constraint an
            // offline check simply fails at once, silently, and the next
            // opening of the app tries again (a failed connection does not
            // count as a check for the 30-minute spacing).
            val req = androidx.work.OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
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
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_OK_MS, bundleMs).putLong(KEY_DATA_DATE_MS, bundleMs).apply()
            FileLogger.i(TAG, "Bundled data dated ${bundleMs}ms; freshness clock set")
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
            // Installed by a version that did not record the data's date:
            // unknown, so try a reinstall. It asks the server first and
            // replaces nothing unless the result is known to be at least as
            // new. The time of the last check cannot stand in — a "not
            // modified" answer moves it.
            if (data <= 0L) return true
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
    )

    override suspend fun doWork(): Result =
        if (inputData.getBoolean(KEY_INSTALL, false)) install() else update()

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
            KEY_VISIBLE to (!replaceLocal || importStarted))

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
                        return@withContext Result.success(done(RESULT_INSTALLED_NEW))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Install: new data failed to parse: ${e.message}")
                        finalDir.deleteRecursively()
                        backupDir.renameTo(finalDir)
                        markBadFeed(ctx)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "Install: newer data not available (${e.message}) — using local data")
            if (downloadStarted) {
                prefs(ctx).edit().putLong(KEY_DOWNLOAD_FAIL_MS, System.currentTimeMillis()).apply()
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
        try {
            gtfsRepo.loadStaticData()
            if (usingBundled) recordBundledDate(ctx)
            FileLogger.i(TAG, "Install: local data installed (${if (usingBundled) "bundled" else "downloaded"})")
            Result.success(done(
                if (serverSaidCurrent) RESULT_INSTALLED_CURRENT else RESULT_INSTALLED_FALLBACK))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Install: local data failed: ${e.message}", e)
            Result.failure(done(RESULT_FAILED))
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
                return@withContext try {
                    val usingBundled = gtfsRepo.getActiveDataDir() == null
                    gtfsRepo.loadStaticData()
                    if (usingBundled) recordBundledDate(ctx)
                    Result.success(out(RESULT_UPDATED))
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Local install failed: ${e.message}", e)
                    Result.failure(out(RESULT_FAILED))
                }
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
                // older date): its markers work, so the once-a-day limit can
                // go. A real new feed being different proves nothing — a
                // server that stamps every response "new" does that too.
                prefs(ctx).edit().putBoolean(KEY_MARKERS_UNRELIABLE, false).apply()
                recordSuccess(ctx)
                return@withContext Result.success(out(RESULT_UNCHANGED))
            }

            // 1.2) Same content as the feed we already hold: the server's
            //      change markers misled us. Keep the current data (no
            //      re-import) and allow one download per day until an
            //      announced feed really differs.
            val oldHash = prefs(ctx).getString(KEY_FEED_HASH, null)
            if (validators.hash != null && validators.hash == oldHash) {
                FileLogger.w(TAG, "Downloaded feed is identical to the current one — " +
                    "server change markers unreliable; downloads limited to once a day")
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

            // 3) Atomic swap: keep old data as backup until new data parses OK
            if (finalDir.exists()) {
                if (!finalDir.renameTo(backupDir)) {
                    FileLogger.e(TAG, "Failed to move old data to backup")
                    tmpDir.deleteRecursively()
                    return@withContext Result.failure(out(RESULT_FAILED))
                }
            }
            if (!tmpDir.renameTo(finalDir)) {
                FileLogger.e(TAG, "Failed to rename tmp → final; restoring backup")
                backupDir.renameTo(finalDir)
                return@withContext Result.failure(out(RESULT_FAILED))
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
                // WorkManager stopped us (system pressure, timeout). This is NOT a parse failure: rolling back here
                // would try to run yet another import inside an already
                // cancelled scope, which just throws again — exactly the
                // confusing "Parse failed: Job was cancelled" pair we saw in
                // the logs. Leave the data alone; the next check (validators
                // were not stored) downloads and imports it again.
                FileLogger.i(TAG, "Import cancelled by WorkManager")
                throw e
            } catch (e: Exception) {
                // Parse failed — roll back to old data
                FileLogger.e(TAG, "Parse failed, rolling back: ${e.message}")
                finalDir.deleteRecursively()
                backupDir.renameTo(finalDir)
                try {
                    gtfsRepo.loadStaticData()  // reload old data
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
                Result.failure(out(RESULT_FAILED))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // No automatic retry: the next time the app is opened (after
            // MIN_CHECK_INTERVAL_MS) a fresh check is made anyway, and a
            // silent retry could start a download mid-journey.
            FileLogger.e(TAG, "Update failed: ${e.message}", e)
            tmpDir.deleteRecursively()
            if (downloadStarted) {
                prefs(ctx).edit().putLong(KEY_DOWNLOAD_FAIL_MS, System.currentTimeMillis()).apply()
            }
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
                   else Result.failure(workDataOf(KEY_RESULT to RESULT_FAILED, KEY_VISIBLE to true))
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
        e.putBoolean(KEY_NO_VALIDATOR, v.lastModifiedMs <= 0L && v.etag == null)
        if (v.hash != null) e.putString(KEY_FEED_HASH, v.hash)
        e.remove(KEY_LAST_MODIFIED)
        e.apply()
    }

    /**
     * The date our current data corresponds to, as the server's
     * Last-Modified, or 0 if unknown. While the app still runs on the data
     * bundled in the APK, falls back to the bundle's own date, so a fresh
     * install whose bundled data is already current does not download it
     * again.
     *
     * The marker stored by earlier versions ([KEY_LAST_MODIFIED]) is ignored
     * on purpose: they saved it before the download had finished, so it may
     * describe a feed that never made it into the database. Ignoring it costs
     * one full download after the upgrade.
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
            // the 30-minute spacing. (No network → exception above → the
            // next opening of the app tries again.)
            p.edit().putLong(KEY_LAST_ATTEMPT_MS, System.currentTimeMillis()).apply()
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

            // Fingerprint of the files we actually use (names + contents), in
            // archive order. Hashing the ZIP bytes instead would differ on
            // every rebuild of the archive (entry timestamps) even when the
            // timetable is the same.
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val deadline = System.currentTimeMillis() + maxDownloadMs
            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                val buf = ByteArray(64 * 1024)
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Avoid path traversal — strip any leading dirs
                        val safeName = File(entry.name).name
                        val outFile  = File(target, safeName)
                        val fingerprint = safeName in FILES_TO_KEEP
                        if (fingerprint) digest.update(safeName.toByteArray())
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
                        FileLogger.d(TAG, "Extracted ${entry.name} (${outFile.length()} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return Validators(lastModified, etag, hash)
        } finally {
            conn.disconnect()
        }
    }
}
