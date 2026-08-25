package bg.sofia.transit.ui.settings

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import bg.sofia.transit.databinding.FragmentSettingsBinding
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.util.AppSettings
import bg.sofia.transit.util.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

/**
 * App settings. One section for now (speech), structured so further sections
 * can be appended without reshaping the screen.
 *
 * The engine list comes from a live TextToSpeech instance — the platform
 * exposes installed engines only that way. The same instance speaks the
 * preview, so what the user hears is exactly what a journey will sound like.
 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    @Inject lateinit var settings: AppSettings

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    /** Package names parallel to the spinner's visible labels. */
    private var enginePackages: List<String> = emptyList()
    /**
     * A Spinner fires onItemSelected as soon as its adapter and selection are
     * set, which would look like a user choice and re-init TTS on every screen
     * open. This suppresses that first, programmatic callback.
     */
    private var suppressSpinnerCallback = true

    companion object {
        private const val TAG = "SettingsFragment"
        private const val SAMPLE = "Следваща спирка: Люлин център."
        /** Slider steps: 0.1 … 2.0 in 0.1 increments → 20 positions. */
        private const val STEPS = 19
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, saved: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, saved: Bundle?) {
        super.onViewCreated(view, saved)

        initTts()
        setUpRateSlider()

        binding.btnTestSpeech.setOnClickListener { speak(SAMPLE) }
    }

    // ── Speech engine ─────────────────────────────────────────────────────

    /** (Re)creates the TTS instance using the currently chosen engine. */
    private fun initTts() {
        tts?.shutdown()
        ttsReady = false
        val chosen = settings.ttsEngine
        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("bg", "BG"))
                tts?.setSpeechRate(settings.speechRate)
                ttsReady = true
                // Engine enumeration needs a live instance; do it here, on the
                // main thread, and only if the view is still around.
                view?.post { _binding?.let { populateEngineSpinner() } }
            } else {
                FileLogger.w(TAG, "TTS init failed: $status")
            }
        }
        tts = if (chosen.isNotBlank()) {
            TextToSpeech(requireContext(), listener, chosen)
        } else {
            TextToSpeech(requireContext(), listener)
        }
    }

    /**
     * Fills the spinner with the installed engines, preceded by an entry that
     * follows the system default so the user can return to it without knowing
     * which engine that is. Called once TTS is ready, since the engine list is
     * only available from a live instance.
     */
    private fun populateEngineSpinner() {
        if (enginePackages.isNotEmpty()) return   // already built
        val t = tts ?: return
        val engines = settings.availableEngines(t)

        val labels = mutableListOf("Системната по подразбиране")
        val packages = mutableListOf(AppSettings.ENGINE_SYSTEM_DEFAULT)
        engines.forEach { labels.add(it.label); packages.add(it.packageName) }
        enginePackages = packages

        val adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)

        suppressSpinnerCallback = true
        binding.spinnerEngine.adapter = adapter
        val current = packages.indexOf(settings.ttsEngine).coerceAtLeast(0)
        binding.spinnerEngine.setSelection(current)

        binding.spinnerEngine.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>, view: View?, position: Int, id: Long
                ) {
                    if (suppressSpinnerCallback) {
                        suppressSpinnerCallback = false
                        return
                    }
                    val chosen = enginePackages.getOrNull(position) ?: return
                    if (chosen == settings.ttsEngine) return
                    settings.ttsEngine = chosen
                    initTts()                    // rebind this screen's instance
                    notifyServiceSettingsChanged(engineChanged = true)
                }

                override fun onNothingSelected(parent: AdapterView<*>) {}
            }
    }


    // ── Speaking rate ─────────────────────────────────────────────────────

    private fun setUpRateSlider() {
        binding.seekRate.max = STEPS
        binding.seekRate.progress = rateToProgress(settings.speechRate)
        updateRateLabel(settings.speechRate)

        binding.seekRate.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    // Label follows the thumb; speaking waits for release so
                    // dragging doesn't fire a preview on every increment.
                    updateRateLabel(progressToRate(p))
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {
                    val rate = progressToRate(sb.progress)
                    settings.speechRate = rate
                    tts?.setSpeechRate(rate)
                    notifyServiceSettingsChanged(engineChanged = false)
                    // Preview at the new rate, so the choice can be judged by
                    // ear rather than by the number.
                    speak(SAMPLE)
                }
            })
    }

    private fun progressToRate(progress: Int): Float =
        (AppSettings.RATE_MIN + progress * AppSettings.RATE_STEP)
            .coerceIn(AppSettings.RATE_MIN, AppSettings.RATE_MAX)

    private fun rateToProgress(rate: Float): Int =
        Math.round((rate - AppSettings.RATE_MIN) / AppSettings.RATE_STEP)
            .coerceIn(0, STEPS)

    private fun updateRateLabel(rate: Float) {
        val text = String.format(Locale.US, "%.1f×", rate)
        binding.tvRateValue.text = text
        // The description is the value alone — the caption above is a normal
        // element and is read on its own, so repeating it here would announce
        // it twice. A raw SeekBar would otherwise say "1 of 19", which is
        // meaningless; this is the same "1.4×" shown on screen.
        binding.seekRate.contentDescription = text
    }

    // ── Applying changes to a running journey ─────────────────────────────

    /**
     * Pushes a settings change into a journey that is already running, so it
     * takes effect immediately instead of only on the next journey. Binds
     * without AUTO_CREATE, so nothing starts when no journey is active.
     */
    private fun notifyServiceSettingsChanged(engineChanged: Boolean) {
        if (JourneyService.trackingState.value !is JourneyService.TrackingState.Tracking) return
        val ctx = requireContext().applicationContext
        val conn = object : android.content.ServiceConnection {
            override fun onServiceConnected(
                name: android.content.ComponentName, binder: android.os.IBinder
            ) {
                val svc = (binder as JourneyService.LocalBinder).get()
                if (engineChanged) svc.reloadTtsEngine() else svc.applySpeechRate()
                try { ctx.unbindService(this) } catch (_: Exception) {}
            }
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        try {
            ctx.bindService(
                android.content.Intent(ctx, JourneyService::class.java), conn, 0)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not notify service: ${e.message}")
        }
    }

    private fun speak(text: String) {
        if (!ttsReady) {
            Toast.makeText(requireContext(),
                "Речевата машина още се подготвя", Toast.LENGTH_SHORT).show()
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UUID.randomUUID().toString())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.shutdown()
        tts = null
        _binding = null
    }
}
