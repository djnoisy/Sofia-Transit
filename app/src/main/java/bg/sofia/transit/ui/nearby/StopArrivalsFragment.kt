package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentStopArrivalsBinding
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Shows real-time arrivals for a single stop.
 * Reached by tapping a stop in NearbyStopsFragment.
 *
 * Auto-refreshes every 20 seconds while visible to keep ETAs current.
 */
@AndroidEntryPoint
class StopArrivalsFragment : Fragment() {

    private var _binding: FragmentStopArrivalsBinding? = null
    private val binding get() = _binding!!
    private val vm: StopArrivalsViewModel by viewModels()
    private val args: StopArrivalsFragmentArgs by navArgs()

    private lateinit var adapter: ArrivalAdapter
    private var refreshJob: kotlinx.coroutines.Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentStopArrivalsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.tvStopName.text = "${args.stopName} [${args.stopId}]"
        binding.tvStopName.contentDescription =
            "Спирка ${args.stopName}, идентификатор ${args.stopId}. Списък с пристигания."

        adapter = ArrivalAdapter()
        binding.rvArrivals.apply {
            layoutManager = LinearLayoutManager(requireContext())
            this.adapter = adapter
            contentDescription = "Пристигащи превозни средства"
        }

        binding.btnRefresh.setOnClickListener {
            loadArrivals()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.error.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        loadArrivals()
        scheduleAutoRefresh()
    }

    private fun loadArrivals() {
        vm.loadArrivals(stopId = args.stopId)
    }

    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                kotlinx.coroutines.delay(20_000L)
                loadArrivals()
            }
        }
    }

    private fun renderState(state: StopArrivalsState) {
        FileLogger.i(TAG, "renderState: loading=${state.loading} arrivals.size=${state.arrivals.size}")

        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled = !state.loading

        adapter.submitList(state.arrivals)
        FileLogger.i(TAG, "After submitList: itemCount=${adapter.itemCount}")
        binding.rvArrivals.post {
            FileLogger.i(TAG, "After post: rv.width=${binding.rvArrivals.width} " +
                "rv.height=${binding.rvArrivals.height} " +
                "rv.visibility=${binding.rvArrivals.visibility} " +
                "rv.childCount=${binding.rvArrivals.childCount}")

            // Check again 500ms later to see if children get inflated eventually
            binding.rvArrivals.postDelayed({
                FileLogger.i(TAG, "After 500ms: rv.childCount=${binding.rvArrivals.childCount} " +
                    "adapter.itemCount=${adapter.itemCount} " +
                    "lm=${binding.rvArrivals.layoutManager?.javaClass?.simpleName}")
                // Force a re-layout to test
                binding.rvArrivals.requestLayout()
                binding.rvArrivals.invalidate()
                binding.rvArrivals.postDelayed({
                    FileLogger.i(TAG, "After requestLayout: rv.childCount=${binding.rvArrivals.childCount}")
                }, 200)
            }, 500)
        }

        // Empty state — only show after loading is done, otherwise it flickers
        val showEmpty = !state.loading && state.arrivals.isEmpty()
        binding.svEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            binding.tvEmpty.text = "Няма пристигащи превозни средства в момента"
        }

        binding.rvArrivals.visibility =
            if (state.arrivals.isNotEmpty()) View.VISIBLE else View.GONE
    }

    companion object { private const val TAG = "StopArrivalsFrag" }

    override fun onPause() {
        super.onPause()
        refreshJob?.cancel()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) scheduleAutoRefresh()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}
