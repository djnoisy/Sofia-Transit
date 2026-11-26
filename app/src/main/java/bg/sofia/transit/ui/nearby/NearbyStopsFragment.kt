package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import bg.sofia.transit.databinding.FragmentNearbyStopsBinding
import bg.sofia.transit.ui.common.Tabs
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

/**
 * Container fragment for the "Спирки" (Stops) section. Hosts a TabLayout +
 * ViewPager2 with three tabs (the owner, 26 Nov 2026): "Наблизо" (the
 * stops near us; was "Близки спирки"), "Любими" (the favourite stops; the
 * owner, 9 Nov 2026) and "Търсене" (a stop by its name or code). The tabs
 * on a band of their own, the selected one a pill (bg_stops_tab).
 *
 * A stop opens in a window of its own, "Спирка" — its board and its
 * timetables (StopBoardActivity). "Настройки" and "Диагностика" are in the
 * top app bar (MainActivity), on every screen.
 */
@AndroidEntryPoint
class NearbyStopsFragment : Fragment() {

    private var _binding: FragmentNearbyStopsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentNearbyStopsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        // Tied to the screen's view, not the section: the section outlives
        // its view (kept beneath the others), and each return to it held on
        // to the view before (9 Nov 2026).
        binding.viewPager.adapter = StopsTabsAdapter(childFragmentManager, viewLifecycleOwner.lifecycle)

        // "Наблизо", "Любими", "Търсене" (the owner, 26 Nov 2026): their names
        // only, no longer description for TalkBack.
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = TAB_NAMES[position]
        }.attach()
        Tabs.boldSelected(binding.tabLayout)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class StopsTabsAdapter(
        fm: FragmentManager,
        lifecycle: androidx.lifecycle.Lifecycle
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount(): Int = TAB_NAMES.size
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> NearbyTabFragment()
            1 -> FavoritesTabFragment()
            else -> SearchTabFragment()
        }
    }

    private companion object {
        val TAB_NAMES = listOf("Наблизо", "Любими", "Търсене")
    }
}
