package bg.sofia.transit.databinding
import android.view.View
import android.widget.TextView
class ActivityMainBinding {
    val root = View(); val bottomNav = View(); val layoutLoading = View(); val tvLoadingMsg = TextView()
    companion object { fun inflate(i: android.view.LayoutInflater) = ActivityMainBinding().also { last = it }; var last: ActivityMainBinding? = null }
}
