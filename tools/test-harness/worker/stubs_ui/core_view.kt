package androidx.core.view
import android.view.View
import android.view.ViewGroup
object ViewCompat {
    /** Inset listeners set, by view, for the tests to call. */
    val insetListeners = mutableMapOf<View, (View, WindowInsetsCompat) -> WindowInsetsCompat>()
    fun setAccessibilityHeading(v: View, heading: Boolean) {}
    fun setOnApplyWindowInsetsListener(v: View, l: ((View, WindowInsetsCompat) -> WindowInsetsCompat)?) {
        if (l == null) insetListeners.remove(v) else insetListeners[v] = l
    }
}
class Insets(val left: Int = 0, val top: Int = 0, val right: Int = 0, val bottom: Int = 0)
/** Only the system's navigation bar, as the tests give it. */
class WindowInsetsCompat(private val navigationBar: Insets = Insets()) {
    object Type { fun navigationBars() = 1; fun ime() = 2 }
    fun getInsets(type: Int) = if (type == Type.navigationBars()) navigationBar else Insets()
}
val ViewGroup.children: Sequence<View> get() = childViews.asSequence()
inline fun <reified T : ViewGroup.LayoutParams> View.updateLayoutParams(block: T.() -> Unit) {
    block(layoutParams as T)
}
