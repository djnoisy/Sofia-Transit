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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentJourneyBinding
import bg.sofia.transit.service.JourneyService
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
 * Journey tab. Two visual modes, driven by two independent flows:
 *  - selection list (vm.selection)              — picking an arriving vehicle
 *  - active journey (JourneyService.trackingState) — live tracking
 * The service state wins: while Tracking, the selection UI is hidden.
 * Because tracking state lives in the service, switching tabs or rotating
 * never interrupts a journey.
 */
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
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.tracking.collectLatest { render() } }
                launch { vm.selection.collectLatest { render() } }
                launch {
                    vm.error.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
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

    /** Single render entry: decides between tracking and selection modes. */
    private fun render() {
        val trackingState = vm.tracking.value
        if (trackingState is JourneyService.TrackingState.Tracking) {
            renderTracking(trackingState)
        } else {
            renderSelection(vm.selection.value)
        }
    }

    private fun renderTracking(state: JourneyService.TrackingState.Tracking) {
        binding.panelUpcoming.visibility      = View.GONE
        binding.tvJourneyHint.visibility      = View.GONE
        binding.panelActiveJourney.visibility = View.VISIBLE
        binding.btnEndJourney.visibility      = View.VISIBLE

        binding.tvJourneyRoute.text = state.routeLabel
        binding.tvJourneyRoute.contentDescription =
            state.routeLabel.replace("→", "към")

        val current = state.stops.getOrNull(state.currentIdx)
        val currentLabel = if (state.atStop)
            "Спирка: ${current?.stopName ?: "—"}"
        else
            "Към: ${current?.stopName ?: "—"}"
        binding.tvCurrentStop.text = currentLabel
        binding.tvCurrentStop.contentDescription = currentLabel

        // Live distance to the stop we're heading to
        val dist = state.distanceToNextMetres
        binding.tvDistance.text = when {
            dist == null -> "Определяне на разстояние…"
            else         -> "$dist метра"
        }
        binding.tvDistance.contentDescription = when {
            dist == null -> "Определяне на разстоянието до спирката"
            else         -> "$dist метра до спирката"
        }

        val next = state.stops.getOrNull(state.currentIdx + 1)
        binding.tvNextStop.text = if (next != null)
            "След това: ${next.stopName}"
        else
            "Крайна спирка"
        binding.tvNextStop.contentDescription = binding.tvNextStop.text

        val progress = "${state.currentIdx + 1} / ${state.stops.size} спирки"
        binding.tvProgress.text = progress
        binding.tvProgress.contentDescription = progress
    }

    private fun renderSelection(state: SelectionState) {
        binding.panelActiveJourney.visibility = View.GONE
        binding.btnEndJourney.visibility      = View.GONE

        if (!state.hasLocation) {
            binding.panelUpcoming.visibility = View.GONE
            binding.tvJourneyHint.visibility = View.VISIBLE
            binding.tvJourneyHint.text = "Определяне на местоположение…"
            binding.tvJourneyHint.contentDescription =
                "Определяне на местоположение, моля изчакайте."
            return
        }

        binding.tvJourneyHint.visibility = View.GONE
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
        binding.tvUpcomingTitle.contentDescription = binding.tvUpcomingTitle.text

        binding.tvEmptyMessage.visibility =
            if (count == 0 && !state.refreshing) View.VISIBLE else View.GONE
        binding.rvUpcoming.visibility =
            if (count > 0) View.VISIBLE else View.GONE

        upcomingAdapter.submitList(state.upcoming)
    }

    // ── Location for the SELECTION list only (tracking GPS lives in the
    //    service). Balanced accuracy is enough while waiting for the bus. ──
    @Suppress("MissingPermission")
    private fun startLocationAndLoad() {
        fusedClient = LocationServices.getFusedLocationProviderClient(requireContext())
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && vm.tracking.value !is JourneyService.TrackingState.Tracking) {
                lastLat = loc.latitude
                lastLon = loc.longitude
                hasLocation = true
                vm.loadUpcomingTrips(lastLat, lastLon)
            }
        }
        val req = LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30_000L)
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
                if (vm.tracking.value !is JourneyService.TrackingState.Tracking) {
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
