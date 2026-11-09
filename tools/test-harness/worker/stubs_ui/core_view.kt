package androidx.core.view
import android.view.View
import android.view.ViewGroup
object ViewCompat { fun setAccessibilityHeading(v: View, heading: Boolean) {} }
val ViewGroup.children: Sequence<View> get() = childViews.asSequence()
