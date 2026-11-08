package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import bg.sofia.transit.databinding.FragmentNearbyStopsBinding
import com.google.android.material.tabs.TabLayoutMediator
import dagger.hilt.android.AndroidEntryPoint

/**
 * Container fragment for the "Spirki" (Stops) section. Hosts a TabLayout +
 * ViewPager2 with two tabs: "Близки спирки" (nearby) and "Търсене" (search).
 *
 * The navigation action to a stop's arrivals lives on this fragment's
 * destination node in the nav graph, so child tabs reuse it via the
 * Directions class. "Настройки" and "Диагностика" are in the top app bar
 * (MainActivity), on every screen.
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

        binding.viewPager.adapter = StopsTabsAdapter(childFragmentManager, lifecycle)

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Близки спирки"
                else -> "Търсене"
            }
            tab.contentDescription = when (position) {
                0 -> "Близки спирки около вас"
                else -> "Търсене на спирка по име или код"
            }
        }.attach()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class StopsTabsAdapter(
        fm: FragmentManager,
        lifecycle: androidx.lifecycle.Lifecycle
    ) : FragmentStateAdapter(fm, lifecycle) {
        override fun getItemCount(): Int = 2
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> NearbyTabFragment()
            else -> SearchTabFragment()
        }
    }
}
