package bg.sofia.transit.ui.lines

import android.os.Bundle
import android.view.*
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.data.db.entity.Route
import bg.sofia.transit.data.db.entity.TransportType
import bg.sofia.transit.databinding.FragmentLinesBinding
import bg.sofia.transit.ui.common.Keyboard
import bg.sofia.transit.util.LineSearch
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
                applyCurrentFilter()
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

        // A line by its number (the owner, 26 Nov 2026): the list of the kind
        // narrowed as it is typed. What is typed is the screen's (the view
        // does not save it): put back before the watcher, which then leaves
        // the same text alone — a list drawn anew moves TalkBack's focus off
        // the line opened before.
        binding.etSearch.setText(vm.lineQuery)
        binding.btnClearSearch.visibility = if (vm.lineQuery.isEmpty()) View.GONE else View.VISIBLE
        binding.etSearch.doAfterTextChanged { text ->
            val query = text?.toString().orEmpty()
            binding.btnClearSearch.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            if (query == vm.lineQuery) return@doAfterTextChanged
            vm.lineQuery = query
            applyCurrentFilter()
        }
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
            binding.etSearch.requestFocus()
        }
        // "Търсене" on the keyboard: it is put away, the lines found stay.
        binding.etSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { Keyboard.hide(binding.etSearch); true } else false
        }
        // The keyboard lies over the window: room under the last line (as in "Пътуване").
        Keyboard.keepClear(binding.rvRoutes)

        updateFilterButtonStates()
        vm.loadRoutes()
    }

    /** Another kind chosen: the search field cleared, the keyboard put away (the owner, 26 Nov 2026). */
    private fun filterType(type: TransportType) {
        if (type != vm.currentFilter) {
            vm.lineQuery = ""
            binding.etSearch.text?.clear()
        }
        Keyboard.hide(binding.etSearch)
        vm.currentFilter = type
        applyCurrentFilter()
    }

    /**
     * Applies vm.currentFilter — and the number typed, for every kind but
     * the metro, whose four lines have no search field — to the list and
     * syncs the button states. No count of the lines shown or said (the
     * owner, 26 Nov 2026): the kind chosen says "избрано" by itself.
     */
    private fun applyCurrentFilter() {
        val grouped = vm.groupedRoutes.value
        val filter = vm.currentFilter ?: TransportType.BUS
        val all = grouped[filter] ?: emptyList()
        val searchable = filter != TransportType.METRO
        binding.searchRow.visibility = if (searchable) View.VISIBLE else View.GONE
        val query = if (searchable) vm.lineQuery.trim() else ""
        // As in "Пътуване": exact, then beginning with it, then containing it
        // — whatever the case and the alphabet ("3тм" finds 3ТМ).
        val list = if (query.isEmpty()) all else LineSearch.rank(all, query, { it.routeShortName })
        adapter.submitList(list)
        updateFilterButtonStates()
        // Not while the lines are still loading (review of 26 Nov 2026).
        val none = query.isNotEmpty() && list.isEmpty() && grouped.isNotEmpty()
        // Said once, as it comes (as Спирки → "Търсене").
        if (none && binding.tvEmpty.visibility != View.VISIBLE) binding.tvEmpty.announceForAccessibility(binding.tvEmpty.text)
        binding.tvEmpty.visibility = if (none) View.VISIBLE else View.GONE
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

    override fun onResume() {
        super.onResume()
        // The keyboard may have gone while the screen was away.
        Keyboard.again(binding.rvRoutes)
    }

    override fun onPause() {
        super.onPause()
        // A line opened, another section chosen: the keyboard not left over it.
        Keyboard.hide(binding.etSearch)
    }

    private fun openDirections(route: Route) {
        Keyboard.hide(binding.etSearch)
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
