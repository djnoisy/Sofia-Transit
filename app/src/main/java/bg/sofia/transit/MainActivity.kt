package bg.sofia.transit

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.core.view.ViewCompat
import androidx.core.view.children
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.work.WorkInfo
import androidx.work.WorkManager
import bg.sofia.transit.data.repository.GtfsRepository
import bg.sofia.transit.databinding.ActivityMainBinding
import bg.sofia.transit.service.JourneyService
import bg.sofia.transit.ui.permissions.PermissionsActivity
import bg.sofia.transit.util.Permissions
import bg.sofia.transit.util.ThemeMode
import bg.sofia.transit.worker.GtfsUpdateWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var navController: NavController
    /** The three sections of the navigation bar: no "back" arrow on them. */
    private val appBarConfig = AppBarConfiguration(
        setOf(R.id.nearbyFragment, R.id.linesFragment, R.id.journeyFragment))

    @Inject lateinit var gtfsRepo: GtfsRepository

    private companion object {
        const val UPDATE_TITLE  = "Актуализиране на данните"
        const val INSTALL_TITLE = "Инсталиране на данните"
    }

    /** Shown while a data update is downloading / importing. */
    private var updateProgressDialog: AlertDialog? = null
    private var updateProgressMessage: String? = null
    /** Shown when an update the user saw has finished; has a close button. */
    private var updateResultDialog: AlertDialog? = null
    /** Run whose result [updateResultDialog] shows. */
    private var updateResultId: java.util.UUID? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // High contrast is a theme of its own, set before the activity is.
        ThemeMode.applyTo(this)
        super.onCreate(savedInstanceState)
        // Opened as from its icon (the tracking notification, a launcher,
        // Play's "Open"…) while the app is already open: the system brings
        // its task to the front, but adds a second copy on top when the
        // intent is not exactly the one that started it — the same app over
        // itself, with sections and a "back" of its own (the owner, 9 Nov
        // 2026). The copy goes, and the app is shown as it was left.
        val launch: Intent? = intent
        if (!isTaskRoot && launch?.action == Intent.ACTION_MAIN &&
            launch.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            finish()
            return
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Navigation
        val navHost = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController

        // The top app bar: the screen's title (the destination's label), a
        // "back" arrow below the three sections, "Настройки" and
        // "Диагностика" (onCreateOptionsMenu) — on every screen, not only
        // on "Спирки" as before (the owner, 8 Nov 2026).
        setSupportActionBar(binding.toolbar)
        setupActionBarWithNavController(navController, appBarConfig)
        navController.addOnDestinationChangedListener { _, _, _ ->
            invalidateOptionsMenu()
            // The title is the screen's heading for TalkBack, as the title
            // inside each screen was; set once the bar has laid it out.
            binding.toolbar.post { markTitleAsHeading() }
        }

        binding.bottomNav.setupWithNavController(navController)
        // "Настройки" and "Диагностика" belong to no section: left before a
        // section is chosen, or they would be kept as part of the section
        // left — and come back with it, the section's own screen behind
        // them (review of 8 Nov 2026). The section shown chosen again, from
        // either of them: back to where it was; from its own screens:
        // nothing, as before.
        binding.bottomNav.setOnItemSelectedListener { item ->
            leaveSettingsAndDiagnostics()
            NavigationUI.onNavDestinationSelected(item, navController)
            // Marked whenever the section is now the one shown — also when it
            // comes back on one of its own screens (a line's directions, its
            // stops), which NavigationUI does not count: the bar stayed on
            // the section left, and that one, tapped, did nothing — "it is
            // shown already" (the owner, 10 Nov 2026).
            isOnBackStack(item.itemId)
        }
        binding.bottomNav.setOnItemReselectedListener { leaveSettingsAndDiagnostics() }

        // "Back" on the first screen of a section closes the app, from any of
        // the three: they are of equal rank (the owner, 9 Nov 2026). Before,
        // it went first to "Спирки", which NavigationUI keeps beneath every
        // other section — and must keep: the first fragment of the stack is
        // the one fragment whose state is not saved, so popping "Спирки"
        // instead would have lost every section's first screen on each
        // change (review of 9 Nov 2026). Added after the navigation's own
        // handler, so asked first (both again in that order on every start).
        onBackPressedDispatcher.addCallback(this, sectionBack)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            sectionBack.isEnabled = destination.id in appBarConfig.topLevelDestinations
        }

        // Opened anew while a journey is tracked (the app removed from the
        // recent ones, closed by "back" before Android 12…): straight to
        // "Пътуване", not "Спирки" (the owner, 9 Nov 2026). Not when brought
        // back by the system as it was left: then the screen left is shown.
        if (savedInstanceState == null &&
            JourneyService.trackingState.value is JourneyService.TrackingState.Tracking) {
            binding.bottomNav.selectedItemId = R.id.journeyFragment
        }

        // No description of the bar as a whole: TalkBack read it as an element
        // of its own, "Главно меню: …", before the sections — a menu, not a
        // navigation bar as in other apps (the owner, 9 Nov 2026). Each
        // section speaks its title.

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
        observeDataUpdate()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar, menu)
        return true
    }

    /** Not offered on the screen it opens. */
    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // Not for a second copy closed in onCreate.
        if (!::navController.isInitialized) return super.onPrepareOptionsMenu(menu)
        val here = navController.currentDestination?.id
        menu.findItem(R.id.actionSettings)?.isVisible = here != R.id.settingsFragment
        menu.findItem(R.id.actionDiagnostics)?.isVisible = here != R.id.diagnosticsFragment
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        // One over the other, not one on top of the other again and again.
        R.id.actionSettings -> {
            leaveSettingsAndDiagnostics()
            navController.navigate(R.id.actionGlobalSettings); true
        }
        R.id.actionDiagnostics -> {
            leaveSettingsAndDiagnostics()
            navController.navigate(R.id.actionGlobalDiagnostics); true
        }
        else -> super.onOptionsItemSelected(item)
    }

    /**
     * The loading overlay over the whole screen: it takes the taps, and what
     * lies beneath — the bars and the screen — is hidden from TalkBack while
     * it is shown, so "Настройки" cannot be reached through it.
     */
    private fun showLoadingOverlay(show: Boolean) {
        binding.layoutLoading.visibility = if (show) View.VISIBLE else View.GONE
        val beneath = if (show) View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            else View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        listOf<View>(binding.appBar, binding.navHostFragment, binding.bottomNav)
            .forEach { it.importantForAccessibility = beneath }
    }

    /**
     * As the system's own "back" on the app's first screen: since Android 12
     * the app is sent to the background, as left; before, it is closed.
     */
    private val sectionBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !moveTaskToBack(false)) finish()
        }
    }

    private fun isOnBackStack(id: Int): Boolean = try {
        navController.getBackStackEntry(id)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    /** Back out of "Настройки" and "Диагностика", if on one of them. */
    private fun leaveSettingsAndDiagnostics() {
        while (navController.currentDestination?.id.let {
                it == R.id.settingsFragment || it == R.id.diagnosticsFragment }) {
            if (!navController.popBackStack()) break
        }
    }

    override fun onSupportNavigateUp(): Boolean =
        navController.navigateUp(appBarConfig) || super.onSupportNavigateUp()

    /** The bar's title view, by its text: the toolbar does not expose it. */
    private fun markTitleAsHeading() {
        // As text: the bar keeps the title it was given (navigation builds it
        // in a StringBuffer), its view a String copy.
        val title = binding.toolbar.title?.toString() ?: return
        binding.toolbar.children.filterIsInstance<TextView>()
            .firstOrNull { it.text.toString() == title }
            ?.let { ViewCompat.setAccessibilityHeading(it, true) }
    }

    override fun onDestroy() {
        // Rotation etc.: the dialogs are rebuilt from the work's state by
        // the next Activity instance. Dismiss (not cancel) so nothing is
        // acknowledged and no window leaks.
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
        updateResultDialog?.dismiss()
        updateResultDialog = null
        super.onDestroy()
    }

    /**
     * Follows the static-data update run. LiveData delivers only while the
     * Activity is started, and replays the latest state on return — so a
     * run that finished while the app was in the background still reports
     * its result once the user comes back.
     *
     * The dialog appears only for runs that actually download new data. A
     * check that finds the feed unchanged stays silent.
     */
    private fun observeDataUpdate() {
        WorkManager.getInstance(this)
            .getWorkInfosForUniqueWorkLiveData(GtfsUpdateWorker.WORK_NAME)
            .observe(this) { infos ->
                // A running run first (an install can have its follow-up
                // update already queued behind it), then any unfinished one.
                val info = infos?.firstOrNull { it.state == WorkInfo.State.RUNNING }
                    ?: infos?.firstOrNull { !it.state.isFinished } ?: infos?.firstOrNull()
                renderDataUpdate(info)
            }
    }

    private fun renderDataUpdate(info: WorkInfo?) {
        if (info == null) {
            dismissUpdateProgress()
            showPendingResult()
            return
        }
        val install = GtfsUpdateWorker.TAG_INSTALL in info.tags
        if (!info.state.isFinished) {
            // Only a RUNNING run shows progress. A run stopped by the system
            // goes back to ENQUEUED and may still carry its old phase; the
            // dialog must not stay up for it.
            val phase = if (info.state == WorkInfo.State.RUNNING)
                info.progress.getString(GtfsUpdateWorker.KEY_PHASE) else null
            if (install) {
                // First run: there is no data yet, so the dialog stays up for
                // the whole install, from the first moment. One text for all
                // of it — checking, downloading and installing alike.
                showUpdateProgress(info.id, INSTALL_TITLE, "Инсталиране на данните…")
                return
            }
            // A later update: checking and downloading happen silently in
            // the background. The dialog appears only once the new data
            // starts replacing the old — then stops and lines must not be
            // browsed, as they would come back half empty.
            when (phase) {
                GtfsUpdateWorker.PHASE_IMPORT ->
                    showUpdateProgress(info.id, UPDATE_TITLE, "Инсталиране на новите данни…")
                else -> {
                    dismissUpdateProgress()
                    // A silent new check: a result from an earlier run the
                    // user has not seen yet can still be shown meanwhile.
                    showPendingResult()
                }
            }
            return
        }

        dismissUpdateProgress()
        val out = info.outputData
        if (updateResultDialog?.isShowing == true) return
        val installFailed = info.state != WorkInfo.State.SUCCEEDED ||
            out.getString(GtfsUpdateWorker.KEY_RESULT) == GtfsUpdateWorker.RESULT_FAILED
        if (install && installFailed && !gtfsRepo.initialLoadDone.value &&
            !GtfsUpdateWorker.isAcknowledged(this, info.id)) {
            // The install ended (however: failed, crashed, cancelled) and
            // there is still no data — the user must be able to retry.
            showInstallResult(info.id, GtfsUpdateWorker.RESULT_FAILED)
            return
        }
        if (install && installFailed && gtfsRepo.initialLoadDone.value) {
            // A failed install whose data has been loaded since (e.g. it
            // gave up because another import was busy): "Опитай отново"
            // would be stale. Drop it quietly.
            GtfsUpdateWorker.acknowledge(this, info.id)
            showPendingResult()
            return
        }
        if (!out.getBoolean(GtfsUpdateWorker.KEY_VISIBLE, false) ||
            GtfsUpdateWorker.isAcknowledged(this, info.id)) {
            // Nothing to tell about this run — but maybe about an earlier one
            // whose record WorkManager has already replaced.
            showPendingResult()
            return
        }

        val kind = when {
            GtfsUpdateWorker.TAG_REINSTALL in info.tags -> GtfsUpdateWorker.KIND_REINSTALL
            install -> GtfsUpdateWorker.KIND_INSTALL
            else -> GtfsUpdateWorker.KIND_UPDATE
        }
        val reported = out.getString(GtfsUpdateWorker.KEY_RESULT)
        showResult(info.id, kind, when {
            reported == GtfsUpdateWorker.RESULT_FAILED_NO_DATA -> reported
            installFailed -> GtfsUpdateWorker.RESULT_FAILED
            else -> reported
        })
    }

    /** The result the app kept for itself (see GtfsUpdateWorker.pendingResult). */
    private fun showPendingResult() {
        if (updateResultDialog?.isShowing == true) return
        val p = GtfsUpdateWorker.pendingResult(this) ?: return
        if (GtfsUpdateWorker.isAcknowledged(this, p.id)) return
        if (p.kind != GtfsUpdateWorker.KIND_UPDATE &&
            p.result == GtfsUpdateWorker.RESULT_FAILED && gtfsRepo.initialLoadDone.value) {
            // An old install failure, but the data has been loaded since —
            // "Опитай отново" would be stale. Drop it quietly.
            GtfsUpdateWorker.acknowledge(this, p.id)
            return
        }
        showResult(p.id, p.kind, p.result)
    }

    private fun showResult(id: java.util.UUID, kind: String, result: String?) {
        if (result == GtfsUpdateWorker.RESULT_FAILED_NO_DATA) {
            // The update failed and the old data could not be put back
            // either: same as a failed install — offer "Опитай отново".
            showInstallResult(id, GtfsUpdateWorker.RESULT_FAILED)
            return
        }
        if (kind == GtfsUpdateWorker.KIND_REINSTALL && result == GtfsUpdateWorker.RESULT_FAILED) {
            // A reinstall (new app version) that failed after it began
            // replacing the tables: the data is incomplete. Same as a failed
            // first install — offer "Опитай отново".
            showInstallResult(id, GtfsUpdateWorker.RESULT_FAILED)
            return
        }
        if (kind == GtfsUpdateWorker.KIND_INSTALL) {
            showInstallResult(id, result)
            return
        }
        val text = when (result) {
            // An unfinished install completed from the local files: which
            // data it is is not known — say only that it is installed.
            GtfsUpdateWorker.RESULT_REPAIRED ->
                "Данните са инсталирани."
            // An update, or a reinstall after a new app version brought
            // newer bundled data — to the user both are the same thing.
            GtfsUpdateWorker.RESULT_UPDATED,
            GtfsUpdateWorker.RESULT_INSTALLED_NEW,
            GtfsUpdateWorker.RESULT_INSTALLED_CURRENT,
            GtfsUpdateWorker.RESULT_INSTALLED_FALLBACK ->
                "Данните за градския транспорт са актуализирани."
            else ->
                "Актуализирането на данните не бе успешно. Приложението " +
                "продължава с досегашните данни и ще опита отново по-късно."
        }
        updateResultId = id
        updateResultDialog = AlertDialog.Builder(this)
            .setTitle(UPDATE_TITLE)
            .setMessage(text)
            .setPositiveButton("Затвори") { _, _ -> resultClosed(id, result) }
            .setOnCancelListener { resultClosed(id, result) }
            .show()
    }

    /**
     * The user closed the message about a finished install/update. Only now
     * may the next one start (checkForUpdate waits for this), so that data
     * never changes behind a message that is still on screen. After an
     * install that fell back to the local data, look for newer data at once.
     */
    private fun resultClosed(id: java.util.UUID, result: String?) {
        GtfsUpdateWorker.acknowledge(this, id)
        updateResultId = null
        updateResultDialog = null
        maybeShowPermissions()
        // A reinstall due after an app upgrade waited for this too.
        gtfsRepo.startInitialLoadIfNeeded()
        if (result == GtfsUpdateWorker.RESULT_INSTALLED_FALLBACK) {
            GtfsUpdateWorker.updateAfterFallbackInstall(this)
        } else {
            GtfsUpdateWorker.checkForUpdate(this)
        }
    }

    private fun showInstallResult(id: java.util.UUID, result: String?) {
        val failed = result == GtfsUpdateWorker.RESULT_FAILED
        // Whichever data was installed (downloaded or bundled), the user
        // is told only that it is done; the log keeps the details.
        val text = if (failed) "Данните не можаха да се инсталират."
                   else "Данните са инсталирани."
        val b = AlertDialog.Builder(this)
            .setTitle(INSTALL_TITLE)
            .setMessage(text)
        if (failed) {
            // Without data the app cannot work: the only way on is to retry.
            b.setCancelable(false)
             .setPositiveButton("Опитай отново") { _, _ ->
                GtfsUpdateWorker.acknowledge(this, id)
                gtfsRepo.retryInitialLoad()
            }
        } else {
            b.setPositiveButton("Затвори") { _, _ -> resultClosed(id, result) }
            .setOnCancelListener { resultClosed(id, result) }
        }
        updateResultId = id
        updateResultDialog = b.show()
    }

    private fun showUpdateProgress(runId: java.util.UUID, title: String, message: String) {
        // A late progress report of a run that has already finished (its
        // result is kept or on screen): nothing is in progress any more.
        if (GtfsUpdateWorker.pendingResult(this)?.id == runId ||
            (updateResultId == runId && updateResultDialog?.isShowing == true)) {
            dismissUpdateProgress()
            showPendingResult()
            return
        }
        // A new install is starting: a result from an earlier run the user
        // has not closed yet is outdated — the new run ends with its own
        // message. Discard it for good rather than show it later.
        // (Never the result of this very run: a late progress report of a run
        // that has just finished must not swallow its own message.)
        if (updateResultId != null && updateResultId != runId) {
            updateResultDialog?.dismiss()
            updateResultDialog = null
            GtfsUpdateWorker.acknowledge(this, updateResultId!!)
            updateResultId = null
        }
        GtfsUpdateWorker.pendingResult(this)?.let {
            if (it.id != runId) GtfsUpdateWorker.acknowledge(this, it.id)
        }
        val existing = updateProgressDialog
        if (existing != null && existing.isShowing) {
            // Already up: the work reports each new phase, but the text is
            // the same — do not make TalkBack repeat it.
            if (message != updateProgressMessage) {
                existing.setTitle(title)
                existing.setMessage(message)
                existing.window?.decorView?.announceForAccessibility(message)
                updateProgressMessage = message
            }
            return
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val spinner = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        // Deliberately no button and not cancelable (Back, touch outside):
        // the app is not to be used while the data is being replaced. Leaving
        // the app (Home) only stops the Activity — the update runs in
        // WorkManager, independent of any screen, and carries on. Coming back
        // shows this dialog again while it runs, or the result once done.
        updateProgressDialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(spinner)
            .setCancelable(false)
            .show()
        updateProgressMessage = message
    }

    private fun dismissUpdateProgress() {
        updateProgressDialog?.dismiss()
        updateProgressDialog = null
        updateProgressMessage = null
    }

    /**
     * The first-run permissions screen, once the data is in. Never on top of
     * an install or update window: during the first install it comes when
     * the user closes "Данните са инсталирани." ([resultClosed]). Shown once
     * — not again after "Не сега", and not when everything is already granted
     * (e.g. an update of the app that brought this screen). Not during a
     * journey either: that would cover the tracking screen.
     */
    private fun maybeShowPermissions() {
        if (Permissions.introShown(this)) return
        if (!gtfsRepo.initialLoadDone.value) return
        if (updateProgressDialog?.isShowing == true || updateResultDialog?.isShowing == true) return
        GtfsUpdateWorker.pendingResult(this)?.let {
            if (!GtfsUpdateWorker.isAcknowledged(this, it.id)) return
        }
        if (JourneyService.trackingState.value is JourneyService.TrackingState.Tracking) return
        Permissions.markIntroShown(this)
        if (Permissions.allGranted(this)) return
        startActivity(android.content.Intent(this, PermissionsActivity::class.java))
    }

    private fun observeInitialLoad() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {

                // Overlay visibility is derived purely from state.
                // initialLoadDone never flips back to false, so a later
                // refresh cannot make the overlay reappear mid-use.
                launch {
                    gtfsRepo.initialLoadDone.collect { done ->
                        if (done) {
                            if (binding.layoutLoading.visibility == View.VISIBLE) {
                                showLoadingOverlay(false)
                                // During the first-run install the dialog
                                // speaks for itself and ends with its own
                                // result message — no second announcement.
                                if (updateProgressDialog?.isShowing != true) {
                                    binding.root.announceForAccessibility(
                                        "Данните са заредени. Приложението е готово."
                                    )
                                }
                            }
                            // Only now — with the DB confirmed populated and
                            // no import in flight — may we check the network
                            // for new data. This is the single trigger for a
                            // refresh in the whole app. It runs every time the
                            // app comes to the foreground (this block restarts
                            // on each STARTED); the worker itself skips it
                            // during a journey and within an hour of the last
                            // check, and on mobile data downloads once a day.
                            GtfsUpdateWorker.checkForUpdate(this@MainActivity)
                            maybeShowPermissions()
                        } else {
                            showLoadingOverlay(true)
                            // Plain backdrop until the data is there; the
                            // install dialog on top carries the messages
                            // (and the retry button if the install fails).
                            binding.tvLoadingMsg.text = "Инсталиране на данните…"
                            binding.tvLoadingMsg.contentDescription = binding.tvLoadingMsg.text
                        }
                    }
                }
            }
        }
    }
}
