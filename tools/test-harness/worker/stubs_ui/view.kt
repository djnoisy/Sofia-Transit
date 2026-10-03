package android.view
object A11y { val spoken = mutableListOf<String>() }
open class View {
    companion object { const val VISIBLE = 0; const val GONE = 8 }
    var visibility = GONE
    var contentDescription: CharSequence? = null
    var isClickable = false
    fun announceForAccessibility(t: CharSequence) { A11y.spoken += t.toString() }
    fun setOnClickListener(l: ((View) -> Unit)?) {}
    fun setPadding(a: Int, b: Int, c: Int, d: Int) {}
}
class LayoutInflater
class Window { val decorView = View() }
