package bg.sofia.transit.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import bg.sofia.transit.util.FileLogger
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
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
 * Weekly background task that:
 *   1. Downloads the GTFS static ZIP from the official Sofia portal
 *   2. Atomically extracts it into filesDir/gtfs/  (via a tmp dir + rename)
 *   3. Re-imports the new data into the Room database
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
        private const val WORK_NAME = "gtfs_weekly_update"
        private const val FEED_URL  = "https://gtfs.sofiatraffic.bg/api/v1/static"

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

        fun scheduleWeekly(context: Context) {
            val req = PeriodicWorkRequestBuilder<GtfsUpdateWorker>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            FileLogger.i(TAG, "Weekly GTFS update starting")

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
                FileLogger.i(TAG, "Weekly update complete")
                Result.success()
            } catch (e: Exception) {
                // Parse failed — roll back to old data
                FileLogger.e(TAG, "Parse failed, rolling back: ${e.message}")
                finalDir.deleteRecursively()
                backupDir.renameTo(finalDir)
                gtfsRepo.loadStaticData()  // reload old data
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            }
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

/** Re-schedules the weekly job after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            GtfsUpdateWorker.scheduleWeekly(context)
        }
    }
}
