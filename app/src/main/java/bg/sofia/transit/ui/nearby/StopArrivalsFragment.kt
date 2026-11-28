package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.repository.ArrivalInfo
import bg.sofia.transit.databinding.FragmentStopArrivalsBinding
import bg.sofia.transit.ui.board.LineWindowActivity
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The arrivals at one stop — the "Табло с пристигания" tab of the stop's window
 * (StopBoardActivity; the virtual board of before, the owner, 26 Nov 2026),
 * with "Обнови" in the window's top bar while it is shown (the owner,
 * 9 Nov 2026). A row activated opens its line's route through the stop
 * (the owner, 24 Nov 2026; straight, 26 Nov 2026: the timetable is the
 * window's other tab).
 */
@AndroidEntryPoint
class StopArrivalsFragment : Fragment() {

    companion object {
        private const val TAG = "StopArrivalsFrag"
        private const val ARG_STOP_ID = "stopId"
        private const val ARG_STOP_CODE = "stopCode"
        /**
         * The arrivals refreshed this often while shown — frequent enough to
         * stay current, infrequent enough that TalkBack users aren't
         * constantly interrupted by the list re-rendering; "Обнови" in the
         * top bar is there for anyone who wants an immediate refresh — and
         * loaded at once when shown again after so long.
         */
        private const val REFRESH_MS = 60_000L

        /** The board of the stop [stopId]; [stopCode], e.g. "0170", gathers its poles. */
        fun newInstance(stopId: String, stopCode: String?) =
            StopArrivalsFragment().apply {
                arguments = bundleOf(ARG_STOP_ID to stopId, ARG_STOP_CODE to stopCode)
            }
    }

    private var _binding: FragmentStopArrivalsBinding? = null
    private val binding get() = _binding!!
    private val vm: StopArrivalsViewModel by viewModels()

    private val stopId: String get() = requireArguments().getString(ARG_STOP_ID).orEmpty()
    private val stopCode: String? get() = requireArguments().getString(ARG_STOP_CODE)

    private lateinit var adapter: ArrivalAdapter
    private var refreshJob: kotlinx.coroutines.Job? = null

    /** When the arrivals were last asked for (elapsed real time; 0: not yet). */
    private var loadedAt = 0L

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentStopArrivalsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        // The stop's name and code: the window's, above its tabs (StopBoardActivity).

        adapter = ArrivalAdapter { info -> openRoute(info) }
        binding.rvArrivals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvArrivals.adapter = adapter
        // Disable the "change" animation: when a row's contents update in
        // place (ETA ticking down), the default animator cross-fades by
        // swapping in a second ViewHolder, which disturbs TalkBack focus.
        // Turning it off keeps the same view, so focus stays put.
        (binding.rvArrivals.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
        // Removed contentDescription on the RecyclerView itself — it was making
        // TalkBack treat the whole list as one element and may have been
        // suppressing child rendering.

        // "Обнови", in the window's top bar while the board is shown — its
        // tab the current one: only then is it resumed. The favourites are
        // the window's, on both tabs.
        requireActivity().addMenuProvider(boardMenu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // The lines' official colours for the badges (redesign, stage b).
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setRouteColors(vm.routeColors())
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.error.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        loadArrivals()
    }

    private fun loadArrivals() {
        loadedAt = SystemClock.elapsedRealtime()
        vm.loadArrivals(stopId = stopId, stopCode = stopCode)
    }

    /** The top bar's action while the board is shown: refresh. */
    private val boardMenu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.stop_board_refresh, menu)
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.actionRefresh -> { loadArrivals(); true }
            else -> false
        }
    }

    /**
     * A row activated: the stops of the route its line's direction takes
     * through this stop, in a window of its own over the stop's (the owner,
     * 24 Nov 2026; straight, 26 Nov 2026).
     */
    private fun openRoute(info: ArrivalInfo) {
        val owner = viewLifecycleOwnerLiveData.value ?: return
        owner.lifecycleScope.launch {
            val line = vm.lineAtStop(info, stopId)
            // Not after the board was left meanwhile (a data import waited on).
            if (!isResumed) return@launch
            val context = context ?: return@launch
            FileLogger.i(TAG, "Route of ${info.routeId} towards '${info.headsign}' " +
                "from the board of $stopId: ${line?.let { "trip ${it.tripId} at ${it.stopId}" } ?: "none"}")
            if (line == null) Toast.makeText(context, "Маршрутът на линията не е известен", Toast.LENGTH_LONG).show()
            else LineWindowActivity.openFromBoard(context, info.routeId, info.routeShortName, ArrivalAdapter.labelOf(info),
                line.headsign, line.tripId, line.stopId)
        }
    }

    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(REFRESH_MS)
                loadArrivals()
            }
        }
    }

    private fun renderState(state: StopArrivalsState) {
        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE

        // While merely loading (no data yet in this tick), don't touch the
        // list — calling submitList with the old/empty list on every refresh
        // cycle disturbs focus. Only update the list when we actually have a
        // resolved result for this load.
        if (state.loading) return

        adapter.submitList(state.arrivals)

        val showEmpty = state.arrivals.isEmpty()
        // Only flip visibility when it actually changes — writing visibility
        // (even to the same value) can emit an accessibility event that makes
        // TalkBack re-evaluate the screen and bounce focus to the top.
        val desiredEmptyVis = if (showEmpty) View.VISIBLE else View.GONE
        if (binding.svEmpty.visibility != desiredEmptyVis) {
            binding.svEmpty.visibility = desiredEmptyVis
            if (showEmpty) {
                binding.tvEmpty.text = "Няма пристигащи превозни средства в момента"
            }
        }
        val desiredListVis = if (state.arrivals.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.rvArrivals.visibility != desiredListVis) {
            binding.rvArrivals.visibility = desiredListVis
        }
    }

    // Refreshed only while shown: its tab the current one, the window in front.
    override fun onPause() { super.onPause(); refreshJob?.cancel() }
    override fun onResume() {
        super.onResume()
        if (_binding == null) return
        // Shown again — its tab chosen, the window back — with arrivals of a
        // minute before or more: loaded at once, not a minute on.
        if (SystemClock.elapsedRealtime() - loadedAt >= REFRESH_MS) loadArrivals()
        scheduleAutoRefresh()
    }
    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}
