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
 * A line's window (the owner, 27 Nov 2026): its title the line itself —
 * "Автобус 111", the window's heading for TalkBack — and its stops along a
 * direction chosen in it (RouteStopsFragment). A window of its own, as a
 * stop's is (SubWindow), no navigation bar — opened from Линии (a line
 * activated; was its directions' screen, then its stops', in the section)
 * and from a stop's board (a row activated: its direction, through the
 * board's stop). Back returns to where it was opened from.
 */
@AndroidEntryPoint
class LineWindowActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLineWindowBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityLineWindowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val routeId = intent.getStringExtra(EXTRA_ROUTE_ID)
        SubWindow.setUp(this, binding.toolbar, binding.lineContainer,
            intent.getStringExtra(EXTRA_TITLE) ?: "Линия ${intent.getStringExtra(EXTRA_ROUTE_NAME).orEmpty()}")
        if (routeId == null) { finish(); return }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.lineContainer.id, RouteStopsFragment.newInstance(routeId,
                    intent.getStringExtra(EXTRA_HEADSIGN), intent.getStringExtra(EXTRA_TRIP_ID),
                    intent.getStringExtra(EXTRA_STOP_ID)))
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
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEADSIGN = "headsign"
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_TRIP_ID = "tripId"

        /** The line [routeId] — [title]: "Автобус 111" — from Линии: its first direction, from its first stop. */
        fun open(context: Context, routeId: String, routeName: String, title: String) {
            context.startActivity(Intent(context, LineWindowActivity::class.java)
                .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_ROUTE_NAME, routeName).putExtra(EXTRA_TITLE, title))
        }

        /**
         * The line [routeId] from a board's row: its direction [headsign], by
         * the route of [tripId] through the board's stop [stopId] (its pole),
         * counted from that stop.
         */
        fun openFromBoard(context: Context, routeId: String, routeName: String, title: String,
                          headsign: String, tripId: String, stopId: String) {
            context.startActivity(Intent(context, LineWindowActivity::class.java)
                .putExtra(EXTRA_ROUTE_ID, routeId).putExtra(EXTRA_ROUTE_NAME, routeName).putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HEADSIGN, headsign).putExtra(EXTRA_TRIP_ID, tripId).putExtra(EXTRA_STOP_ID, stopId))
        }
    }
}
