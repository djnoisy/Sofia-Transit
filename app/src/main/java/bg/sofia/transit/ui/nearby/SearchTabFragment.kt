package bg.sofia.transit.ui.nearby

import android.location.Location
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentSearchTabBinding
import bg.sofia.transit.ui.board.StopBoardActivity
import bg.sofia.transit.ui.common.Keyboard
import bg.sofia.transit.util.LocationHelper
import bg.sofia.transit.util.NearbyLinesLoader
import bg.sofia.transit.util.Permissions
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Спирки → "Търсене" (the owner, 26 Nov 2026): a stop found by its name or
 * code (SearchViewModel, util/StopSearch) — at most ten, as cards, with how
 * far each is when where we are is known (the phone's last known place, if
 * recent; nothing asked for) — opens its window, on its board. What is
 * typed stays when another tab is chosen. The keyboard comes only when the
 * field is activated, and goes with "Търсене" on it, a stop opened, another
 * tab or window.
 */
@AndroidEntryPoint
class SearchTabFragment : Fragment() {

    private var _binding: FragmentSearchTabBinding? = null
    private val binding get() = _binding!!
    private val vm: SearchViewModel by viewModels()
    private lateinit var adapter: SearchResultAdapter

    /** Where we are, as last known to the phone; null when not known or not allowed. */
    private var here: Location? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentSearchTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        adapter = SearchResultAdapter { stop ->
            // The keyboard put away first: else it may come back over the list on return.
            hideKeyboard()
            StopBoardActivity.start(requireContext(), stop.stopId, stop.stopName, stop.stopCode)
        }
        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = adapter
        // A card's distance changed in place: the same view, TalkBack's focus kept.
        (binding.rvResults.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)
            ?.supportsChangeAnimations = false

        // The keyboard lies over the window: room under the last card (as in "Пътуване").
        Keyboard.keepClear(binding.rvResults)
        // "Търсене" on the keyboard: it is put away, the stops found stay.
        binding.etSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false
        }

        binding.etSearch.doAfterTextChanged { text ->
            binding.btnClearSearch.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
            vm.search(text?.toString().orEmpty())
        }
        binding.btnClearSearch.setOnClickListener {
            binding.etSearch.text?.clear()
            binding.etSearch.requestFocus()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.found.collect { found -> show(found) }
        }
    }

    private fun show(found: List<bg.sofia.transit.data.db.entity.Stop>?) {
        val at = here
        adapter.submitList(found.orEmpty()) { s ->
            at?.let { LocationHelper.distanceMetres(it.latitude, it.longitude, s.stopLat, s.stopLon) }
        }
        val none = found != null && found.isEmpty()
        // Said once, as it comes: shown with the list hidden in the same
        // frame, a live region may not be told apart (review of 26 Nov 2026).
        if (none && binding.tvEmpty.visibility != View.VISIBLE) binding.tvEmpty.announceForAccessibility(binding.tvEmpty.text)
        binding.tvEmpty.visibility = if (none) View.VISIBLE else View.GONE
        binding.rvResults.visibility = if (found.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // The keyboard may have gone while the page was away, unseen by it:
        // the room under the list worked out again.
        Keyboard.again(binding.rvResults)
        // Where we are, for how far: the phone's last known place, if allowed —
        // no updates asked for; the stops found shown again with it.
        val ctx = requireContext()
        if (!Permissions.hasAnyLocation(ctx)) { here = null; return }
        try {
            @Suppress("MissingPermission")
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { loc ->
                if (_binding == null || loc == null) return@addOnSuccessListener
                // Only a recent one, as in "Пътуване": an older one may be
                // from somewhere else (the owner, 9 Nov 2026) — no distance then.
                if (!NearbyLinesLoader.lastKnownUsable(
                        (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000)) {
                    if (here != null) { here = null; show(vm.found.value) }
                    return@addOnSuccessListener
                }
                // Drawn again only when it tells more: the list drawn anew
                // moves TalkBack's focus off it.
                val before = here
                here = loc
                if (before == null || before.distanceTo(loc) >= MOVED_M) show(vm.found.value)
            }
        } catch (e: SecurityException) {
            here = null
        }
    }

    override fun onPause() {
        super.onPause()
        // Another tab chosen, or another window: the keyboard not left over it.
        hideKeyboard()
    }

    private fun hideKeyboard() {
        _binding?.etSearch?.let { Keyboard.hide(it) }
    }

    private companion object {
        /** Moved this far since, the distances shown again. */
        const val MOVED_M = 50f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
