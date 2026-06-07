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

        // Show "stop_code" (e.g. "0170") rather than internal stop_id ("A0170")
        // — matches what's printed on physical stop signs and the CGM website.
        val codeSuffix = args.stopCode?.takeIf { it.isNotBlank() }?.let { " (код $it)" } ?: ""
        binding.tvStopName.text = "${args.stopName}$codeSuffix"
        binding.tvStopName.contentDescription =
            "Спирка ${args.stopName}${codeSuffix.ifEmpty { "" }}. Списък с пристигания."

        adapter = ArrivalAdapter()
        binding.rvArrivals.layoutManager = LinearLayoutManager(requireContext())
        binding.rvArrivals.adapter = adapter
        // Disable the "change" animation: when a row's contents update in
        // place (ETA ticking down), the default animator cross-fades by
        // swapping in a second ViewHolder, which disturbs TalkBack focus.
        // Turning it off keeps the same view, so focus stays put.
        (binding.rvArrivals.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false
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

    private fun loadArrivals() {
        vm.loadArrivals(stopId = args.stopId, stopCode = args.stopCode)
    }

    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                // 60 s: frequent enough to stay current, infrequent enough
                // that TalkBack users aren't constantly interrupted by the
                // list re-rendering. The "Обнови" button is there for anyone
                // who wants an immediate refresh.
                kotlinx.coroutines.delay(60_000L)
                loadArrivals()
            }
        }
    }

    private fun renderState(state: StopArrivalsState) {
        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE
        binding.btnRefresh.isEnabled = !state.loading

        // While merely loading (no data yet in this tick), don't touch the
        // list — calling submitList with the old/empty list on every refresh
        // cycle disturbs focus. Only update the list when we actually have a
        // resolved result for this load.
        if (state.loading) return

        adapter.submitList(state.arrivals)

        val showEmpty = state.arrivals.isEmpty()
        // Only flip visibility when it actually changes — writing visibility
        // (even to the same value) can emit an accessibility event that makes
        // TalkBack re-evaluate the screen and bounce focus to the top.
        val desiredEmptyVis = if (showEmpty) View.VISIBLE else View.GONE
        if (binding.svEmpty.visibility != desiredEmptyVis) {
            binding.svEmpty.visibility = desiredEmptyVis
            if (showEmpty) {
                binding.tvEmpty.text = "Няма пристигащи превозни средства в момента"
            }
        }
        val desiredListVis = if (state.arrivals.isNotEmpty()) View.VISIBLE else View.GONE
        if (binding.rvArrivals.visibility != desiredListVis) {
            binding.rvArrivals.visibility = desiredListVis
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
