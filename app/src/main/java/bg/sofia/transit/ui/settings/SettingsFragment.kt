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
import bg.sofia.transit.R
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

    /** Pending debounced preview of the speaking rate. */
    private var previewRunnable: Runnable? = null

    companion object {
        private const val TAG = "SettingsFragment"
        private const val SAMPLE = "Следваща спирка: Люлин център."
        /** Slider steps: 0.1 … 2.0 in 0.1 increments → 20 positions. */
        private const val STEPS = 19
        /** Debounce before previewing a new rate, in milliseconds. */
        private const val PREVIEW_DELAY_MS = 600L
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
        setUpApproachMode()
        setUpAutoDirection()
        showBatteryStatus()

        binding.btnTestSpeech.setOnClickListener { speak(SAMPLE) }
        binding.btnBatterySettings.setOnClickListener { openBatterySettings() }
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

        // Custom item layouts, not the platform ones: the stock dropdown row
        // is a CheckedTextView (screen reader says "не е отметнато") and it
        // follows the system theme, which rendered a dark popup inside this
        // light app.
        val adapter = ArrayAdapter(
            requireContext(), R.layout.item_spinner_selected, labels)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)

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
                    updateRateLabel(progressToRate(p))
                    // Persist and apply here, NOT in onStopTrackingTouch:
                    // a screen-reader user adjusts a SeekBar with swipe
                    // gestures, which never produce start/stop tracking
                    // callbacks. Relying on those meant the rate was silently
                    // discarded for exactly the users who need it most.
                    if (fromUser) applyRate(progressToRate(p))
                }

                override fun onStartTrackingTouch(sb: SeekBar) {}

                override fun onStopTrackingTouch(sb: SeekBar) {
                    // Touch drag ends: speak immediately rather than waiting
                    // out the debounce.
                    previewRunnable?.let { binding.seekRate.removeCallbacks(it) }
                    speak(SAMPLE)
                }
            })
    }

    /**
     * Saves the rate, applies it to this screen's TTS and to any running
     * journey, then previews it — debounced, so dragging or swiping through
     * several steps speaks once at the end instead of on every increment.
     */
    private fun applyRate(rate: Float) {
        settings.speechRate = rate
        tts?.setSpeechRate(rate)
        notifyServiceSettingsChanged(engineChanged = false)

        val sb = _binding?.seekRate ?: return
        previewRunnable?.let { sb.removeCallbacks(it) }
        val r = Runnable { speak(SAMPLE) }
        previewRunnable = r
        sb.postDelayed(r, PREVIEW_DELAY_MS)
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

    // ── Direction of travel ───────────────────────────────────────────────

    private fun setUpAutoDirection() {
        binding.cbAutoDirection.isChecked = settings.autoDirection
        binding.cbAutoDirection.setOnCheckedChangeListener { _, checked ->
            settings.autoDirection = checked
            // Applies to the next journey; an active one keeps whatever
            // direction it already settled on.
            FileLogger.i(TAG, "Auto direction set to $checked")
        }
    }

    // ── Approach announcements ────────────────────────────────────────────

    /**
     * Radio buttons rather than a spinner or switch: three named states, all
     * visible at once, and a screen reader announces each with its checked
     * state without opening anything.
     */
    private fun setUpApproachMode() {
        val checked = when (settings.approachMode) {
            AppSettings.APPROACH_ALL -> binding.rbApproachAll.id
            AppSettings.APPROACH_OFF -> binding.rbApproachOff.id
            else                     -> binding.rbApproachSparse.id
        }
        binding.rgApproach.check(checked)

        binding.rgApproach.setOnCheckedChangeListener { _, id ->
            settings.approachMode = when (id) {
                binding.rbApproachAll.id -> AppSettings.APPROACH_ALL
                binding.rbApproachOff.id -> AppSettings.APPROACH_OFF
                else                     -> AppSettings.APPROACH_SPARSE
            }
            // Takes effect on the next stop, including during a journey: the
            // service reads the setting each time it evaluates a stop.
            FileLogger.i(TAG, "Approach mode set to ${settings.approachMode}")
        }
    }

    // ── Battery optimisation ──────────────────────────────────────────────

    /**
     * Reflects whether the system currently exempts us from battery
     * optimisation. Re-read in onResume, since the user changes it in a
     * system screen and returns.
     */
    private fun showBatteryStatus() {
        val exempt = isIgnoringBatteryOptimisations()
        binding.tvBatteryStatus.text = if (exempt)
            "Ограниченията са изключени"
        else
            "Ограниченията са включени"
        binding.btnBatterySettings.text = if (exempt)
            "Отвори системните настройки"
        else
            "Изключи ограниченията"
    }

    private fun isIgnoringBatteryOptimisations(): Boolean = try {
        val pm = requireContext().getSystemService(android.content.Context.POWER_SERVICE)
                as android.os.PowerManager
        pm.isIgnoringBatteryOptimizations(requireContext().packageName)
    } catch (e: Exception) {
        FileLogger.w(TAG, "Battery optimisation state unknown: ${e.message}")
        false
    }

    /**
     * Asks the system to exempt the app. When already exempt — or when the
     * direct request is unavailable — falls back to opening the battery
     * optimisation list, since manufacturers layer their own restrictions
     * ("sleeping apps" and the like) that no API can switch off.
     */
    private fun openBatterySettings() {
        val ctx = requireContext()
        if (!isIgnoringBatteryOptimisations()) {
            try {
                @Suppress("BatteryLife")
                val intent = android.content.Intent(
                    android.provider.Settings
                        .ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:${ctx.packageName}")
                )
                startActivity(intent)
                return
            } catch (e: Exception) {
                FileLogger.w(TAG, "Direct exemption request failed: ${e.message}")
            }
        }
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(ctx,
                "Отворете Настройки → Батерия за това приложение",
                Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // The exemption is granted in a system screen, so refresh on return.
        _binding?.let { showBatteryStatus() }
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
        previewRunnable?.let { _binding?.seekRate?.removeCallbacks(it) }
        previewRunnable = null
        tts?.shutdown()
        tts = null
        _binding = null
    }
}
