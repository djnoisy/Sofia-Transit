package bg.sofia.transit

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import bg.sofia.transit.util.FileLogger
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
        // No background work is scheduled here on purpose. The GTFS refresh
        // is triggered from MainActivity once the database is confirmed
        // populated (see GtfsUpdateWorker.scheduleIfStale) so that nothing
        // ever runs unless the user actually opens the app, and so that a
        // refresh can never collide with the first-run import.
    }
}
