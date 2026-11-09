package androidx.navigation.fragment
class NavHostFragment {
    val navController = androidx.navigation.NavController(bg.sofia.transit.R.id.nearbyFragment).also {
        it.actions[bg.sofia.transit.R.id.actionGlobalSettings] = bg.sofia.transit.R.id.settingsFragment
        it.actions[bg.sofia.transit.R.id.actionGlobalDiagnostics] = bg.sofia.transit.R.id.diagnosticsFragment
        last = it
    }
    companion object { var last: androidx.navigation.NavController? = null }
}
