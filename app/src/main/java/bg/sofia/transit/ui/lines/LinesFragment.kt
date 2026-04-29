package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.databinding.FragmentLinesBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LinesFragment : Fragment() {

    private var _binding: FragmentLinesBinding? = null
    private val binding get() = _binding!!
    private val vm: LinesViewModel by viewModels()
    private lateinit var adapter: RoutesAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentLinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        adapter = RoutesAdapter { route -> openDirections(route) }

        binding.rvRoutes.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@LinesFragment.adapter
            contentDescription = "Списък с транспортни линии"
        }

        // Tab buttons for transport type filtering
        binding.btnBus.setOnClickListener      { filterType(TransportType.BUS) }
        binding.btnTram.setOnClickListener     { filterType(TransportType.TRAM) }
        binding.btnTrolley.setOnClickListener  { filterType(TransportType.TROLLEYBUS) }
        binding.btnMetro.setOnClickListener    { filterType(TransportType.METRO) }
        binding.btnAll.setOnClickListener      { showAll() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.groupedRoutes.collectLatest { grouped ->
                // Show all by default
                val all = grouped.values.flatten()
                adapter.submitList(all)
                updateAccessibilityCount(all.size)
            }
        }

        vm.loadRoutes()
    }

    private var currentFilter: TransportType? = null
    private var allGrouped: Map<TransportType, List<Route>> = emptyMap()

    private fun filterType(type: TransportType) {
        currentFilter = type
        viewLifecycleOwner.lifecycleScope.launch {
            vm.groupedRoutes.value.let { grouped ->
                val filtered = grouped[type] ?: emptyList()
                adapter.submitList(filtered)
                updateAccessibilityCount(filtered.size)
                binding.rvRoutes.announceForAccessibility(
                    "${type.labelBg}: ${filtered.size} линии"
                )
            }
        }
    }

    private fun showAll() {
        currentFilter = null
        val all = vm.groupedRoutes.value.values.flatten()
        adapter.submitList(all)
        updateAccessibilityCount(all.size)
    }

    private fun updateAccessibilityCount(count: Int) {
        binding.tvRouteCount.text = "$count линии"
        binding.tvRouteCount.contentDescription = "$count транспортни линии"
    }

    private fun openDirections(route: Route) {
        vm.selectRoute(route)
        findNavController().navigate(
            LinesFragmentDirections.actionLinesToDirections(
                routeId        = route.routeId,
                routeShortName = route.routeShortName,
                routeLongName  = route.routeLongName
            )
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
