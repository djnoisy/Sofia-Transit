package bg.sofia.transit

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController

    @Inject lateinit var gtfsRepo: GtfsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        binding.bottomNav.setupWithNavController(navController)

        // Accessibility: describe the bottom navigation for TalkBack
        binding.bottomNav.contentDescription =
            "Главно меню: Спирки, Линии, Пътуване"

        // First-run DB initialisation
        initDatabaseIfNeeded()
    }

    private fun initDatabaseIfNeeded() {
        lifecycleScope.launch {
            if (!gtfsRepo.isDatabaseReady()) {
                binding.layoutLoading.visibility = View.VISIBLE
                binding.tvLoadingMsg.text = "Зареждане на данни за пръв път…"
                binding.tvLoadingMsg.contentDescription = binding.tvLoadingMsg.text

                gtfsRepo.initialiseFromAssets { msg ->
                    runOnUiThread { binding.tvLoadingMsg.text = msg }
                }

                binding.layoutLoading.visibility = View.GONE
                // Announce readiness to TalkBack
                binding.root.announceForAccessibility("Данните са заредени. Приложението е готово.")
            }
        }
    }
}
