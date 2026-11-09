package android.view
object A11y { val spoken = mutableListOf<String>() }
open class View {
    companion object {
        const val VISIBLE = 0; const val GONE = 8
        const val IMPORTANT_FOR_ACCESSIBILITY_AUTO = 0
        const val IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS = 4
    }
    var visibility = GONE
    var contentDescription: CharSequence? = null
    var isClickable = false
    var importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    fun announceForAccessibility(t: CharSequence) { A11y.spoken += t.toString() }
    fun setOnClickListener(l: ((View) -> Unit)?) {}
    fun setPadding(a: Int, b: Int, c: Int, d: Int) {}
    fun post(r: () -> Unit): Boolean { r(); return true }
}
open class ViewGroup : View() { val childViews = mutableListOf<View>() }
class LayoutInflater
class Window { val decorView = View() }
