package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.FragmentStopsListBinding
import bg.sofia.transit.ui.board.StopBoardActivity
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StopsListFragment : Fragment() {

    private companion object { const val TAG = "StopsListFrag" }

    private var _binding: FragmentStopsListBinding? = null
    private val binding get() = _binding!!
    private val vm: LinesViewModel by activityViewModels()
    private val args: StopsListFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentStopsListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        // One more route of a direction: what tells it apart, after it —
        // "→ ГАРА ПОДУЯНЕ (от ДЕПО ИСКЪР)", for TalkBack "…, от ДЕПО ИСКЪР".
        val via = args.via.takeIf { it.isNotEmpty() }
        val shownVia = via?.let { " ($it)" } ?: ""
        val saidVia = via?.let { ", $it" } ?: ""
        binding.tvDirectionTitle.text = "→ ${args.headsign}$shownVia"
        binding.tvDirectionTitle.contentDescription = "към ${args.headsign}$saidVia"

        // Same pattern as Directions/Schedule screens: replace generic
        // "Линия N" with the concrete vehicle type ("Автобус 84",
        // "Тролей 2"), for consistency with the Lines list. Visual uses
        // "→"; TalkBack reads "към" so it sounds natural.
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = vm.getRouteMeta(args.routeId)
            val label = meta.vehicleType?.replaceFirstChar { it.uppercase() } ?: "Линия"
            binding.tvDirectionTitle.text = "$label ${args.routeName} → ${args.headsign}$shownVia"
            binding.tvDirectionTitle.contentDescription =
                "$label ${args.routeName} към ${args.headsign}$saidVia"
        }

        val adapter = StopOnRouteAdapter { stop -> openSchedule(stop) }
        binding.rvStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStops.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.directionStops.collectLatest { stops ->
                adapter.submitList(stops)
                binding.tvStopCount.text = "${stops.size} спирки"
                binding.tvStopCount.contentDescription = "${stops.size} спирки по маршрута"
            }
        }
    }

    /**
     * A stop of the direction: the stop's window, on its timetable of this
     * line and direction (the owner, 26 Nov 2026) — over the main one, with
     * the stop's board and the other lines there a tab and a choice away.
     */
    private fun openSchedule(stop: StopWithSequence) {
        viewLifecycleOwner.lifecycleScope.launch {
            val code = vm.stopCode(stop.stopId)
            val context = context ?: return@launch
            FileLogger.i(TAG, "Timetable of ${args.routeId} towards '${args.headsign}' at ${stop.stopId} (code $code)")
            StopBoardActivity.startOnTimetable(context, stop.stopId, stop.stopName, code, args.routeId, args.headsign)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
