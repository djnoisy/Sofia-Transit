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
 *   1. Downloads the GTFS static ZIP from the official Sofia portal
 *   2. Atomically extracts it into filesDir/gtfs/  (via a tmp dir + rename)
 *   3. Re-imports the new data into the Room database
 *
 * Triggered only by the user: either by opening the app when the data has
 * gone stale (see [scheduleIfStale], Wi-Fi only) or by the manual button in
 * Diagnostics (see [runNow], any connection). Nothing runs unless the app is
 * launched.
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
        private const val WORK_NAME = "gtfs_refresh"
        private const val FEED_URL  = "https://gtfs.sofiatraffic.bg/api/v1/static"

        private const val PREFS_NAME     = "gtfs_update"
        private const val KEY_LAST_OK_MS = "last_success_ms"

        /** How old the data may get before we offer to refresh it. */
        private val MAX_AGE_MS = TimeUnit.DAYS.toMillis(7)

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

        /** Timestamp of the last successful refresh, 0 if never. */
        fun lastSuccessMs(context: Context): Long =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_OK_MS, 0L)

        private fun recordSuccess(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_OK_MS, System.currentTimeMillis()).apply()
        }

        /**
         * Called when the user opens the app, once the database is known to be
         * populated. If the data is older than [MAX_AGE_MS] it enqueues a
         * single refresh; otherwise it does nothing at all.
         *
         * Deliberately NOT a PeriodicWorkRequest any more. A periodic job runs
         * whether or not the user ever opens the app, and its first occurrence
         * fires the instant it is enqueued — which is what collided with the
         * first-run import. Here nothing is ever scheduled behind the user's
         * back: no launch, no refresh.
         *
         * The refresh only runs on an unmetered connection. The feed ZIP is
         * tens of megabytes and must never be pulled over mobile data without
         * the user asking for it — the manual button in Diagnostics is the way
         * to force it on any connection.
         */
        fun scheduleIfStale(context: Context) {
            val last = lastSuccessMs(context)
            val age  = System.currentTimeMillis() - last
            if (last != 0L && age < MAX_AGE_MS) {
                FileLogger.d(TAG, "Data is ${TimeUnit.MILLISECONDS.toDays(age)} days old — no refresh needed")
                return
            }

            FileLogger.i(TAG, if (last == 0L) "No refresh on record — enqueuing first refresh"
                              else "Data older than 7 days — enqueuing refresh")

            val req = androidx.work.OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        /**
         * Schedules a one-time refresh of the GTFS static data. Used by the
         * Diagnostics screen so the user can force a fresh download on demand.
         * Unlike [scheduleIfStale] this accepts any connection — the user has
         * explicitly asked for it, so mobile data is their call to make.
         * Returns the work ID so the caller can observe progress.
         */
        fun runNow(context: Context): java.util.UUID {
            val req = androidx.work.OneTimeWorkRequestBuilder<GtfsUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(req)
            return req.id
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            FileLogger.i(TAG, "GTFS refresh starting")

            val ctx        = applicationContext
            val finalDir   = File(ctx.filesDir, GtfsRepository.EXTERNAL_DIR_NAME)
            val tmpDir     = File(ctx.filesDir, "${GtfsRepository.EXTERNAL_DIR_NAME}.tmp")
            val backupDir  = File(ctx.filesDir, "${GtfsRepository.EXTERNAL_DIR_NAME}.bak")

            // Clean any leftovers from previous failed runs
            tmpDir.deleteRecursively()
            backupDir.deleteRecursively()

            // 1) Download + extract into tmp dir
            tmpDir.mkdirs()
            downloadAndExtract(tmpDir)

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
                return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
            }

            // 3) Atomic swap: keep old data as backup until new data parses OK
            if (finalDir.exists()) {
                if (!finalDir.renameTo(backupDir)) {
                    FileLogger.e(TAG, "Failed to move old data to backup")
                    tmpDir.deleteRecursively()
                    return@withContext Result.failure()
                }
            }
            if (!tmpDir.renameTo(finalDir)) {
                FileLogger.e(TAG, "Failed to rename tmp → final; restoring backup")
                backupDir.renameTo(finalDir)
                return@withContext Result.failure()
            }

            // 4) Re-import into Room
            try {
                gtfsRepo.loadStaticData()
                backupDir.deleteRecursively()
                recordSuccess(ctx)
                FileLogger.i(TAG, "GTFS refresh complete")
                Result.success()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // WorkManager stopped us (constraints lost, system pressure,
                // timeout). This is NOT a parse failure: rolling back here
                // would try to run yet another import inside an already
                // cancelled scope, which just throws again — exactly the
                // confusing "Parse failed: Job was cancelled" pair we saw in
                // the logs. Leave the data alone and let WorkManager retry.
                FileLogger.i(TAG, "Import cancelled by WorkManager; will retry")
                throw e
            } catch (e: Exception) {
                // Parse failed — roll back to old data
                FileLogger.e(TAG, "Parse failed, rolling back: ${e.message}")
                finalDir.deleteRecursively()
                backupDir.renameTo(finalDir)
                gtfsRepo.loadStaticData()  // reload old data
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "Update failed: ${e.message}", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    /** Streams the ZIP from FEED_URL and writes its entries into [target]. */
    private fun downloadAndExtract(target: File) {
        val conn = URL(FEED_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout    = 120_000
        conn.requestMethod  = "GET"
        conn.connect()

        try {
            if (conn.responseCode != 200) {
                throw Exception("HTTP ${conn.responseCode}")
            }

            ZipInputStream(conn.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        // Avoid path traversal — strip any leading dirs
                        val safeName = File(entry.name).name
                        val outFile  = File(target, safeName)
                        outFile.outputStream().buffered().use { out -> zip.copyTo(out) }
                        FileLogger.d(TAG, "Extracted ${entry.name} (${outFile.length()} bytes)")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}

