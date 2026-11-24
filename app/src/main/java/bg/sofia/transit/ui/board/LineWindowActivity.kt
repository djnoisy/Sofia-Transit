package bg.sofia.transit.ui.board

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import bg.sofia.transit.databinding.ActivityStopBoardBinding
import bg.sofia.transit.ui.common.SubWindow
import bg.sofia.transit.ui.lines.ScheduleFragment
import bg.sofia.transit.ui.lines.ScheduleFragmentArgs
import bg.sofia.transit.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * A line's direction at a stop, opened from a row of the "Виртуално табло"
 * (the owner, 24 Nov 2026), in a window of its own over the board, as the
 * board is (SubWindow): "Спирки по маршрута" — the route the direction takes
 * through the stop (RouteStopsFragment) — or "Разписание" — the Lines
 * screen's timetable of the direction at the stop (ScheduleFragment). Back
 * returns to the board.
 */
@AndroidEntryPoint
class LineWindowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStopBoardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityStopBoardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val kind = intent.getStringExtra(EXTRA_KIND)
        SubWindow.setUp(this, binding.toolbar, binding.boardContainer,
            if (kind == KIND_SCHEDULE) "Разписание" else "Спирки по маршрута")

        if (savedInstanceState == null) {
            val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
            val stopId = intent.getStringExtra(EXTRA_STOP_ID)
            if (routeId == null || stopId == null) { finish(); return }
            val routeName = intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty()
            val headsign = intent.getStringExtra(EXTRA_HEADSIGN).orEmpty()
            val fragment = when (kind) {
                KIND_SCHEDULE -> ScheduleFragment().apply {
                    arguments = ScheduleFragmentArgs(routeId = routeId, routeName = routeName, headsign = headsign,
                        stopId = stopId, stopName = intent.getStringExtra(EXTRA_STOP_NAME).orEmpty()).toBundle()
                }
                KIND_ROUTE -> RouteStopsFragment.newInstance(routeId, routeName, headsign,
                    intent.getStringExtra(EXTRA_TRIP_ID) ?: run { finish(); return }, stopId)
                else -> { finish(); return }
            }
            supportFragmentManager.beginTransaction()
                .replace(binding.boardContainer.id, fragment)
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        private const val KIND_ROUTE = "route"
        private const val KIND_SCHEDULE = "schedule"
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_ROUTE_NAME = "routeName"
        private const val EXTRA_HEADSIGN = "headsign"
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_TRIP_ID = "tripId"

        /** The stops of the route of [tripId], the direction's through the stop [stopId], marked there. */
        fun openRoute(context: Context, routeId: String, routeName: String, headsign: String, tripId: String, stopId: String) {
            context.startActivity(Intent(context, LineWindowActivity::class.java)
                .putExtra(EXTRA_KIND, KIND_ROUTE)
                .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_ROUTE_NAME, routeName)
                .putExtra(EXTRA_HEADSIGN, headsign).putExtra(EXTRA_TRIP_ID, tripId).putExtra(EXTRA_STOP_ID, stopId))
        }

        /** The timetable of the direction [headsign] at the stop [stopId]. */
        fun openSchedule(context: Context, routeId: String, routeName: String, headsign: String, stopId: String, stopName: String) {
            context.startActivity(Intent(context, LineWindowActivity::class.java)
                .putExtra(EXTRA_KIND, KIND_SCHEDULE)
                .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_ROUTE_NAME, routeName)
                .putExtra(EXTRA_HEADSIGN, headsign).putExtra(EXTRA_STOP_ID, stopId).putExtra(EXTRA_STOP_NAME, stopName))
        }
    }
}
