package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.os.bundleOf
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.R
import bg.sofia.transit.databinding.FragmentStopArrivalsBinding
import bg.sofia.transit.util.FavoriteStop
import bg.sofia.transit.util.FavoriteStops
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The arrivals at one stop — the board of the "Виртуално табло" window
 * (StopBoardActivity), with its top-bar actions: "Обнови" and adding to or
 * removing from the favourites (the owner, 9 Nov 2026).
 */
@AndroidEntryPoint
class StopArrivalsFragment : Fragment() {

    companion object {
        private const val TAG = "StopArrivalsFrag"
        private const val ARG_STOP_ID = "stopId"
        private const val ARG_STOP_NAME = "stopName"
        private const val ARG_STOP_CODE = "stopCode"

        fun newInstance(stopId: String, stopName: String, stopCode: String?) =
            StopArrivalsFragment().apply {
                arguments = bundleOf(ARG_STOP_ID to stopId, ARG_STOP_NAME to stopName,
                    ARG_STOP_CODE to stopCode)
            }
    }

    private var _binding: FragmentStopArrivalsBinding? = null
    private val binding get() = _binding!!
    private val vm: StopArrivalsViewModel by viewModels()

    @Inject lateinit var favorites: FavoriteStops

    private val stopId: String get() = requireArguments().getString(ARG_STOP_ID).orEmpty()
    private val stopName: String get() = requireArguments().getString(ARG_STOP_NAME) ?: stopId
    private val stopCode: String? get() = requireArguments().getString(ARG_STOP_CODE)

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
        val codeSuffix = stopCode?.takeIf { it.isNotBlank() }?.let { " (код $it)" } ?: ""
        binding.tvStopName.text = "$stopName$codeSuffix"
        binding.tvStopName.contentDescription =
            "Спирка $stopName${codeSuffix.ifEmpty { "" }}. Списък с пристигания."

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

        // "Обнови" and the favourites, in the window's top bar.
        requireActivity().addMenuProvider(boardMenu, viewLifecycleOwner, Lifecycle.State.RESUMED)

        // The lines' official colours for the badges (redesign, stage b).
        viewLifecycleOwner.lifecycleScope.launch {
            adapter.setRouteColors(vm.routeColors())
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
        vm.loadArrivals(stopId = stopId, stopCode = stopCode)
    }

    /** The top bar's actions: refresh, and the favourite's state, shown and changed. */
    private val boardMenu = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
            inflater.inflate(R.menu.stop_board, menu)
        }

        override fun onPrepareMenu(menu: Menu) {
            val fav = favorites.contains(stopId)
            menu.findItem(R.id.actionFavorite)?.apply {
                title = if (fav) "Премахни от любими" else "Добави в любими"
                setIcon(if (fav) R.drawable.ic_star else R.drawable.ic_star_outline)
            }
        }

        override fun onMenuItemSelected(item: MenuItem): Boolean = when (item.itemId) {
            R.id.actionRefresh -> { loadArrivals(); true }
            R.id.actionFavorite -> { toggleFavorite(); true }
            else -> false
        }
    }

    private fun toggleFavorite() {
        val message = if (favorites.contains(stopId)) {
            favorites.remove(stopId)
            "Спирката е премахната от любими"
        } else {
            favorites.add(FavoriteStop(stopId, stopName, stopCode))
            "Спирката е добавена в любими"
        }
        FileLogger.i(TAG, "$message: $stopId")
        requireActivity().invalidateOptionsMenu()
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun scheduleAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                // 60 s: frequent enough to stay current, infrequent enough
                // that TalkBack users aren't constantly interrupted by the
                // list re-rendering. "Обнови" in the top bar is there for
                // anyone who wants an immediate refresh.
                kotlinx.coroutines.delay(60_000L)
                loadArrivals()
            }
        }
    }

    private fun renderState(state: StopArrivalsState) {
        binding.pbLoading.visibility =
            if (state.loading) View.VISIBLE else View.GONE

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
