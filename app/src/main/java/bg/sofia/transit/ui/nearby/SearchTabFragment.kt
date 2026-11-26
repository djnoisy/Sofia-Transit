package bg.sofia.transit.ui.nearby

import android.location.Location
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentSearchTabBinding
import bg.sofia.transit.ui.board.StopBoardActivity
import bg.sofia.transit.util.LocationHelper
import bg.sofia.transit.util.Permissions
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Спирки → "Търсене" (the owner, 26 Nov 2026): a stop found by its name or
 * code (SearchViewModel, util/StopSearch) — at most ten, as cards, with how
 * far each is when where we are is known (the phone's last known place;
 * nothing asked for) — opens its window, on its board. What is typed stays
 * when another tab is chosen. The keyboard comes only when the field is
 * activated.
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
            StopBoardActivity.start(requireContext(), stop.stopId, stop.stopName, stop.stopCode)
        }
        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = adapter

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
        binding.tvEmpty.visibility = if (found != null && found.isEmpty()) View.VISIBLE else View.GONE
        binding.rvResults.visibility = if (found.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        // Where we are, for how far: the phone's last known place, if allowed —
        // no updates asked for; the stops found shown again with it.
        val ctx = requireContext()
        if (!Permissions.hasAnyLocation(ctx)) { here = null; return }
        try {
            @Suppress("MissingPermission")
            LocationServices.getFusedLocationProviderClient(ctx).lastLocation.addOnSuccessListener { loc ->
                if (_binding == null || loc == null) return@addOnSuccessListener
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

    private companion object {
        /** Moved this far since, the distances shown again. */
        const val MOVED_M = 50f
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
