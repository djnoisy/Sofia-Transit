package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
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
    private val vm: LinesViewModel by activityViewModels()
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

        binding.rvRoutes.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRoutes.adapter = adapter
        // No contentDescription on the RecyclerView itself

        // Category filters shown as vertical icon-over-label tabs.
        // Presented to TalkBack as plain selectable elements (not
        // "buttons"); the active one reports its selected state ("избрано").
        binding.btnBus.setOnClickListener      { filterType(TransportType.BUS) }
        binding.btnTram.setOnClickListener     { filterType(TransportType.TRAM) }
        binding.btnTrolley.setOnClickListener  { filterType(TransportType.TROLLEYBUS) }
        binding.btnMetro.setOnClickListener    { filterType(TransportType.METRO) }
        attachTabSemantics(binding.btnBus, binding.btnTram,
                           binding.btnTrolley, binding.btnMetro)

        // Default category on first entry (no prior choice): buses — the
        // largest and most-used group. Replaces the old "Всички" view.
        if (vm.currentFilter == null) vm.currentFilter = TransportType.BUS

        viewLifecycleOwner.lifecycleScope.launch {
            vm.groupedRoutes.collectLatest { grouped ->
                if (grouped.isEmpty()) return@collectLatest
                // Re-apply whatever filter was active before navigating
                // away (survives in the shared VM), instead of always
                // resetting to "all".
                applyCurrentFilter(announce = false)
                restoreFocusToLastSelected()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.routeSubtitles.collectLatest { subs ->
                adapter.setSubtitles(subs)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.trolleyRouteIds.collectLatest { ids ->
                adapter.setTrolleyRouteIds(ids)
            }
        }

        updateFilterButtonStates()
        vm.loadRoutes()
    }

    private fun filterType(type: TransportType) {
        vm.currentFilter = type
        applyCurrentFilter(announce = true)
    }

    /**
     * Applies vm.currentFilter to the list and syncs the button states.
     * When [announce] is true (user just tapped a category), TalkBack
     * announces the new category and count.
     */
    private fun applyCurrentFilter(announce: Boolean) {
        val grouped = vm.groupedRoutes.value
        val filter = vm.currentFilter ?: TransportType.BUS
        val list = grouped[filter] ?: emptyList()
        adapter.submitList(list)
        updateAccessibilityCount(list.size)
        updateFilterButtonStates()
        if (announce) {
            binding.rvRoutes.announceForAccessibility("${filter.labelBg}: ${list.size} линии")
        }
    }

    /**
     * Attaches "tab" accessibility semantics to the category filters:
     * suppresses the "button" role (so TalkBack uses the plain element
     * focus sound) and reports the selected state, which TalkBack
     * announces as "избрано" for the active category.
     */
    private fun attachTabSemantics(vararg views: View) {
        val delegate = object : androidx.core.view.AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(
                host: View,
                info: androidx.core.view.accessibility.AccessibilityNodeInfoCompat
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.view.View"
                info.isSelected = host.isSelected
            }
        }
        views.forEach { androidx.core.view.ViewCompat.setAccessibilityDelegate(it, delegate) }
    }

    /**
     * Marks the active category visually and for accessibility via the
     * selected state (announced by TalkBack thanks to attachTabSemantics).
     */
    private fun updateFilterButtonStates() {
        val filter = vm.currentFilter
        val map = listOf(
            binding.btnBus to TransportType.BUS,
            binding.btnTram to TransportType.TRAM,
            binding.btnTrolley to TransportType.TROLLEYBUS,
            binding.btnMetro to TransportType.METRO
        )
        for ((btn, type) in map) {
            btn.isSelected = (filter == type)
        }
    }

    /**
     * After returning from a deeper screen, scrolls to and puts
     * accessibility focus on the route the user last opened, so they
     * continue from where they were instead of the top of the list.
     * Consumes the stored id so it only happens once per return.
     */
    private fun restoreFocusToLastSelected() {
        val targetId = vm.lastSelectedRouteId ?: return
        vm.lastSelectedRouteId = null
        val pos = adapter.positionOf(targetId)
        if (pos < 0) return
        binding.rvRoutes.post {
            (binding.rvRoutes.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, 0)
            binding.rvRoutes.post {
                binding.rvRoutes.findViewHolderForAdapterPosition(pos)
                    ?.itemView
                    ?.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                        null
                    )
            }
        }
    }

    private fun updateAccessibilityCount(count: Int) {
        binding.tvRouteCount.text = "$count линии"
        binding.tvRouteCount.contentDescription = "$count транспортни линии"
    }

    private fun openDirections(route: Route) {
        vm.selectRoute(route)
        vm.lastSelectedRouteId = route.routeId
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
