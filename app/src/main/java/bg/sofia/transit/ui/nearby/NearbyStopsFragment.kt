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
    private lateinit var clockAdapter: ClockStopAdapter

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

        binding.rvClockStops.contentDescription = "Спирки около вас"

        binding.btnDiagnostics.setOnClickListener {
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToDiagnostics()
            )
        }

        // Tapping a stop opens a separate screen with real-time arrivals
        clockAdapter = ClockStopAdapter { stop ->
            findNavController().navigate(
                NearbyStopsFragmentDirections.actionNearbyToArrivals(
                    stopId   = stop.stopId,
                    stopName = stop.stopName
                )
            )
        }
        binding.rvClockStops.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = clockAdapter
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.clockStops.collectLatest { stops ->
                clockAdapter.submitList(stops)
                if (stops.isNotEmpty()) {
                    val desc = stops.joinToString(". ") { cs ->
                        "${cs.clockLabel}: ${cs.stop.stopName}, " +
                        "${cs.distanceMetres.toInt()} метра"
                    }
                    binding.rvClockStops.announceForAccessibility(desc)
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
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateDistanceMeters(15f)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, requireActivity().mainLooper)
        vm.startCompass()
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { vm.onLocationUpdate(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.startCompass()
    }

    override fun onPause() {
        super.onPause()
        vm.stopCompass()
        if (::fusedClient.isInitialized)
            fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
