package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentSearchTabBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "Search" tab — lets the user look up any stop by code or name.
 * Search is triggered on button-press only, not while typing, to avoid
 * the TalkBack overhead of announcing results on every keystroke.
 */
@AndroidEntryPoint
class SearchTabFragment : Fragment() {

    private var _binding: FragmentSearchTabBinding? = null
    private val binding get() = _binding!!

    private val vm: SearchStopsViewModel by viewModels()
    private lateinit var resultsAdapter: SearchResultAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentSearchTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        resultsAdapter = SearchResultAdapter { stop ->
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToArrivals(
                    stopId   = stop.stopId,
                    stopName = stop.stopName,
                    stopCode = stop.stopCode
                )
            )
        }
        binding.rvResults.layoutManager = LinearLayoutManager(requireContext())
        binding.rvResults.adapter = resultsAdapter

        binding.btnSearch.setOnClickListener { triggerSearch() }
        binding.etQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                triggerSearch()
                true
            } else false
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.results.collectLatest { state ->
                resultsAdapter.submitList(state.stops)
                binding.tvStatus.text = when {
                    state.lastQuery.isEmpty() ->
                        "Въведете име или код на спирка и натиснете „Търси“."
                    state.stops.isEmpty() ->
                        "Няма намерени спирки за „${state.lastQuery}“."
                    else ->
                        "Намерени ${state.stops.size} спирки за „${state.lastQuery}“."
                }
                binding.rvResults.announceForAccessibility(binding.tvStatus.text)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.error.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerSearch() {
        val query = binding.etQuery.text?.toString().orEmpty()
        vm.search(query)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
