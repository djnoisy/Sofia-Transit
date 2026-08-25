package bg.sofia.transit.ui.journey

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
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
        binding.btnChooseDestination.setOnClickListener { showDestinationPicker() }
        binding.btnClearDestination.setOnClickListener {
            vm.setDestination(null)
            // Announced through TalkBack, not the speech engine: this is a
            // screen interaction, so it should queue with the screen reader
            // instead of cutting it off. Silent when no screen reader is on —
            // the row returning to "Не е избрана" is the visual feedback.
            binding.root.announceForAccessibility("Спирката за слизане е премахната")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.tracking.collectLatest { render() } }
                launch { vm.selection.collectLatest { render() } }
                launch {
                    vm.error.collect { msg ->
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    }
                }
                // Destination reached → the journey has ended; take the user
                // to the Stops tab as agreed.
                launch {
                    vm.journeyEvents.collect { ev ->
                        when (ev) {
                            // Both endings return the user to the Stops tab:
                            // the journey is over either way.
                            is JourneyService.JourneyEvent.DestinationReached,
                            is JourneyService.JourneyEvent.RouteEnded ->
                                goToStopsTab()
                        }
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
        // The only contentDescription in this panel: "→" is a glyph a sighted
        // user reads as "към", but a screen reader would either skip it or
        // announce the symbol. Everywhere else the visible text is the
        // spoken text.
        binding.tvJourneyRoute.contentDescription =
            state.routeLabel.replace("→", "към")

        val stopName = state.currentStop?.stopName ?: "—"

        // Only ONE stop is shown: the one we're at, or the one we're heading
        // to. The caption switches accordingly.
        if (state.atStop) {
            binding.tvStopCaption.text = "Спирка"
            binding.tvCurrentStop.text = stopName
            binding.tvCurrentStop.contentDescription = null
            // Distance is meaningless while standing at the stop.
            binding.tvDistance.visibility = View.GONE
        } else {
            binding.tvStopCaption.text = "Следваща спирка"
            binding.tvCurrentStop.text = stopName
            binding.tvCurrentStop.contentDescription = null
            binding.tvDistance.visibility = View.VISIBLE
            val d = state.distanceToNextMetres
            binding.tvDistance.text =
                if (d == null) "Определяне на разстояние…" else "Разстояние: $d метра"
            binding.tvDistance.contentDescription = null
        }

        // Remaining stops — to the chosen destination when set, else to the
        // end of the route.
        val remaining = state.stopsRemaining
        val progressText = when {
            state.destinationIdx != null && remaining == 0 -> "Слизане на следващата спирка"
            state.destinationIdx != null ->
                "Още $remaining ${stopWord(remaining)} до края на пътуването"
            else -> "Още $remaining ${stopWord(remaining)} до края на маршрута"
        }
        binding.tvProgress.text = progressText
        binding.tvProgress.contentDescription = null

        // Destination row + button label reflect whether one is chosen.
        val destName = state.destinationStop?.stopName
        if (destName != null) {
            binding.tvDestination.text = destName
            binding.tvDestination.contentDescription = null
            binding.btnChooseDestination.text = "Промени спирката"
            binding.btnChooseDestination.contentDescription = null
            binding.btnClearDestination.visibility = View.VISIBLE

            // Arrival estimate. A live prediction and a delay-adjusted one
            // are close enough in practice to be worded identically; only a
            // raw timetable time (no live data at all) is marked as such.
            // No contentDescription is set anywhere here: TalkBack should
            // read exactly what is on screen, nothing longer.
            val eta = state.destinationEtaEpoch
            if (eta != null && state.etaSource != JourneyService.EtaSource.NONE) {
                val minutes = ((eta - System.currentTimeMillis() / 1000) / 60).toInt()
                val whenText = when {
                    minutes <= 0 -> "сега"
                    minutes == 1 -> "след 1 минута"
                    else         -> "след $minutes минути"
                }
                val text = if (state.etaSource == JourneyService.EtaSource.SCHEDULE)
                    "Пристигане: $whenText (по разписание)"
                else
                    "Пристигане: $whenText"
                binding.tvDestinationEta.visibility = View.VISIBLE
                binding.tvDestinationEta.text = text
                binding.tvDestinationEta.contentDescription = null
            } else {
                binding.tvDestinationEta.visibility = View.GONE
            }
        } else {
            binding.tvDestination.text = "Не е избрана"
            binding.tvDestination.contentDescription = null
            binding.btnChooseDestination.text = "Избери спирка"
            binding.btnChooseDestination.contentDescription = null
            binding.btnClearDestination.visibility = View.GONE
            binding.tvDestinationEta.visibility = View.GONE
        }
    }

    /** Correct Bulgarian plural for the stop counter. */
    private fun stopWord(n: Int) = if (n == 1) "спирка" else "спирки"

    /**
     * Lets the user pick (or clear) the alighting stop. Lists only stops
     * still ahead, so a passed stop can't be chosen by accident.
     */
    private fun showDestinationPicker() {
        val state = vm.tracking.value as? JourneyService.TrackingState.Tracking ?: return

        // Candidates: every stop after the one we're heading to, plus the
        // current one when we're still en route to it.
        val firstCandidate = if (state.atStop) state.currentIdx + 1 else state.currentIdx
        if (firstCandidate > state.stops.lastIndex) {
            Toast.makeText(requireContext(),
                "Няма следващи спирки по маршрута", Toast.LENGTH_SHORT).show()
            return
        }

        val indices = (firstCandidate..state.stops.lastIndex).toList()
        val names = indices.map { state.stops[it].stopName }

        AlertDialog.Builder(requireContext())
            .setTitle("Спирка за слизане")
            .setItems(names.toTypedArray()) { _, which ->
                vm.setDestination(indices[which])
                binding.root.announceForAccessibility(
                    "Спирка за слизане: ${names[which]}")
            }
            .setNegativeButton("Отказ", null)
            .show()
    }

    private fun renderSelection(state: SelectionState) {
        binding.panelActiveJourney.visibility = View.GONE
        binding.btnEndJourney.visibility      = View.GONE

        if (!state.hasLocation) {
            binding.panelUpcoming.visibility = View.GONE
            binding.tvJourneyHint.visibility = View.VISIBLE
            binding.tvJourneyHint.text = "Определяне на местоположение…"
            binding.tvJourneyHint.contentDescription = null
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
        binding.tvUpcomingTitle.contentDescription = null

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

    /** Switches the bottom navigation to the Stops tab. */
    private fun goToStopsTab() {
        try {
            requireActivity()
                .findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    bg.sofia.transit.R.id.bottomNav
                )?.selectedItemId = bg.sofia.transit.R.id.nearbyFragment
        } catch (e: Exception) {
            bg.sofia.transit.util.FileLogger.e(
                "JourneyFragment", "Could not switch to Stops tab: ${e.message}")
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
