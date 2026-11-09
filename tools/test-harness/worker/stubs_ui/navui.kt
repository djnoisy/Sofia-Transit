package androidx.navigation.ui
import android.view.MenuItem
import androidx.navigation.NavController
import androidx.navigation.NavOptions
import com.google.android.material.bottomnavigation.BottomNavigationView

class AppBarConfiguration(val topLevelDestinations: Set<Int>)

/** As the library: a section over the start one, which stays beneath, saved and restored. */
object NavigationUI {
    fun onNavDestinationSelected(item: MenuItem, c: NavController): Boolean {
        c.navigate(item.itemId, null, NavOptions.Builder().setLaunchSingleTop(true).setRestoreState(true)
            .setPopUpTo(bg.sofia.transit.R.id.nearbyFragment, inclusive = false, saveState = true).build())
        return c.currentDestination?.id == item.itemId
    }
}

/** As the library: the bar marks the section whose first screen is shown. */
fun BottomNavigationView.setupWithNavController(c: NavController) {
    val sections = setOf(bg.sofia.transit.R.id.nearbyFragment, bg.sofia.transit.R.id.linesFragment,
        bg.sofia.transit.R.id.journeyFragment)
    check(bg.sofia.transit.R.id.nearbyFragment)
    c.addOnDestinationChangedListener { _, d, _ -> if (d.id in sections) check(d.id) }
}
fun androidx.appcompat.app.AppCompatActivity.setupActionBarWithNavController(
    c: NavController, cfg: AppBarConfiguration) {}
fun NavController.navigateUp(cfg: AppBarConfiguration): Boolean = false
