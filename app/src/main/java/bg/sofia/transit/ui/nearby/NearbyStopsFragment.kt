package bg.sofia.transit.ui.nearby

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentNearbyStopsBinding
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class NearbyStopsFragment : Fragment() {

    private var _binding: FragmentNearbyStopsBinding? = null
    private val binding get() = _binding!!

    private val vm: NearbyViewModel by viewModels()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var nearbyAdapter: NearbyStopAdapter

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            startLocationUpdates()
        } else {
            Toast.makeText(requireContext(),
                "Необходим е достъп до местоположение", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentNearbyStopsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.btnDiagnostics.setOnClickListener {
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToDiagnostics()
            )
        }

        nearbyAdapter = NearbyStopAdapter { stopWithDist ->
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToArrivals(
                    stopId   = stopWithDist.stop.stopId,
                    stopName = stopWithDist.stop.stopName
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

        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val perms = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (perms.all { ContextCompat.checkSelfPermission(requireContext(), it) ==
                        PackageManager.PERMISSION_GRANTED }) {
            startLocationUpdates()
        } else {
            locPermLauncher.launch(perms)
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        // 30-second interval matches the user's request: refresh only when
        // the location actually changes (the VM also has its own 20m filter).
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
