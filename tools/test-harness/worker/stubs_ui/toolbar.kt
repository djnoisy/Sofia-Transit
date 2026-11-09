// The top app bar and the navigation bar, as MainActivity uses them.
package com.google.android.material.bottomnavigation
import android.view.MenuItem

class BottomNavigationView : android.view.View() {
    /** The section marked. Set, as the real one: as if tapped (listeners and all). */
    var selectedItemId: Int
        get() = checked
        set(id) = tap(id)
    private var checked = 0
    /** As the navigation marks a section: no listener. */
    fun check(id: Int) { checked = id }
    private var onSelected: ((MenuItem) -> Boolean)? = null
    private var onReselected: ((MenuItem) -> Unit)? = null
    fun setOnItemSelectedListener(l: (MenuItem) -> Boolean) { onSelected = l }
    fun setOnItemReselectedListener(l: (MenuItem) -> Unit) { onReselected = l }
    /** Test helper: a tap on a section, as the real view handles it. */
    fun tap(id: Int) {
        if (id == checked) onReselected?.invoke(MenuItem(id))
        else if (onSelected?.invoke(MenuItem(id)) == true) checked = id
    }
}

class MaterialToolbar : android.view.ViewGroup() { var title: CharSequence? = null }
