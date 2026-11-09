package bg.sofia.transit.databinding
import android.view.View
import android.widget.TextView
class ActivityMainBinding {
    val root = View(); val layoutLoading = View(); val tvLoadingMsg = TextView()
    val appBar = View(); val toolbar = com.google.android.material.bottomnavigation.MaterialToolbar()
    val navHostFragment = View()
    val bottomNav = com.google.android.material.bottomnavigation.BottomNavigationView()
    companion object { fun inflate(i: android.view.LayoutInflater) = ActivityMainBinding().also { last = it }; var last: ActivityMainBinding? = null }
}
