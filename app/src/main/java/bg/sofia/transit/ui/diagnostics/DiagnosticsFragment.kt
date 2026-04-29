package bg.sofia.transit.ui.diagnostics

import android.content.Intent
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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class DiagnosticsFragment : Fragment() {

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

        binding.btnShareLog.setOnClickListener { shareLog() }
        binding.btnClearLog.setOnClickListener {
            FileLogger.clear()
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

    private fun shareLog() {
        val file = FileLogger.file()
        if (file == null || !file.exists() || file.length() == 0L) {
            Toast.makeText(requireContext(),
                "Лог файлът е празен", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Sofia Transit log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
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
