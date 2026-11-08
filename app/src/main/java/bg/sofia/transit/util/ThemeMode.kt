package bg.sofia.transit.util

import android.app.Activity
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import bg.sofia.transit.R

/**
 * Applies the look chosen in Settings → "Изглед" (AppSettings.themeMode):
 * light or dark by AppCompat's night mode, for the whole app; high contrast
 * by a theme of its own (Theme.SofiaTransit.HighContrast), which each
 * activity sets before it is created, its night mode dark.
 */
object ThemeMode {

    /** AppCompat's night mode for [mode]. */
    fun nightModeFor(mode: Int): Int = when (mode) {
        AppSettings.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
        AppSettings.THEME_DARK, AppSettings.THEME_HIGH_CONTRAST -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    /** The night mode of the chosen look, for the whole app (at start). */
    fun applyNightMode(context: Context) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(AppSettings.themeModeOf(context)))
    }

    /** Before an activity's super.onCreate: the high-contrast theme, if chosen. */
    fun applyTo(activity: Activity) {
        if (AppSettings.themeModeOf(activity) == AppSettings.THEME_HIGH_CONTRAST) {
            activity.setTheme(R.style.Theme_SofiaTransit_HighContrast)
        }
    }

    /**
     * A new look chosen, from [old]: the night mode set — AppCompat
     * recreates the activities if it changes how they look — and, if high
     * contrast is switched on or off, [activity] recreated, since a night
     * mode alone does not change its theme (dark and high contrast are both
     * dark).
     */
    fun change(activity: Activity, old: Int, new: Int) {
        AppCompatDelegate.setDefaultNightMode(nightModeFor(new))
        val hcBefore = old == AppSettings.THEME_HIGH_CONTRAST
        val hcNow = new == AppSettings.THEME_HIGH_CONTRAST
        if (hcBefore != hcNow) activity.recreate()
    }
}
