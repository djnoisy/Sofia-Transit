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

        /** Sentinel meaning "whatever the system default is". */
        const val ENGINE_SYSTEM_DEFAULT = ""
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

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
