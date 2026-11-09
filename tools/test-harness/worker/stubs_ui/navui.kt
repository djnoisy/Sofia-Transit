package androidx.navigation.ui
import androidx.navigation.NavController
import com.google.android.material.bottomnavigation.BottomNavigationView

object R { object animator {
    const val nav_default_enter_anim = 1; const val nav_default_exit_anim = 2
    const val nav_default_pop_enter_anim = 3; const val nav_default_pop_exit_anim = 4
} }

class AppBarConfiguration(val topLevelDestinations: Set<Int>)

/** As the library: the bar marks the section whose first screen is shown. */
fun BottomNavigationView.setupWithNavController(c: NavController) {
    val sections = setOf(bg.sofia.transit.R.id.nearbyFragment, bg.sofia.transit.R.id.linesFragment,
        bg.sofia.transit.R.id.journeyFragment)
    selectedItemId = bg.sofia.transit.R.id.nearbyFragment
    c.addOnDestinationChangedListener { _, d, _ -> if (d.id in sections) selectedItemId = d.id }
}
fun androidx.appcompat.app.AppCompatActivity.setupActionBarWithNavController(
    c: NavController, cfg: AppBarConfiguration) {}
fun NavController.navigateUp(cfg: AppBarConfiguration): Boolean = false
