// "Back" as the activity sees it. The activity's own handlers are asked
// first, last added first; then the navigation's (as NavController's: on only
// while more than one screen is on its stack — added before the activity's,
// at every start); then the system's.
package androidx.activity

abstract class OnBackPressedCallback(enabled: Boolean) {
    var isEnabled = enabled
    abstract fun handleOnBackPressed()
}

class OnBackPressedDispatcher(private val system: () -> Unit) {
    private val callbacks = mutableListOf<OnBackPressedCallback>()
    fun addCallback(owner: Any, cb: OnBackPressedCallback) { callbacks += cb }
    fun onBackPressed() {
        callbacks.lastOrNull { it.isEnabled }?.let { it.handleOnBackPressed(); return }
        val nav = androidx.navigation.fragment.NavHostFragment.last
        if (nav != null && nav.stack.size > 1) { nav.popBackStack(); return }
        system()
    }
}
