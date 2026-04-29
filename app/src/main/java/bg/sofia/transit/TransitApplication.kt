package bg.sofia.transit

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.worker.GtfsUpdateWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TransitApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Initialise in-app log file capture before anything else logs
        FileLogger.init(this)
        // Schedule weekly static data update
        GtfsUpdateWorker.scheduleWeekly(this)
    }
}
