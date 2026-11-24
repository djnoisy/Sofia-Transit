package bg.sofia.transit.ui.common

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import androidx.core.view.updatePadding

/**
 * A window of its own over the main one — "Виртуално табло"
 * (StopBoardActivity), and a line's route or timetable opened from it
 * (LineWindowActivity): no navigation bar; in the top bar the back arrow and
 * the title, the window's heading for TalkBack, as in the main screens
 * (MainActivity).
 */
object SubWindow {

    fun setUp(activity: AppCompatActivity, toolbar: Toolbar, content: View, title: String) {
        activity.setSupportActionBar(toolbar)
        activity.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        activity.title = title
        toolbar.post { markTitleAsHeading(toolbar) }

        // No navigation bar below: the content keeps clear of the system's
        // (edge to edge on Android 15).
        ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            v.updatePadding(bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom)
            insets
        }
    }

    /** The bar's title view, by its text: the toolbar does not expose it. */
    private fun markTitleAsHeading(toolbar: Toolbar) {
        val title = toolbar.title?.toString() ?: return
        toolbar.children.filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == title }
            ?.let { ViewCompat.setAccessibilityHeading(it, true) }
    }
}
