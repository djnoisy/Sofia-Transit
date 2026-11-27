package bg.sofia.transit.ui.board

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.FragmentStopsListBinding
import bg.sofia.transit.ui.lines.StopOnRouteAdapter
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * "Спирки по маршрута" from a row of a stop's board (the "Табло с пристигания" tab of the
 * stop's window; the owner, 24 and 26 Nov 2026): the stops of the route the row's direction takes through the
 * board's stop (GtfsRepository.lineAtStop) — 111 towards БУЛ. НИКОЛА ПЕТКОВ
 * its short run's 22, not the full run's 41 — as the Lines screen lists a
 * direction's (StopOnRouteAdapter), the board's stop marked "тази спирка",
 * brought to the top of the list — TalkBack's focus not moved there (the
 * owner, 27 Nov 2026: it broke the reading of the screen) — the minutes from
 * it on (the owner, 25 Nov 2026).
 * A stop opens its own window, on its board (the owner, 24 Nov 2026).
 */
@AndroidEntryPoint
class RouteStopsFragment : Fragment() {

    companion object {
        private const val TAG = "RouteStopsFrag"
        private const val ARG_ROUTE_ID = "routeId"
        private const val ARG_ROUTE_NAME = "routeName"
        private const val ARG_HEADSIGN = "headsign"
        private const val ARG_TRIP_ID = "tripId"
        private const val ARG_STOP_ID = "stopId"

        fun newInstance(routeId: String, routeName: String, headsign: String, tripId: String, stopId: String) =
            RouteStopsFragment().apply {
                arguments = bundleOf(ARG_ROUTE_ID to routeId, ARG_ROUTE_NAME to routeName,
                    ARG_HEADSIGN to headsign, ARG_TRIP_ID to tripId, ARG_STOP_ID to stopId)
            }
    }

    private var _binding: FragmentStopsListBinding? = null
    private val binding get() = _binding!!
    private val vm: RouteStopsViewModel by viewModels()

    private val args get() = requireArguments()
    private val routeId get() = args.getString(ARG_ROUTE_ID).orEmpty()
    private val routeName get() = args.getString(ARG_ROUTE_NAME).orEmpty()
    private val headsign get() = args.getString(ARG_HEADSIGN).orEmpty()
    private val hereId get() = args.getString(ARG_STOP_ID).orEmpty()

    /** The board's stop brought to the top once, not again on every redraw. */
    private var focused = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentStopsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        focused = saved != null

        // As the Lines screen's list of a direction's stops: "Автобус 111 →
        // БУЛ. НИКОЛА ПЕТКОВ" on screen, "… към …" for TalkBack.
        binding.tvDirectionTitle.text = "Линия $routeName → $headsign"
        binding.tvDirectionTitle.contentDescription = "Линия $routeName към $headsign"
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = vm.getRouteMeta(routeId)
            val label = meta.vehicleType?.replaceFirstChar { it.uppercase() } ?: "Линия"
            binding.tvDirectionTitle.text = "$label $routeName → $headsign"
            binding.tvDirectionTitle.contentDescription = "$label $routeName към $headsign"
        }

        val adapter = StopOnRouteAdapter(here = hereId) { stop -> openBoard(stop) }
        binding.rvStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStops.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.stops.filterNotNull().collect { stops ->
                adapter.submitList(stops)
                binding.tvStopCount.text = "${stops.size} спирки"
                binding.tvStopCount.contentDescription = "${stops.size} спирки по маршрута"
                if (!focused) { focused = true; bringHere(stops.indexOfFirst { it.stopId == hereId }) }
            }
        }
        vm.load(args.getString(ARG_TRIP_ID).orEmpty())
    }

    /**
     * The board's stop at the top of the list, TalkBack's focus left where it
     * is (the owner, 27 Nov 2026): the first stop a swipe from the heading
     * reaches is it.
     */
    private fun bringHere(pos: Int) {
        if (pos < 0) return
        val rv = binding.rvStops
        rv.post { (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(pos, 0) }
    }

    /** A stop's own board (the owner, 24 Nov 2026), by its code as Спирки opens it. */
    private fun openBoard(stop: StopWithSequence) {
        viewLifecycleOwner.lifecycleScope.launch {
            val code = vm.stopCode(stop.stopId)
            val context = context ?: return@launch
            FileLogger.i(TAG, "Board of ${stop.stopId} (code $code) from the route of $routeId towards $headsign")
            StopBoardActivity.start(context, stop.stopId, stop.stopName, code)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

