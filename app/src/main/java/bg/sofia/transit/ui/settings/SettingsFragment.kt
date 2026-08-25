package bg.sofia.transit.ui.settings

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import bg.sofia.transit.databinding.FragmentSettingsBinding
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.AppSettings
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * App settings. Currently one section (speech), structured so further
 * sections can be appended without reshaping the screen.
 *
 * The engine list is obtained from a throwaway TextToSpeech instance — the
 * platform exposes installed engines only through a live instance. It is
 * shut down in onDestroyView.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var settings: AppSettings

    /** Used both to enumerate engines and to speak the test phrase. */
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        initTts()
        showCurrentEngine()

        binding.btnChooseEngine.setOnClickListener { showEnginePicker() }
        binding.btnTestSpeech.setOnClickListener { speakTest() }
    }

    /** (Re)creates the TTS instance using the currently chosen engine. */
    private fun initTts() {
        tts?.shutdown()
        ttsReady = false
        val chosen = settings.ttsEngine
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("bg", "BG"))
                // Default media stream, matching JourneyService — the test
                // must sound exactly like a real announcement.
                ttsReady = true
            }
        }
        tts = if (chosen.isNotBlank()) {
            TextToSpeech(requireContext(), listener, chosen)
        } else {
            TextToSpeech(requireContext(), listener)
        }
    }

    private fun showCurrentEngine() {
        val chosen = settings.ttsEngine
        val label = if (chosen.isBlank()) {
            "Системната по подразбиране"
        } else {
            // Resolve the package name to its human-readable label.
            tts?.let { t ->
                settings.availableEngines(t)
                    .firstOrNull { it.packageName == chosen }?.label
            } ?: chosen
        }
        binding.tvEngineCurrent.text = label
    }

    private fun showEnginePicker() {
        val t = tts
        if (t == null) {
            Toast.makeText(requireContext(),
                "Речевите машини не са достъпни", Toast.LENGTH_SHORT).show()
            return
        }

        val engines = settings.availableEngines(t)
        if (engines.isEmpty()) {
            Toast.makeText(requireContext(),
                "Не са намерени инсталирани речеви машини", Toast.LENGTH_LONG).show()
            return
        }

        // First entry always follows the system setting, so the user can
        // return to default without knowing which engine that is.
        val labels = mutableListOf("Системната по подразбиране")
        val packages = mutableListOf(AppSettings.ENGINE_SYSTEM_DEFAULT)
        engines.forEach { labels.add(it.label); packages.add(it.packageName) }

        AlertDialog.Builder(requireContext())
            .setTitle("Речева машина")
            .setItems(labels.toTypedArray()) { _, which ->
                settings.ttsEngine = packages[which]
                initTts()          // rebind this screen's test instance
                // A journey may be running right now; make it pick up the new
                // engine immediately instead of only on the next journey.
                notifyServiceEngineChanged()
                showCurrentEngine()
                binding.root.announceForAccessibility(
                    "Избрана речева машина: ${labels[which]}")
            }
            .setNegativeButton("Отказ", null)
            .show()
    }

    /**
     * Tells a running JourneyService to rebind its TTS. Binds without
     * AUTO_CREATE, so nothing is started when no journey is active.
     */
    private fun notifyServiceEngineChanged() {
        if (JourneyService.trackingState.value !is JourneyService.TrackingState.Tracking) return
        val ctx = requireContext().applicationContext
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName, binder: android.os.IBinder
            ) {
                (binder as JourneyService.LocalBinder).get().reloadTtsEngine()
                try { ctx.unbindService(this) } catch (_: Exception) {}
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        try {
            ctx.bindService(
                android.content.Intent(ctx, JourneyService::class.java), conn, 0)
        } catch (e: Exception) {
            bg.sofia.transit.util.FileLogger.w(
                "SettingsFragment", "Could not notify service: ${e.message}")
        }
    }

    private fun speakTest() {
        if (!ttsReady) {
            Toast.makeText(requireContext(),
                "Речевата машина още се подготвя", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(
            "Следваща спирка: Люлин център.",
            TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString()
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.shutdown()
        tts = null
        _binding = null
    }
}
