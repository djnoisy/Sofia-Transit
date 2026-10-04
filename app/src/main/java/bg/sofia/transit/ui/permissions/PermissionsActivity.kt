package bg.sofia.transit.ui.permissions

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import bg.sofia.transit.databinding.ActivityPermissionsBinding
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.PermissionRequester
import bg.sofia.transit.util.Permissions

/**
 * First-run permissions screen: all three permissions on one page, one
 * "Разреши всички" that brings up the missing system windows one after
 * another. Shown once, by [bg.sofia.transit.MainActivity], after the data
 * install; afterwards Settings → "Разрешения" is the place for them.
 */
class PermissionsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermissionsBinding
    private lateinit var rows: PermissionRows

    private val requester = PermissionRequester(this, { this }) { rows.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = "Разрешения"

        rows = PermissionRows(binding.permissionRows, requester, withButtons = false)
        rows.refresh()

        binding.btnAllowAll.setOnClickListener {
            requester.requestAllMissing { allAsked() }
        }
        binding.btnNotNow.setOnClickListener {
            FileLogger.i(TAG, "Permissions screen closed with \"Не сега\"")
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        rows.refresh()
    }

    /**
     * All system windows have been through. Approximate location is the one
     * outcome worth stopping for: the journey cannot announce stops with it,
     * and the user may not have noticed the choice. Anything else the user
     * decided on purpose.
     */
    private fun allAsked() {
        rows.refresh()
        val approximate = !Permissions.hasFineLocation(this) && Permissions.hasAnyLocation(this)
        FileLogger.i(TAG, "Permissions asked: fine=${Permissions.hasFineLocation(this)}, " +
            "notifications=${Permissions.hasNotifications(this)}, " +
            "battery=${Permissions.isBatteryExempt(this)}")
        if (approximate) {
            binding.permissionRows.tvLocationInfo.announceForAccessibility(
                PermissionRows.LOCATION_APPROXIMATE)
            return
        }
        finish()
    }

    private companion object {
        const val TAG = "PermissionsActivity"
    }
}
