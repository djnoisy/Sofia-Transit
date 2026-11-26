package bg.sofia.transit.ui.common

import android.graphics.Typeface
import android.widget.TextView
import androidx.core.view.children
import com.google.android.material.tabs.TabLayout

/**
 * The tabs of a stop's window and of Спирки (the owner, 26 Nov 2026): the
 * selected one's name bold — told apart by more than its colour and its
 * background (bg_stop_tab, bg_stops_tab). TabLayout has no such setting of
 * its own here; its tab's text is the TextView among the tab's views.
 */
object Tabs {

    /** From now on, and for the tab selected already. */
    fun boldSelected(tabs: TabLayout) {
        for (i in 0 until tabs.tabCount) tabs.getTabAt(i)?.let { style(it, it.isSelected) }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = style(tab, true)
            override fun onTabUnselected(tab: TabLayout.Tab) = style(tab, false)
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun style(tab: TabLayout.Tab, selected: Boolean) {
        tab.view.children.filterIsInstance<TextView>().forEach {
            it.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}
