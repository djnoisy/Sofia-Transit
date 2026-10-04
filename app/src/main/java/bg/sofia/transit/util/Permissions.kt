package bg.sofia.transit.util

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * State of the three things the app needs from the system: precise location,
 * notifications and exemption from battery optimisation. Read-only checks;
 * asking for them is [PermissionRequester]'s job.
 */
object Permissions {

    private const val PREFS = "permissions"
    private const val KEY_INTRO_SHOWN = "intro_shown"

    /** The POST_NOTIFICATIONS runtime permission exists from Android 13. */
    val notificationsAreRuntime: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun hasFineLocation(ctx: Context): Boolean = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    /** Precise or approximate — enough for the stop lists, not for a journey. */
    fun hasAnyLocation(ctx: Context): Boolean =
        hasFineLocation(ctx) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Covers both the Android 13+ permission and notifications switched off by hand. */
    fun hasNotifications(ctx: Context): Boolean =
        NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    fun isBatteryExempt(ctx: Context): Boolean = try {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (e: Exception) {
        FileLogger.w("Permissions", "Battery optimisation state unknown: ${e.message}")
        false
    }

    /** Everything the permissions screen asks for is already in place. */
    fun allGranted(ctx: Context): Boolean =
        hasFineLocation(ctx) &&
            (!notificationsAreRuntime || hasNotifications(ctx)) &&
            isBatteryExempt(ctx)

    /** The first-run permissions screen is shown once, whatever the user chose. */
    fun introShown(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_INTRO_SHOWN, false)

    fun markIntroShown(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_INTRO_SHOWN, true).apply()
    }

    /** Whether the system dialog for [key] has been launched before. */
    internal fun askedBefore(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("asked_$key", false)

    internal fun markAsked(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("asked_$key", true).apply()
    }

    private fun granted(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED
}

/**
 * Asks for the permissions, choosing between the system dialog and the
 * system settings page.
 *
 * After two refusals Android stops showing the dialog and refuses at once,
 * without the user seeing anything. That case is recognised (asked before,
 * and the system no longer wants a rationale) and the app's page in the
 * system settings is opened instead, where the permission can still be
 * turned on.
 *
 * Every request takes a continuation, run once the user is back — so the
 * permissions screen can chain all three. [onChanged] runs after each step
 * so the caller can refresh what it shows.
 *
 * Must be created during the owner's initialisation (a property
 * initialiser), as the result launchers are registered in the constructor.
 */
class PermissionRequester(
    caller: ActivityResultCaller,
    private val activity: () -> Activity,
    private val onChanged: () -> Unit
) {
    private companion object {
        const val TAG = "PermissionRequester"
        const val KEY_LOCATION = "location"
        const val KEY_NOTIFICATIONS = "notifications"
        const val SETTINGS_HINT =
            "Включете разрешението в отворената страница и се върнете с бутона Назад."
    }

    private var next: (() -> Unit)? = null

    private val permissionLauncher = caller.registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { finishStep() }

    /** System settings screens; the result only tells that the user is back. */
    private val screenLauncher = caller.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { finishStep() }

    private fun finishStep() {
        onChanged()
        val n = next
        next = null
        n?.invoke()
    }

    /** Precise location. Also upgrades an approximate one. */
    fun requestLocation(then: () -> Unit = {}) {
        val act = activity()
        if (Permissions.hasFineLocation(act)) { then(); return }
        if (dialogBlocked(act, KEY_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) {
            openScreen(appDetailsIntent(act), then)
            return
        }
        Permissions.markAsked(act, KEY_LOCATION)
        FileLogger.i(TAG, "Asking for location")
        next = then
        permissionLauncher.launch(arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    fun requestNotifications(then: () -> Unit = {}) {
        val act = activity()
        if (Permissions.hasNotifications(act)) { then(); return }
        val permissionMissing = Permissions.notificationsAreRuntime &&
            ContextCompat.checkSelfPermission(act, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (permissionMissing &&
            !dialogBlocked(act, KEY_NOTIFICATIONS, Manifest.permission.POST_NOTIFICATIONS)) {
            Permissions.markAsked(act, KEY_NOTIFICATIONS)
            FileLogger.i(TAG, "Asking for notifications")
            next = then
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
            return
        }
        // Dialog no longer shown, or notifications switched off by hand
        // (any Android version): only the settings page can turn them on.
        openScreen(notificationSettingsIntent(act), then)
    }

    /**
     * Exemption from battery optimisation, via the system's direct request;
     * the optimisation list when the direct request is unavailable.
     */
    fun requestBattery(then: () -> Unit = {}) {
        val act = activity()
        if (Permissions.isBatteryExempt(act)) { then(); return }
        @Suppress("BatteryLife")
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${act.packageName}"))
        if (launch(direct, then)) return
        openBatteryList(then)
    }

    private fun openBatteryList(then: () -> Unit) {
        if (launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), then)) return
        Toast.makeText(activity(), "Отворете Настройки → Батерия за това приложение",
            Toast.LENGTH_LONG).show()
        then()
    }

    /**
     * Everything still missing, one system window after another. Notifications
     * are left out before Android 13, where nothing has to be granted.
     */
    fun requestAllMissing(done: () -> Unit) {
        requestLocation {
            val afterNotifications = { requestBattery(done) }
            if (Permissions.notificationsAreRuntime) requestNotifications(afterNotifications)
            else afterNotifications()
        }
    }

    /**
     * True when the system will refuse without showing its dialog: it was
     * asked before and no longer wants a rationale. A first request also has
     * no rationale, hence the "asked before" record.
     */
    private fun dialogBlocked(act: Activity, key: String, perm: String): Boolean =
        Permissions.askedBefore(act, key) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(act, perm)

    private fun openScreen(intent: Intent, then: () -> Unit) {
        Toast.makeText(activity(), SETTINGS_HINT, Toast.LENGTH_LONG).show()
        // The general app page is the fallback; it always exists.
        if (!launch(intent, then) && !launch(appDetailsIntent(activity()), then)) then()
    }

    private fun launch(intent: Intent, then: () -> Unit): Boolean = try {
        next = then
        screenLauncher.launch(intent)
        true
    } catch (e: Exception) {
        next = null
        FileLogger.w(TAG, "Could not open ${intent.action}: ${e.message}")
        false
    }

    private fun appDetailsIntent(ctx: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}"))

    private fun notificationSettingsIntent(ctx: Context) =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
}
