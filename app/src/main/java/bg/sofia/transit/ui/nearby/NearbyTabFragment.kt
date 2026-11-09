package bg.sofia.transit.ui.nearby

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import bg.sofia.transit.databinding.FragmentNearbyTabBinding
import bg.sofia.transit.ui.board.StopBoardActivity
import bg.sofia.transit.util.LocationSwitchWatcher
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

    /** Location switched off or on with the screen in front; said when off. */
    private val locationSwitch = LocationSwitchWatcher { on ->
        if (_binding == null) return@LocationSwitchWatcher
        refreshLocationAccess()
        if (!on && Permissions.hasAnyLocation(requireContext())) {
            binding.tvNoLocation.announceForAccessibility(LOCATION_OFF)
        }
    }

    private companion object {
        const val LOCATION_OFF = "Местоположението на телефона е изключено."
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentNearbyTabBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        // A stop's arrivals: the "Виртуално табло" window (StopBoardActivity).
        nearbyAdapter = NearbyStopAdapter { stopWithDist ->
            StopBoardActivity.start(requireContext(),
                stopId   = stopWithDist.stop.stopId,
                stopName = stopWithDist.stop.stopName,
                stopCode = stopWithDist.stop.stopCode)
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
        binding.btnTurnOnLocation.setOnClickListener { requester.turnOnLocation() }
    }

    /**
     * Starts location updates when access is there, otherwise shows the
     * message with its button; with access but location switched off in the
     * phone's settings, says so, with "Включи" (the owner, 9 Nov 2026) —
     * before, the list simply stayed empty. Runs on every resume: access may
     * have been given in the system settings meanwhile, and onPause stops
     * the updates. The updates are asked for with location off too: they
     * come by themselves once it is on.
     */
    private fun refreshLocationAccess() {
        val ctx = requireContext()
        val allowed = Permissions.hasAnyLocation(ctx)
        val off = allowed && !Permissions.isLocationOn(ctx)
        binding.panelNoLocation.visibility = if (allowed && !off) View.GONE else View.VISIBLE
        binding.tvNoLocation.text = if (off) LOCATION_OFF else "Няма достъп до местоположението."
        binding.btnGrantLocation.visibility = if (allowed) View.GONE else View.VISIBLE
        binding.btnTurnOnLocation.visibility = if (off) View.VISIBLE else View.GONE
        if (allowed) startLocationUpdates()
    }

    override fun onResume() {
        super.onResume()
        locationSwitch.start(requireContext())
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
        locationSwitch.stop()
        if (::fusedClient.isInitialized)
            fusedClient.removeLocationUpdates(locationCallback)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
