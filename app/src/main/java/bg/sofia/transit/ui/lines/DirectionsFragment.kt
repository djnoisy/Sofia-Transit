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

        binding.tvRouteTitle.text = "Линия ${args.routeShortName}"
        binding.tvRouteTitle.contentDescription =
            "Линия ${args.routeShortName}: ${args.routeLongName}. Изберете направление."

        val adapter = DirectionsAdapter { trip -> openStops(trip) }
        binding.rvDirections.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDirections.adapter = adapter
        // No contentDescription on the RecyclerView itself — it would make
        // TalkBack treat the whole list as one element and may suppress
        // child rendering. Same fix as in StopArrivalsFragment.

        viewLifecycleOwner.lifecycleScope.launch {
            vm.directions.collectLatest { adapter.submitList(it) }
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
