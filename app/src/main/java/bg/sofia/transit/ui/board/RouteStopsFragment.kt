package bg.sofia.transit.ui.board

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.FragmentStopsListBinding
import bg.sofia.transit.ui.lines.StopOnRouteAdapter
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * A line's stops along a direction, in the line's window (LineWindowActivity;
 * the owner, 27 Nov 2026) — from Линии and from a stop's board alike: the
 * direction chosen from a drop-down list of all the line's — the two main
 * ones, then the others and the further routes, "Други направления" before
 * (RouteStopsViewModel) — the count of its stops, the stops
 * (StopOnRouteAdapter) with their travel time from the stop chosen to count
 * from: the first, or, from a board, the board's stop — at the top of the
 * list, TalkBack's focus not moved (the owner, 27 Nov 2026).
 *
 * A stop opens its window: from Линии on its timetable of this line and
 * direction (the owner, 26 Nov 2026), from a board on its board (24 Nov 2026).
 */
@AndroidEntryPoint
class RouteStopsFragment : Fragment() {

    companion object {
        private const val TAG = "RouteStopsFrag"
        private const val ARG_ROUTE_ID = "routeId"
        private const val ARG_BOARD_HEADSIGN = "boardHeadsign"
        private const val ARG_BOARD_TRIP_ID = "boardTripId"
        private const val ARG_BOARD_STOP_ID = "boardStopId"

        /**
         * The stops of [routeId]; from a board's row, its direction
         * ([boardHeadsign]) by the route through the board's stop
         * ([boardTripId], [boardStopId]: its pole), counted from that stop.
         */
        fun newInstance(routeId: String, boardHeadsign: String?, boardTripId: String?, boardStopId: String?) =
            RouteStopsFragment().apply {
                arguments = bundleOf(ARG_ROUTE_ID to routeId, ARG_BOARD_HEADSIGN to boardHeadsign,
                    ARG_BOARD_TRIP_ID to boardTripId, ARG_BOARD_STOP_ID to boardStopId)
            }
    }

    private var _binding: FragmentStopsListBinding? = null
    private val binding get() = _binding!!
    private val vm: RouteStopsViewModel by viewModels()
    private lateinit var adapter: StopOnRouteAdapter

    private val args get() = requireArguments()
    private val routeId get() = args.getString(ARG_ROUTE_ID).orEmpty()
    private val fromBoard get() = args.getString(ARG_BOARD_TRIP_ID) != null

    /** What was drawn: the choices the drop-down list was built of, the stops of the list. */
    private var listed: List<RouteChoice>? = null
    private var drawn: List<StopWithSequence>? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentStopsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    /** A view made anew (a rotation): the list keeps its own place — not brought to the start stop again. */
    private var restored = false

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        listed = null
        drawn = null
        // Only with stops shown before: else the list's place was used up
        // on an empty layout, and the start stop is to be brought up still.
        restored = saved != null && vm.state.value.stops != null

        adapter = StopOnRouteAdapter(onOpen = { stop -> openStop(stop) }, onStart = { pos -> vm.startAt(pos) })
        binding.rvStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStops.adapter = adapter
        // Another stop counted from: the cards changed in place, TalkBack's focus kept.
        (binding.rvStops.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        vm.load(routeId, args.getString(ARG_BOARD_HEADSIGN), args.getString(ARG_BOARD_TRIP_ID),
            args.getString(ARG_BOARD_STOP_ID))
        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collect { state -> render(state) }
        }
    }

    private fun render(state: RouteStopsState) {
        bindChoices(state)
        val stops = state.stops
        if (stops == null) {
            // Another direction loading: the old stops not left to open (with
            // the new direction's timetable) nor their count said (review of
            // 28 Nov 2026).
            binding.tvStopCount.text = ""
            binding.tvStopCount.contentDescription = null
            binding.tvEmpty.visibility = View.GONE
            restored = false
            if (drawn != null) { drawn = null; adapter.submitList(emptyList(), 0) }
            return
        }
        binding.tvStopCount.text = "${stops.size} спирки"
        binding.tvStopCount.contentDescription = "${stops.size} спирки по маршрута"
        val none = stops.isEmpty()
        binding.tvEmpty.text = if (state.choices.isNullOrEmpty()) "Маршрутът на линията не е известен" else "Няма спирки по този маршрут"
        binding.tvEmpty.visibility = if (none) View.VISIBLE else View.GONE
        if (stops !== drawn) {
            // A direction's stops, new: drawn anew, the stop counted from at
            // the top — no focus moved (the owner, 27 Nov 2026).
            drawn = stops
            adapter.submitList(stops, state.start)
            val keepPlace = restored
            restored = false
            if (state.start > 0 && !keepPlace) {
                val rv = binding.rvStops
                rv.post { (rv.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(state.start, 0) }
            }
        } else adapter.setStart(state.start)
    }

    /**
     * The drop-down list of the directions, once known: "към Ж.К. ЛЮЛИН-1,2",
     * the one shown selected; in the app's own item layouts (as a stop's
     * timetable's list of lines). Hidden while not known and when none.
     */
    private fun bindChoices(state: RouteStopsState) {
        val choices = state.choices
        binding.spinnerDirection.visibility = if (choices.isNullOrEmpty()) View.GONE else View.VISIBLE
        if (choices.isNullOrEmpty() || choices === listed) return
        listed = choices
        val spinner = binding.spinnerDirection
        spinner.onItemSelectedListener = null
        spinner.adapter = ArrayAdapter(requireContext(), R.layout.item_spinner_selected, choices.map { it.label }).apply {
            setDropDownViewResource(R.layout.item_spinner_dropdown)
        }
        spinner.setSelection(state.selected, false)
        // Set after the selection: the callback the Spinner then makes for it
        // is of the direction shown, not a choice.
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                if (position != vm.state.value.selected) vm.select(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    /** A stop's window: from Линии on its timetable of this line and direction, from a board on its board. */
    private fun openStop(stop: StopWithSequence) {
        val choice = vm.state.value.let { it.choices?.getOrNull(it.selected) }
        viewLifecycleOwner.lifecycleScope.launch {
            val code = vm.stopCode(stop.stopId)
            val context = context ?: return@launch
            FileLogger.i(TAG, "Stop ${stop.stopId} (code $code) from the route of $routeId towards '${choice?.headsign}'" +
                if (fromBoard) ", from a board" else "")
            if (fromBoard || choice == null) StopBoardActivity.start(context, stop.stopId, stop.stopName, code)
            else StopBoardActivity.startOnTimetable(context, stop.stopId, stop.stopName, code, routeId, choice.headsign)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
