package bg.sofia.transit.util

import android.content.Context
import android.speech.tts.TextToSpeech
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistent user settings.
 *
 * Kept deliberately small and free of Android UI types so it can be injected
 * into the service as easily as into a fragment.
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val PREFS = "app_settings"
        private const val KEY_TTS_ENGINE = "tts_engine"
        private const val KEY_SPEECH_RATE = "speech_rate"

        /** Sentinel meaning "whatever the system default is". */
        const val ENGINE_SYSTEM_DEFAULT = ""

        const val RATE_MIN = 0.1f
        const val RATE_MAX = 2.0f
        const val RATE_STEP = 0.1f
        const val RATE_DEFAULT = 1.0f

        private const val KEY_APPROACH_MODE = "approach_mode"
        private const val KEY_AUTO_DIRECTION = "auto_direction"

        /** Warn only where stops are far enough apart for it to be useful. */
        const val APPROACH_SPARSE = 0
        /** Never warn; the "Следваща спирка" announcement stands alone. */
        const val APPROACH_OFF = 1
        /** Warn before every stop, however close together they are. */
        const val APPROACH_ALL = 2
    }

    /**
     * When the "Наближава спирка" warning is spoken.
     *
     * Defaults to [APPROACH_SPARSE]: on line 76 half the gaps are under 400 m,
     * where the warning would follow the previous stop by seconds and add
     * noise rather than notice. Riders who want it everywhere — or nowhere —
     * can say so.
     */
    var approachMode: Int
        get() = prefs.getInt(KEY_APPROACH_MODE, APPROACH_SPARSE)
        set(value) { prefs.edit().putInt(KEY_APPROACH_MODE, value).apply() }

    /**
     * Determine the direction of travel automatically instead of asking.
     *
     * Off by default: picking a direction takes one tap for anyone who knows
     * the line, whereas the automatic mode has to wait for the vehicle to
     * move before it can announce anything. It exists for riders who cannot
     * tell which terminus they are heading for — the destination names mean
     * nothing if you don't know the city's layout.
     */
    var autoDirection: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DIRECTION, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_DIRECTION, value).apply() }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Speaking rate for journey announcements, 1.0 being the engine's normal
     * speed. Stored separately from the system-wide TTS rate on purpose:
     * screen-reader users often run their reader very fast, but want stop
     * announcements at a calmer pace (or the reverse).
     */
    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, RATE_DEFAULT)
            .coerceIn(RATE_MIN, RATE_MAX)
        set(value) {
            prefs.edit().putFloat(KEY_SPEECH_RATE, value.coerceIn(RATE_MIN, RATE_MAX)).apply()
        }

    /**
     * Package name of the speech engine journey announcements should use, or
     * [ENGINE_SYSTEM_DEFAULT] to follow Settings → "Синтезиран говор".
     *
     * Choosing an engine DIFFERENT from the one TalkBack uses is what allows
     * announcements and screen-reader speech to play at the same time: a
     * single engine serialises its output and one client cuts off the other.
     */
    var ttsEngine: String
        get() = prefs.getString(KEY_TTS_ENGINE, ENGINE_SYSTEM_DEFAULT)
            ?: ENGINE_SYSTEM_DEFAULT
        set(value) {
            prefs.edit().putString(KEY_TTS_ENGINE, value).apply()
        }

    /** One installed speech engine, for the picker. */
    data class EngineInfo(val packageName: String, val label: String)

    /**
     * All speech engines installed on the device. Requires the
     * <queries> TTS_SERVICE declaration in the manifest, without which
     * Android 11+ hides every engine but the default one.
     */
    fun availableEngines(tts: TextToSpeech): List<EngineInfo> =
        try {
            tts.engines.map { EngineInfo(it.name, it.label) }
                .sortedBy { it.label.lowercase() }
        } catch (e: Exception) {
            FileLogger.w("AppSettings", "Could not list TTS engines: ${e.message}")
            emptyList()
        }
}
