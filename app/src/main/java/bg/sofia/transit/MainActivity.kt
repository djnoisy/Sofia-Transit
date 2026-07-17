package bg.sofia.transit

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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

        // First-run DB initialisation.
        //
        // The import itself runs in the repository's own application-scoped
        // coroutine, NOT here. This Activity only *observes* it. Previously
        // the Activity awaited the import inline and hid the overlay on the
        // next line — so if the import never returned (cancelled by a
        // competing worker import; a launch{} swallows CancellationException
        // silently, without crashing) the overlay stayed up forever while the
        // fragments behind it happily filled with data.
        gtfsRepo.startInitialLoadIfNeeded()
        observeInitialLoad()
    }

    private fun observeInitialLoad() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Overlay visibility is derived purely from state.
                // initialLoadDone never flips back to false, so the weekly
                // refresh cannot make the overlay reappear mid-use.
                launch {
                    gtfsRepo.initialLoadDone.collect { done ->
                        if (done) {
                            if (binding.layoutLoading.visibility == View.VISIBLE) {
                                binding.layoutLoading.visibility = View.GONE
                                binding.root.announceForAccessibility(
                                    "Данните са заредени. Приложението е готово."
                                )
                            }
                            // Only now — with the DB confirmed populated and
                            // no import in flight — may we consider refreshing
                            // from the network. This is the single trigger for
                            // a background refresh in the whole app: no app
                            // launch, no refresh. Wi-Fi only; it does nothing
                            // if the data is under 7 days old.
                            bg.sofia.transit.worker.GtfsUpdateWorker.scheduleIfStale(this@MainActivity)
                        } else {
                            binding.layoutLoading.visibility = View.VISIBLE
                            binding.tvLoadingMsg.text = "Зареждане на данни за пръв път…"
                            binding.tvLoadingMsg.contentDescription = binding.tvLoadingMsg.text
                        }
                    }
                }

                // Step-by-step progress of whichever import is running.
                launch {
                    gtfsRepo.importProgress.collect { msg ->
                        if (msg != null && !gtfsRepo.initialLoadDone.value) {
                            binding.tvLoadingMsg.text = msg
                            binding.tvLoadingMsg.contentDescription = msg
                        }
                    }
                }

                // A failed first-run import must not strand the user on a
                // spinner that will never stop.
                launch {
                    gtfsRepo.importError.collect { err ->
                        if (err != null && !gtfsRepo.initialLoadDone.value) {
                            val text = "Данните не можаха да се заредят. " +
                                       "Докоснете двукратно, за да опитате отново."
                            binding.tvLoadingMsg.text = text
                            binding.tvLoadingMsg.contentDescription = text
                            binding.layoutLoading.isClickable = true
                            binding.layoutLoading.setOnClickListener {
                                binding.layoutLoading.setOnClickListener(null)
                                binding.layoutLoading.isClickable = false
                                binding.tvLoadingMsg.text = "Зареждане на данни за пръв път…"
                                gtfsRepo.retryInitialLoad()
                            }
                        }
                    }
                }
            }
        }
    }
}
