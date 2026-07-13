package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.data.db.entity.Trip
import bg.sofia.transit.databinding.FragmentDirectionsBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DirectionsFragment : Fragment() {

    private var _binding: FragmentDirectionsBinding? = null
    private val binding get() = _binding!!
    private val vm: LinesViewModel by activityViewModels()
    private val args: DirectionsFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentDirectionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        // Placeholder — final title is set once route meta loads below.
        binding.tvRouteTitle.text = args.routeShortName
        binding.tvRouteTitle.contentDescription = args.routeShortName

        val adapter = DirectionsAdapter { trip -> openStops(trip) }
        binding.rvDirections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDirections.adapter = adapter
        // No contentDescription on the RecyclerView itself — it would make
        // TalkBack treat the whole list as one element and may suppress
        // child rendering. Same fix as in StopArrivalsFragment.

        viewLifecycleOwner.lifecycleScope.launch {
            vm.directions.collectLatest { adapter.submitList(it) }
        }

        // Load the same "vehicle type + top-2 headsigns" info the Lines
        // list uses. The visible header is just "Автобус 84" / "Тролей 2"
        // — enough to orient after re-entering the app on this screen —
        // and TalkBack reads the same, without repeating the directions
        // that follow as list items below.
        viewLifecycleOwner.lifecycleScope.launch {
            val meta = vm.getRouteMeta(args.routeId)
            val label = meta.vehicleType?.replaceFirstChar { it.uppercase() } ?: "Линия"
            val title = "$label ${args.routeShortName}"
            binding.tvRouteTitle.text = title
            binding.tvRouteTitle.contentDescription = title
        }
    }

    private fun openStops(trip: Trip) {
        vm.selectDirection(trip)
        findNavController().navigate(
            DirectionsFragmentDirections.actionDirectionsToStopsList(
                routeId   = args.routeId,
                headsign  = trip.tripHeadsign ?: "—",
                routeName = args.routeShortName
            )
        )
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}
