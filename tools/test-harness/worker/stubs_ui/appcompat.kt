package androidx.appcompat.app
import android.content.Context
import android.view.View

object Dialogs { val all = mutableListOf<AlertDialog>() }
class AlertDialog private constructor(val title: CharSequence?, initialMessage: CharSequence?, val cancelable: Boolean,
                                      val positive: CharSequence?, val onPositive: ((Any?, Int) -> Unit)?,
                                      val onCancel: ((Any?) -> Unit)?, val hasView: Boolean) {
    var shownTitle = title
    var shownMessage: CharSequence? = initialMessage
    var isShowing = true; private set
    val window: android.view.Window? = android.view.Window()
    fun dismiss() { isShowing = false }
    fun setTitle(t: CharSequence) { shownTitle = t }
    fun setMessage(m: CharSequence) { shownMessage = m }
    val message get() = shownMessage
    /** Test helpers */
    fun clickPositive() { isShowing = false; onPositive?.invoke(null, -1) }
    fun pressBack() { if (cancelable) { isShowing = false; onCancel?.invoke(null) } }
    override fun toString() = "Dialog[$shownTitle | $shownMessage | btn=$positive | cancelable=$cancelable | showing=$isShowing]"
    class Builder(val c: Context) {
        var t: CharSequence? = null; var m: CharSequence? = null; var canc = true
        var pos: CharSequence? = null; var onPos: ((Any?, Int) -> Unit)? = null; var onC: ((Any?) -> Unit)? = null; var v = false
        fun setTitle(x: CharSequence) = apply { t = x }
        fun setMessage(x: CharSequence) = apply { m = x }
        fun setView(x: View) = apply { v = true }
        fun setCancelable(x: Boolean) = apply { canc = x }
        fun setPositiveButton(x: CharSequence, l: (Any?, Int) -> Unit) = apply { pos = x; onPos = l }
        fun setOnCancelListener(l: (Any?) -> Unit) = apply { onC = l }
        fun show(): AlertDialog = AlertDialog(t, m, canc, pos, onPos, onC, v).also { Dialogs.all += it }
    }
}

object TestEnv { lateinit var files: java.io.File; lateinit var assets: java.io.File }
class DisplayMetrics { val density = 2f }
class Resources { val displayMetrics = DisplayMetrics() }
class FragmentManager { fun findFragmentById(id: Int): Any = androidx.navigation.fragment.NavHostFragment() }

open class AppCompatActivity : Context(TestEnv.files, TestEnv.assets), androidx.lifecycle.LifecycleOwner {
    val layoutInflater = android.view.LayoutInflater()
    val supportFragmentManager = FragmentManager()
    val resources = Resources()
    fun setContentView(v: View) {}
    open fun onCreate(savedInstanceState: android.os.Bundle?) {}
    open fun onDestroy() { androidx.lifecycle.Registry.destroyed(this) }
}
