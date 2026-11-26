package bg.sofia.transit.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView

/**
 * The keyboard of a search field over a list — Спирки → "Търсене" and
 * "Линии" (the owner, 26 Nov 2026) — as "Пътуване" has it (JourneyFragment).
 */
object Keyboard {

    /**
     * The keyboard lies over the window (the owner, 9 Nov 2026): while it is
     * open, room under the last row of [list] as tall as the part of the
     * list it covers, so the last rows scroll up above it. Nothing moves,
     * and the room goes with the keyboard. Worked out again when the list
     * is laid out anew, and with [again] when it is shown again — the
     * keyboard may have gone meanwhile, unseen by it (review of 26 Nov 2026).
     */
    fun keepClear(list: RecyclerView) {
        val padBottom = list.paddingBottom
        var keyboard = 0
        fun room(v: View) {
            val at = IntArray(2).also { v.getLocationInWindow(it) }
            val below = v.rootView.height - (at[1] + v.height)   // the bars under the list
            v.updatePadding(bottom = padBottom + (keyboard - below).coerceAtLeast(0))
        }
        ViewCompat.setOnApplyWindowInsetsListener(list) { v, insets ->
            keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            room(v)
            insets
        }
        list.addOnLayoutChangeListener { v, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (top != oldTop || bottom != oldBottom) room(v)
        }
    }

    /** The room of keepClear worked out again: the list shown again. */
    fun again(list: RecyclerView) = ViewCompat.requestApplyInsets(list)

    /**
     * The keyboard put away and [field] left — by the window's token, not
     * the field's: a page of a ViewPager2 is paused only once it has
     * scrolled away, detached, its field then without one (review of
     * 26 Nov 2026).
     */
    fun hide(field: EditText) {
        val token = activityOf(field.context)?.window?.decorView?.windowToken ?: field.windowToken
        field.context.getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(token, 0)
        field.clearFocus()
    }

    /** The activity behind [context] — a fragment's is wrapped (Hilt). */
    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }
}
