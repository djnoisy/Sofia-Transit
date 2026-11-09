package bg.sofia.transit.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * Location switched on or off in the phone's settings while a screen is in
 * front (the quick settings tile pulled down over it does not pause it):
 * [onChange] with the new state, once per change — the system tells of each
 * provider, several times for one switch. Started in onResume, stopped in
 * onPause.
 */
class LocationSwitchWatcher(private val onChange: (on: Boolean) -> Unit) {

    private var registeredWith: Context? = null
    private var on: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val now = Permissions.isLocationOn(context)
            if (now != on) {
                on = now
                onChange(now)
            }
        }
    }

    fun start(context: Context) {
        if (registeredWith != null) return
        on = Permissions.isLocationOn(context)
        // A system broadcast: received by a receiver not exported too.
        ContextCompat.registerReceiver(context, receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        registeredWith = context
    }

    fun stop() {
        registeredWith?.let {
            try { it.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        }
        registeredWith = null
    }
}
