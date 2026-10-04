package bg.sofia.transit.ui.permissions

import android.content.Context
import android.view.View
import bg.sofia.transit.databinding.ViewPermissionRowsBinding
import bg.sofia.transit.util.PermissionRequester
import bg.sofia.transit.util.Permissions

/**
 * Fills the shared permission rows ([ViewPermissionRowsBinding]) from the
 * current system state. [withButtons] is false on the first-run screen,
 * which has a single "Разреши всички" instead of a button per row.
 */
class PermissionRows(
    private val rows: ViewPermissionRowsBinding,
    private val requester: PermissionRequester,
    private val withButtons: Boolean
) {
    companion object {
        const val LOCATION_INFO =
            "За намиране на спирките около вас и следене на пътуването. " +
            "Изберете „Точно“ и „Докато използвате приложението“."
        const val LOCATION_APPROXIMATE =
            "Дадено е само приблизително местоположение. " +
            "За обявяване на спирки е нужно точно."
    }

    init {
        rows.btnLocation.setOnClickListener { requester.requestLocation() }
        rows.btnNotifications.setOnClickListener { requester.requestNotifications() }
        rows.btnBattery.setOnClickListener { requester.requestBattery() }
    }

    /** Re-reads the state; call on resume and after each request. */
    fun refresh() {
        val ctx: Context = rows.root.context

        val fine = Permissions.hasFineLocation(ctx)
        val approximate = !fine && Permissions.hasAnyLocation(ctx)
        rows.tvLocationStatus.text = "Местоположение: " + when {
            fine        -> "разрешено"
            approximate -> "само приблизително"
            else        -> "не е разрешено"
        }
        rows.tvLocationInfo.text = if (approximate) LOCATION_APPROXIMATE else LOCATION_INFO
        rows.btnLocation.visibility = visibleIf(withButtons && !fine)

        // Before Android 13 notifications need no permission, so the
        // first-run screen has nothing to ask. Settings still shows the row:
        // they can be switched off by hand on any version.
        rows.rowNotifications.visibility =
            visibleIf(withButtons || Permissions.notificationsAreRuntime)
        val notifications = Permissions.hasNotifications(ctx)
        rows.tvNotificationsStatus.text =
            "Известия: " + if (notifications) "разрешено" else "не е разрешено"
        rows.btnNotifications.visibility = visibleIf(withButtons && !notifications)

        val exempt = Permissions.isBatteryExempt(ctx)
        rows.tvBatteryStatus.text =
            "Работа при заключен екран: " + if (exempt) "без ограничения" else "ограничено"
        rows.btnBattery.visibility = visibleIf(withButtons && !exempt)
    }

    private fun visibleIf(b: Boolean) = if (b) View.VISIBLE else View.GONE
}
