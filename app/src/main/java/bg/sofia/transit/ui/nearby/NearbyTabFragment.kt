package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentNearbyTabBinding
import bg.sofia.transit.util.PermissionRequester
import bg.sofia.transit.util.Permissions
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The "Nearby" tab — shows the 10 closest stops with their distance.
 * Hosted inside [NearbyStopsFragment] (the tab container). All click
 * navigation goes through the parent fragment because nav actions live
 * on its destination node.
 */
@AndroidEntryPoint
class NearbyTabFragment : Fragment() {

    private var _binding: FragmentNearbyTabBinding? = null
    private val binding get() = _binding!!

    private val vm: NearbyViewModel by viewModels()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var nearbyAdapter: NearbyStopAdapter

    // The system dialog is asked for only from the button: the first-run
    // permissions screen and Settings are where permissions are given.
    private val requester = PermissionRequester(this, { requireActivity() }) {
        if (_binding != null) refreshLocationAccess()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentNearbyTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        nearbyAdapter = NearbyStopAdapter { stopWithDist ->
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToArrivals(
                    stopId   = stopWithDist.stop.stopId,
                    stopName = stopWithDist.stop.stopName,
                    stopCode = stopWithDist.stop.stopCode
                )
            )
        }
        binding.rvStops.layoutManager = LinearLayoutManager(requireContext())
        binding.rvStops.adapter = nearbyAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.nearestStops.collectLatest { stops ->
                nearbyAdapter.submitList(stops)
                if (stops.isNotEmpty()) {
                    binding.rvStops.announceForAccessibility(
                        "Намерени ${stops.size} спирки около вас"
                    )
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.error.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantLocation.setOnClickListener { requester.requestLocation() }
    }

    /**
     * Starts location updates when access is there, otherwise shows the
     * message with its button. Runs on every resume: access may have been
     * given in the system settings meanwhile, and onPause stops the updates.
     */
    private fun refreshLocationAccess() {
        val allowed = Permissions.hasAnyLocation(requireContext())
        binding.panelNoLocation.visibility = if (allowed) View.GONE else View.VISIBLE
        if (allowed) startLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        refreshLocationAccess()
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateIntervalMillis(15_000L)
            .setMinUpdateDistanceMeters(15f)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, requireActivity().mainLooper)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { vm.onLocationUpdate(it) }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::fusedClient.isInitialized)
            fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
