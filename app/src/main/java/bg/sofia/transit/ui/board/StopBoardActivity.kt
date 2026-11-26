package bg.sofia.transit.ui.board

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import bg.sofia.transit.R
import bg.sofia.transit.databinding.ActivityStopBoardBinding
import bg.sofia.transit.ui.common.SubWindow
import bg.sofia.transit.ui.lines.ScheduleFragment
import bg.sofia.transit.ui.nearby.StopArrivalsFragment
import bg.sofia.transit.util.FavoriteStop
import bg.sofia.transit.util.FavoriteStops
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.ThemeMode
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * A stop's window, "Спирка" (the owner, 26 Nov 2026: the virtual board and
 * the timetables of a stop in one window): a window of its own — no
 * navigation bar; in the top bar the back arrow, the title, "Обнови" while
 * the board is shown (StopArrivalsFragment's) and the favourites. Two tabs,
 * as "Спирки" has: "Табло" — the arrivals, the virtual board of before
 * (StopArrivalsFragment) — and "Разписание" — the timetable of any line
 * calling there, chosen from a drop-down list (ScheduleFragment); between
 * them and the page, the stop's name and code.
 *
 * Opened from anywhere in the app: on the board with [start] (Спирки — near
 * us and the favourites — and a stop of a line's route opened from a board,
 * RouteStopsFragment), on the timetable of a line with [startOnTimetable]
 * (a stop of a direction in Линии). The window itself — SubWindow.
 */
@AndroidEntryPoint
class StopBoardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStopBoardBinding

    @Inject lateinit var favorites: FavoriteStops

    private val stopId get() = intent.getStringExtra(EXTRA_STOP_ID).orEmpty()
    private val stopName get() = intent.getStringExtra(EXTRA_STOP_NAME) ?: stopId
    private val stopCode get() = intent.getStringExtra(EXTRA_STOP_CODE)

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        binding = ActivityStopBoardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SubWindow.setUp(this, binding.toolbar, binding.viewPager, TITLE)
        if (intent.getStringExtra(EXTRA_STOP_ID) == null) { finish(); return }

        // Show "stop_code" (e.g. "0170") rather than internal stop_id ("A0170")
        // — matches what's printed on physical stop signs and the CGM website.
        val code = stopCode?.takeIf { it.isNotBlank() }?.let { " (код $it)" } ?: ""
        binding.tvStopName.text = "$stopName$code"

        binding.viewPager.adapter = Pages(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == PAGE_BOARD) "Табло" else "Разписание"
        }.attach()
        // Opened on the tab asked for; on recreation, ViewPager2 keeps its own.
        if (savedInstanceState == null) {
            binding.viewPager.setCurrentItem(intent.getIntExtra(EXTRA_PAGE, PAGE_BOARD), false)
        }
    }

    /** The two tabs' pages, made once each and kept, as in Спирки. */
    private inner class Pages(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == PAGE_BOARD) StopArrivalsFragment.newInstance(stopId, stopCode)
            else ScheduleFragment.newInstance(stopId,
                intent.getStringExtra(EXTRA_ROUTE_ID), intent.getStringExtra(EXTRA_HEADSIGN))
    }

    // The favourites, on both tabs (the board's "Обнови" is its own, StopArrivalsFragment).

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // The board's "Обнови" first: added by super, from the menu providers
        // (StopArrivalsFragment's, while it is shown).
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.stop_board, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val fav = favorites.contains(stopId)
        menu.findItem(R.id.actionFavorite)?.apply {
            title = if (fav) "Премахни от любими" else "Добави в любими"
            setIcon(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.actionFavorite) return super.onOptionsItemSelected(item)
        val message = if (favorites.contains(stopId)) {
            favorites.remove(stopId)
            "Спирката е премахната от любими"
        } else {
            favorites.add(FavoriteStop(stopId, stopName, stopCode))
            "Спирката е добавена в любими"
        }
        FileLogger.i(TAG, "$message: $stopId")
        invalidateOptionsMenu()
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        private const val TAG = "StopWindow"
        private const val TITLE = "Спирка"
        private const val PAGE_BOARD = 0
        private const val PAGE_TIMETABLE = 1
        private const val EXTRA_STOP_ID = "stopId"
        private const val EXTRA_STOP_NAME = "stopName"
        private const val EXTRA_STOP_CODE = "stopCode"
        private const val EXTRA_PAGE = "page"
        private const val EXTRA_ROUTE_ID = "routeId"
        private const val EXTRA_HEADSIGN = "headsign"

        /** The window of a stop — its id, its name, and its code (e.g. "0170"), if known — on the board. */
        fun intentFor(context: Context, stopId: String, stopName: String, stopCode: String?): Intent =
            Intent(context, StopBoardActivity::class.java)
                .putExtra(EXTRA_STOP_ID, stopId)
                .putExtra(EXTRA_STOP_NAME, stopName)
                .putExtra(EXTRA_STOP_CODE, stopCode)

        fun start(context: Context, stopId: String, stopName: String, stopCode: String?) {
            context.startActivity(intentFor(context, stopId, stopName, stopCode))
        }

        /**
         * The window of a stop on the timetable of the line [routeId] towards
         * [headsign] there ([stopId]: the pole the direction calls at) — from
         * a stop of a direction in Линии (the owner, 26 Nov 2026).
         */
        fun startOnTimetable(context: Context, stopId: String, stopName: String, stopCode: String?,
                             routeId: String, headsign: String) {
            context.startActivity(intentFor(context, stopId, stopName, stopCode)
                .putExtra(EXTRA_PAGE, PAGE_TIMETABLE)
                .putExtra(EXTRA_ROUTE_ID, routeId)
                .putExtra(EXTRA_HEADSIGN, headsign))
        }
    }
}
