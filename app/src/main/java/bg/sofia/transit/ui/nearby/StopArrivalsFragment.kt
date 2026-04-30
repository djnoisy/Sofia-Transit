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

@AndroidEntryPoint
class StopArrivalsFragment : Fragment() {

    companion object { private const val TAG = "StopArrivalsFrag" }

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
        binding.rvArrivals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvArrivals.adapter = adapter
        // Removed contentDescription on the RecyclerView itself — it was making
        // TalkBack treat the whole list as one element and may have been
        // suppressing child rendering.

        binding.btnRefresh.setOnClickListener { loadArrivals() }

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

    private fun loadArrivals() { vm.loadArrivals(stopId = args.stopId) }

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

        // Empty / debug state
        val showEmpty = !state.loading && state.arrivals.isEmpty()
        binding.svEmpty.visibility = if (showEmpty) View.VISIBLE else View.GONE
        if (showEmpty) {
            binding.tvEmpty.text = "Няма пристигащи превозни средства в момента"
        }

        binding.rvArrivals.visibility =
            if (state.arrivals.isNotEmpty()) View.VISIBLE else View.GONE

        // Diagnostic post-layout check
        binding.rvArrivals.post {
            FileLogger.i(TAG, "After post: rv.width=${binding.rvArrivals.width} " +
                "rv.height=${binding.rvArrivals.height} " +
                "rv.visibility=${binding.rvArrivals.visibility} " +
                "rv.childCount=${binding.rvArrivals.childCount}")
        }
    }

    override fun onPause() { super.onPause(); refreshJob?.cancel() }
    override fun onResume() { super.onResume(); if (_binding != null) scheduleAutoRefresh() }
    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        _binding = null
    }
}
