package bg.sofia.transit.ui.board

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import bg.sofia.transit.databinding.ActivityLineWindowBinding
import bg.sofia.transit.ui.common.SubWindow
import bg.sofia.transit.util.ThemeMode
import dagger.hilt.android.AndroidEntryPoint

/**
 * "Спирки по маршрута": the route a board row's line takes through the stop
 * (RouteStopsFragment), opened by activating the row (the owner, 24 Nov 2026;
 * straight, no choice offered, since the timetable is a tab of the stop's
 * window: the owner, 26 Nov 2026), in a window of its own over the stop's,
 * as that one is (SubWindow). Back returns to the stop's window.
 */
@AndroidEntryPoint
class LineWindowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLineWindowBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLineWindowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SubWindow.setUp(this, binding.toolbar, binding.lineContainer, "Спирки по маршрута")

        if (savedInstanceState == null) {
            val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
            val stopId = intent.getStringExtra(EXTRA_STOP_ID)
            val tripId = intent.getStringExtra(EXTRA_TRIP_ID)
            if (routeId == null || stopId == null || tripId == null) { finish(); return }
            supportFragmentManager.beginTransaction()
                .replace(binding.lineContainer.id, RouteStopsFragment.newInstance(routeId,
                    intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty(), intent.getStringExtra(EXTRA_HEADSIGN).orEmpty(),
                    tripId, stopId))
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_ROUTE_NAME = "routeName"
        private const val EXTRA_HEADSIGN = "headsign"
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_TRIP_ID = "tripId"

        /** The stops of the route of [tripId], the direction's through the stop [stopId], marked there. */
        fun openRoute(context: Context, routeId: String, routeName: String, headsign: String, tripId: String, stopId: String) {
            context.startActivity(Intent(context, LineWindowActivity::class.java)
                .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_ROUTE_NAME, routeName)
                .putExtra(EXTRA_HEADSIGN, headsign).putExtra(EXTRA_TRIP_ID, tripId).putExtra(EXTRA_STOP_ID, stopId))
        }
    }
}
