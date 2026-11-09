// The top app bar and the navigation bar, as MainActivity uses them.
package com.google.android.material.bottomnavigation
import android.view.MenuItem

class BottomNavigationView : android.view.View() {
    var selectedItemId = 0
    private var onSelected: ((MenuItem) -> Boolean)? = null
    private var onReselected: ((MenuItem) -> Unit)? = null
    fun setOnItemSelectedListener(l: (MenuItem) -> Boolean) { onSelected = l }
    fun setOnItemReselectedListener(l: (MenuItem) -> Unit) { onReselected = l }
    /** Test helper: a tap on a section, as the real view handles it. */
    fun tap(id: Int) {
        if (id == selectedItemId) onReselected?.invoke(MenuItem(id))
        else if (onSelected?.invoke(MenuItem(id)) == true) selectedItemId = id
    }
}

class MaterialToolbar : android.view.ViewGroup() { var title: CharSequence? = null }
