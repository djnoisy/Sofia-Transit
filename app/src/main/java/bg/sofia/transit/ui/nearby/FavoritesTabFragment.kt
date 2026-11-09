package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentFavoritesTabBinding
import bg.sofia.transit.ui.board.StopBoardActivity
import bg.sofia.transit.util.FavoriteStops
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Спирки → "Любими" (the owner, 9 Nov 2026; in place of "Търсене"): the
 * favourite stops, in the order added; a tap opens the stop's virtual board,
 * where it is also removed from them. Follows the list as it changes.
 */
@AndroidEntryPoint
class FavoritesTabFragment : Fragment() {

    private var _binding: FragmentFavoritesTabBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var favorites: FavoriteStops

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?): View {
        _binding = FragmentFavoritesTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)
        val adapter = FavoriteStopAdapter { s ->
            StopBoardActivity.start(requireContext(), s.stopId, s.stopName, s.stopCode)
        }
        binding.rvFavorites.layoutManager = LinearLayoutManager(requireContext())
        binding.rvFavorites.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                favorites.all.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvFavorites.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
