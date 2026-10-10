package bg.sofia.transit.ui.diagnostics

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import bg.sofia.transit.databinding.FragmentDiagnosticsBinding
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.JourneyTrace
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DiagnosticsFragment : Fragment() {

    companion object { private const val TAG = "DiagnosticsFrag" }

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!
    private val vm: DiagnosticsViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        binding.btnRun.setOnClickListener { vm.runDiagnostics() }
        binding.btnLookup.setOnClickListener {
            val stopId = binding.etStopId.text?.toString() ?: ""
            vm.lookupStop(stopId)
        }

        binding.btnTestNearby.setOnClickListener {
            // Get current location and test the nearest-stops query
            val ctx = requireContext()
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(ctx, "Няма разрешение за местоположение", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            @Suppress("MissingPermission")
            com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(ctx)
                .lastLocation
                .addOnSuccessListener { loc ->
                    if (loc == null) {
                        Toast.makeText(ctx, "Няма налично местоположение", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.testNearbyStops(loc.latitude, loc.longitude)
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(ctx, "Грешка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        // Vehicle-position diagnostic. Uses the last known location so the
        // report can rank vehicles by distance from the user — the practical
        // test of whether we can identify the vehicle someone is riding.
        binding.btnDiagnoseVehicles.setOnClickListener {
            val ctx = requireContext()
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ACCESS_FINE_LOCATION
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                // Still useful without a fix — the freshness stats do not
                // depend on knowing where the user is.
                vm.diagnoseVehicles()
                return@setOnClickListener
            }
            @Suppress("MissingPermission")
            com.google.android.gms.location.LocationServices
                .getFusedLocationProviderClient(ctx)
                .lastLocation
                .addOnSuccessListener { loc ->
                    if (loc == null) vm.diagnoseVehicles()
                    else vm.diagnoseVehicles(loc.latitude, loc.longitude)
                }
                .addOnFailureListener { vm.diagnoseVehicles() }
        }

        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnClearLog.setOnClickListener {
            FileLogger.clear()
            JourneyTrace.clear()
            Toast.makeText(requireContext(), "Логът е изчистен", Toast.LENGTH_SHORT).show()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.state.collectLatest { state ->
                binding.pbProgress.visibility =
                    if (state.running) View.VISIBLE else View.GONE
                binding.btnRun.isEnabled = !state.running
                binding.btnLookup.isEnabled = !state.running
                binding.tvReport.text = state.report
            }
        }
    }


    /**
     * Shares the log, and with it the journey trace when there is one (see
     * JourneyTrace) — the raw positions a ride can be replayed from.
     */
    private fun shareLog() {
        val files = listOfNotNull(FileLogger.file(), JourneyTrace.file())
            .filter { it.exists() && it.length() > 0L }
        if (files.isEmpty()) {
            Toast.makeText(requireContext(),
                "Лог файлът е празен", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val authority = "${requireContext().packageName}.fileprovider"
            val uris = ArrayList<Uri>(files.map {
                FileProvider.getUriForFile(requireContext(), authority, it)
            })
            val intent = Intent(
                if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE)
            if (uris.size == 1) intent.putExtra(Intent.EXTRA_STREAM, uris.first())
            else intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            intent.type = "text/plain"
            intent.putExtra(Intent.EXTRA_SUBJECT, "Sofia Transit log")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, "Сподели лог чрез"))
        } catch (e: Exception) {
            Toast.makeText(requireContext(),
                "Грешка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
