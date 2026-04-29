package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.databinding.FragmentStopsListBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class StopsListFragment : Fragment() {

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

        binding.tvDirectionTitle.text = "→ ${args.headsign}"
        binding.tvDirectionTitle.contentDescription =
            "Линия ${args.routeName} към ${args.headsign}. " +
            "Изберете спирка за разписание."

        val adapter = StopOnRouteAdapter { stop -> openSchedule(stop) }
        binding.rvStops.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            contentDescription = "Спирки по маршрута"
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.directionStops.collectLatest { stops ->
                adapter.submitList(stops)
                binding.tvStopCount.text = "${stops.size} спирки"
                binding.tvStopCount.contentDescription = "${stops.size} спирки по маршрута"
            }
        }
    }

    private fun openSchedule(stop: StopWithSequence) {
        findNavController().navigate(
            StopsListFragmentDirections.actionStopsListToSchedule(
                routeId   = args.routeId,
                routeName = args.routeName,
                headsign  = args.headsign,
                stopId    = stop.stopId,
                stopName  = stop.stopName
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
