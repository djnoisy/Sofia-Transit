// A small model of the navigation library's back stack: enough to see which
// screens MainActivity keeps and which it pops, saves and restores.
package androidx.navigation

class NavDestination(val id: Int) {
    companion object {
        val NavDestination.hierarchy: Sequence<NavDestination> get() = sequenceOf(this)
    }
}

class NavOptions(val singleTop: Boolean, val restoreState: Boolean, val popUpToId: Int,
                 val popUpToInclusive: Boolean, val popUpToSaveState: Boolean) {
    class Builder {
        private var top = false; private var restore = false; private var pop = -1
        private var incl = false; private var save = false
        fun setLaunchSingleTop(b: Boolean) = apply { top = b }
        fun setRestoreState(b: Boolean) = apply { restore = b }
        fun setPopUpTo(id: Int, inclusive: Boolean, saveState: Boolean = false) =
            apply { pop = id; incl = inclusive; save = saveState }
        fun setEnterAnim(a: Int) = this
        fun setExitAnim(a: Int) = this
        fun setPopEnterAnim(a: Int) = this
        fun setPopExitAnim(a: Int) = this
        fun build() = NavOptions(top, restore, pop, incl, save)
    }
}

class NavBackStackEntry(val id: Int)

class NavController(start: Int) {
    /** The back stack, its first screen first. */
    val stack = mutableListOf(start)
    /** Stacks saved by a pop, by the screen popped up to. */
    val saved = mutableMapOf<Int, List<Int>>()
    private val listeners = mutableListOf<(NavController, NavDestination, android.os.Bundle?) -> Unit>()
    val currentDestination: NavDestination? get() = stack.lastOrNull()?.let { NavDestination(it) }
    fun addOnDestinationChangedListener(l: (NavController, NavDestination, android.os.Bundle?) -> Unit) { listeners += l }
    fun getBackStackEntry(id: Int): NavBackStackEntry =
        if (id in stack) NavBackStackEntry(id) else throw IllegalArgumentException("$id not on the back stack")
    fun popBackStack(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex); changed(); return true
    }
    /** Actions, by the screen each leads to. */
    val actions = mutableMapOf<Int, Int>()
    fun navigate(id: Int) { stack += actions[id] ?: id; changed() }
    fun navigate(id: Int, args: android.os.Bundle?, o: NavOptions?) {
        if (o != null && o.popUpToId != -1) {
            val at = stack.lastIndexOf(o.popUpToId)
            if (at >= 0) {
                val popped = stack.subList(if (o.popUpToInclusive) at else at + 1, stack.size).toList()
                if (o.popUpToSaveState && popped.isNotEmpty()) saved[popped.first()] = popped
                repeat(popped.size) { stack.removeAt(stack.lastIndex) }
            }
        }
        val restored = if (o?.restoreState == true) saved.remove(id) else null
        when {
            restored != null -> stack += restored
            o?.singleTop == true && stack.lastOrNull() == id -> {}
            else -> stack += id
        }
        changed()
    }
    private fun changed() { currentDestination?.let { d -> listeners.forEach { it(this, d, null) } } }
}
