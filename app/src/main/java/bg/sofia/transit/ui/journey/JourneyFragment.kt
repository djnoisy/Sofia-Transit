package bg.sofia.transit.ui.journey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentJourneyBinding
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
class JourneyFragment : Fragment() {

    private var _binding: FragmentJourneyBinding? = null
    private val binding get() = _binding!!
    private val vm: JourneyViewModel by viewModels()
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var upcomingAdapter: UpcomingTripsAdapter

    private var lastLat = 0.0
    private var lastLon = 0.0
    private var hasLocation = false

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val locGranted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (locGranted) startLocationAndLoad()
        else Toast.makeText(requireContext(),
            "Необходим е достъп до местоположение", Toast.LENGTH_LONG).show()
        // POST_NOTIFICATIONS denial is non-fatal — the journey still works,
        // just without the notification on the lock-screen.
    }

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentJourneyBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        // Adapter for the upcoming trips list
        upcomingAdapter = UpcomingTripsAdapter { trip -> vm.selectUpcomingTrip(trip) }
        binding.rvUpcoming.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = upcomingAdapter
            contentDescription = "Идващи превозни средства"
        }

        binding.btnRefresh.setOnClickListener {
            if (hasLocation) vm.loadUpcomingTrips(lastLat, lastLon)
        }

        binding.btnEndJourney.setOnClickListener { vm.endJourney() }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state -> renderState(state) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            vm.error.collect { msg ->
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startLocationAndLoad()
        } else {
            permLauncher.launch(requiredPermissions())
        }
    }

    private fun renderState(state: JourneyState) {
        // Hide everything first
        binding.panelUpcoming.visibility       = View.GONE
        binding.panelActiveJourney.visibility  = View.GONE
        binding.btnEndJourney.visibility       = View.GONE
        binding.tvJourneyHint.visibility       = View.GONE

        when (state) {
            is JourneyState.Idle -> {
                binding.tvJourneyHint.visibility = View.VISIBLE
                binding.tvJourneyHint.text = "Определяне на местоположение…"
                binding.tvJourneyHint.contentDescription =
                    "Определяне на местоположение, моля изчакайте."
            }

            is JourneyState.SelectUpcomingTrip -> {
                binding.panelUpcoming.visibility = View.VISIBLE
                binding.pbRefreshing.visibility =
                    if (state.refreshing) View.VISIBLE else View.GONE
                binding.btnRefresh.isEnabled = !state.refreshing

                val count = state.upcoming.size
                binding.tvUpcomingTitle.text = when {
                    state.refreshing && count == 0 -> "Зареждане…"
                    count == 0 -> "Близки превозни средства"
                    count == 1 -> "1 идващо превозно средство"
                    else       -> "$count идващи превозни средства"
                }
                binding.tvUpcomingTitle.contentDescription =
                    binding.tvUpcomingTitle.text

                binding.tvEmptyMessage.visibility =
                    if (count == 0 && !state.refreshing) View.VISIBLE else View.GONE
                binding.rvUpcoming.visibility =
                    if (count > 0) View.VISIBLE else View.GONE

                upcomingAdapter.submitList(state.upcoming)
            }

            is JourneyState.Active -> {
                binding.panelActiveJourney.visibility = View.VISIBLE
                binding.btnEndJourney.visibility      = View.VISIBLE

                val current = state.stops.getOrNull(state.currentIdx)
                val next    = state.stops.getOrNull(state.currentIdx + 1)

                val currentLabel = if (state.atStop)
                    "Спирка: ${current?.stopName ?: "—"}"
                else
                    "В движение"
                binding.tvCurrentStop.text = currentLabel
                binding.tvCurrentStop.contentDescription = currentLabel

                binding.tvNextStop.text = if (next != null)
                    "Следваща: ${next.stopName}"
                else
                    "Крайна спирка"
                binding.tvNextStop.contentDescription = binding.tvNextStop.text

                binding.tvJourneyRoute.text =
                    "Линия ${state.trip.routeShortName} → ${state.trip.headsign}"
                binding.tvJourneyRoute.contentDescription =
                    "Линия ${state.trip.routeShortName} към ${state.trip.headsign}"

                val progress = "${state.currentIdx + 1} / ${state.stops.size} спирки"
                binding.tvProgress.text = progress
                binding.tvProgress.contentDescription = progress
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocationAndLoad() {
        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) {
                lastLat = loc.latitude
                lastLon = loc.longitude
                hasLocation = true
                vm.loadUpcomingTrips(lastLat, lastLon)
            }
        }

        // We use BALANCED accuracy when waiting for the bus (no need for fine
        // GPS until a journey is actually active — that's handled by the service)
        val req = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(50f)
            .build()
        fusedClient.requestLocationUpdates(req, locationCb, requireActivity().mainLooper)
    }

    private val locationCb = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { loc ->
                lastLat = loc.latitude
                lastLon = loc.longitude
                hasLocation = true
                // Only auto-refresh if we're in the selection state (not active journey)
                if (vm.state.value is JourneyState.SelectUpcomingTrip) {
                    vm.loadUpcomingTrips(lastLat, lastLon)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (::fusedClient.isInitialized)
            fusedClient.removeLocationUpdates(locationCb)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
