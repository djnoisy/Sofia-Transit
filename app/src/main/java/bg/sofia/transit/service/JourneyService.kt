package bg.sofia.transit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import bg.sofia.transit.MainActivity
import bg.sofia.transit.R
import bg.sofia.transit.data.db.dao.StopWithSequence
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.LocationHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID

/**
 * Foreground service that owns ALL journey-tracking state.
 *
 * Design principle: the service is the single source of truth. The UI
 * (ViewModel + Fragment) only observes [trackingState] and issues commands
 * (beginJourney / endJourney). This is what lets a journey survive tab
 * switches, screen rotation and the screen being locked — the old design
 * split the stop index between the ViewModel and the service, and the two
 * silently diverged, which is why wrong stop names were announced and
 * tracking froze.
 *
 * Progress model: instead of watching only the "current" stop, every GPS fix
 * searches a small window of stops AHEAD of the current index for the
 * nearest one. A stop missed by a GPS gap therefore never blocks progress —
 * the tracker just catches up at the next fix.
 *
 * Announcements per stop (all via the user's preferred TTS engine):
 *   1. ~[APPROACH_RADIUS] m before:  "Наближава спирка X"
 *   2. inside [ARRIVAL_RADIUS] m:    "Спирка X"  (fires on entering the
 *      radius, so a bus that passes through without stopping still triggers
 *      it — as agreed)
 *   3. on leaving the radius:        "Следваща спирка: Y"
 */
class JourneyService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JourneyService"
        const val CHANNEL_ID = "journey_channel"
        const val NOTIF_ID   = 1001

        /** "At the stop" radius. 60 m keeps adjacent city-centre stops from
         *  overlapping; timeliness is preserved by the 1-second GPS interval
         *  below (a bus at 50 km/h covers 60 m in ~4 s → several fixes). */
        const val ARRIVAL_RADIUS = 60.0

        /** Hysteresis: we only count as "departed" beyond this, so GPS
         *  jitter at the stop cannot fire arrive/depart repeatedly. */
        const val DEPART_RADIUS = 90.0

        /** Early warning distance for "Наближава спирка X". */
        const val APPROACH_RADIUS = 300.0

        /** How many stops ahead of the current one we scan on each fix.
         *  Covers up to that many consecutively missed stops in one gap. */
        private const val LOOKAHEAD = 3

        /**
         * Tracking state, observable without binding to the service.
         * The UI collects this; the service is the only writer.
         */
        private val _trackingState = MutableStateFlow<TrackingState>(TrackingState.Idle)
        val trackingState: StateFlow<TrackingState> = _trackingState

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, JourneyService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, JourneyService::class.java))
    }

    /** Immutable snapshot of the journey, re-emitted on every change. */
    sealed class TrackingState {
        object Idle : TrackingState()
        data class Tracking(
            val routeLabel: String,          // "Автобус 102 → СТУДЕНТСКИ ГРАД"
            val stops: List<StopWithSequence>,
            val currentIdx: Int,             // index of the stop we're heading to / at
            val atStop: Boolean,
            val distanceToNextMetres: Int?   // live distance; null until first fix
        ) : TrackingState()
    }

    inner class LocalBinder : Binder() { fun get() = this@JourneyService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    // ── TTS ───────────────────────────────────────────────────────────────
    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private val pendingAnnouncements = mutableListOf<String>()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale("bg", "BG"))
            ttsReady = true
            synchronized(pendingAnnouncements) {
                pendingAnnouncements.forEach {
                    tts.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                }
                pendingAnnouncements.clear()
            }
        } else {
            FileLogger.e(TAG, "TTS init failed with status $status")
        }
    }

    private fun announce(text: String) {
        FileLogger.i(TAG, "TTS: $text")
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        } else {
            synchronized(pendingAnnouncements) { pendingAnnouncements.add(text) }
        }
        updateNotif(text)
    }

    // ── Journey state (private; published only via _trackingState) ────────
    private var routeLabel = ""
    private var orderedStops: List<StopWithSequence> = emptyList()
    private var stopLatLon: List<Pair<Double, Double>> = emptyList()
    private var currentIdx = 0
    private var atStop = false
    private var approachAnnounced = false
    /** True until the first GPS fix positions us on the route. */
    private var awaitingFirstFix = true

    private lateinit var fusedClient: FusedLocationProviderClient

    // ── Commands (called by the ViewModel through the binder) ─────────────

    /**
     * Starts tracking. Works both from the boarding stop AND mid-journey:
     * the initial position on the route is not assumed — it is computed from
     * the first GPS fix by finding the nearest not-yet-passed stop, seeded
     * with [boardingStopIdx] as a lower bound when the user picked a
     * concrete boarding stop from the arrivals list.
     */
    fun beginJourney(
        label: String,
        stops: List<StopWithSequence>,
        latLons: List<Pair<Double, Double>>,
        boardingStopIdx: Int
    ) {
        routeLabel        = label
        orderedStops      = stops
        stopLatLon        = latLons
        currentIdx        = boardingStopIdx.coerceIn(0, stops.lastIndex)
        atStop            = false
        approachAnnounced = false
        awaitingFirstFix  = true

        publish(distance = null)
        startLocUpdates()
        announce("Следене на пътуването започна.")
    }

    fun endJourney() {
        stopLocUpdates()
        orderedStops = emptyList()
        stopLatLon   = emptyList()
        currentIdx   = 0
        atStop       = false
        _trackingState.value = TrackingState.Idle
        stopSelf()
    }

    // ── Progress engine ───────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        if (orderedStops.isEmpty()) return

        // On the very first fix, snap to the route: among ALL stops from the
        // boarding index onward, pick the nearest. This is what makes
        // starting mid-journey work — if the user began tracking three stops
        // after boarding, we land on the right stop immediately instead of
        // announcing ancient history.
        if (awaitingFirstFix) {
            awaitingFirstFix = false
            var best = currentIdx
            var bestDist = Double.MAX_VALUE
            for (i in currentIdx..orderedStops.lastIndex) {
                val d = distTo(loc, i)
                if (d < bestDist) { bestDist = d; best = i }
            }
            currentIdx = best
            FileLogger.i(TAG, "First fix: snapped to stop #$best " +
                "(${orderedStops[best].stopName}, ${bestDist.toInt()} m)")
        }

        // Look-ahead window: nearest stop among current..current+LOOKAHEAD.
        val end = (currentIdx + LOOKAHEAD).coerceAtMost(orderedStops.lastIndex)
        var nearest = currentIdx
        var nearestDist = distTo(loc, currentIdx)
        for (i in (currentIdx + 1)..end) {
            val d = distTo(loc, i)
            if (d < nearestDist) { nearestDist = d; nearest = i }
        }

        when {
            // ── Inside a stop's radius → we are AT that stop ──────────────
            nearestDist <= ARRIVAL_RADIUS -> {
                if (!atStop || nearest != currentIdx) {
                    // If we jumped over stops the GPS never saw, announce the
                    // skipped ones the same way as a normal stop — as agreed,
                    // a passed stop is announced like a stopped-at stop. If we
                    // were already AT currentIdx, it has been announced, so
                    // skipped ones start after it.
                    val firstSkipped = if (atStop) currentIdx + 1 else currentIdx
                    for (i in firstSkipped until nearest) {
                        announce("Спирка: ${orderedStops[i].stopName}")
                    }
                    currentIdx = nearest
                    atStop = true
                    approachAnnounced = false
                    announce("Спирка: ${orderedStops[nearest].stopName}")
                }
            }

            // ── We were at a stop and have now clearly left it ────────────
            atStop && distTo(loc, currentIdx) > DEPART_RADIUS -> {
                atStop = false
                approachAnnounced = false
                if (currentIdx < orderedStops.lastIndex) {
                    currentIdx += 1
                    announce("Следваща спирка: ${orderedStops[currentIdx].stopName}")
                } else {
                    announce("Крайна спирка. Пристигнахте.")
                    endJourney()
                    return
                }
            }

            // ── Moving; did we silently pass the current stop? ────────────
            !atStop && nearest > currentIdx
                    && nearestDist < distTo(loc, currentIdx) - 30.0 -> {
                // The GPS gap swallowed the stop entirely (never inside the
                // radius). Announce the passed stop(s) as agreed, then
                // continue toward the nearest upcoming one.
                for (i in currentIdx until nearest) {
                    announce("Спирка: ${orderedStops[i].stopName}")
                }
                currentIdx = nearest
                approachAnnounced = false
                announce("Следваща спирка: ${orderedStops[nearest].stopName}")
            }

            // ── Approaching warning ───────────────────────────────────────
            !atStop && !approachAnnounced
                    && distTo(loc, currentIdx) <= APPROACH_RADIUS -> {
                approachAnnounced = true
                announce("Наближава спирка: ${orderedStops[currentIdx].stopName}")
            }
        }

        publish(distance = distTo(loc, currentIdx).toInt())
    }

    private fun distTo(loc: Location, idx: Int): Double {
        val (lat, lon) = stopLatLon.getOrNull(idx) ?: return Double.MAX_VALUE
        return LocationHelper.distanceMetres(loc.latitude, loc.longitude, lat, lon)
    }

    private fun publish(distance: Int?) {
        _trackingState.value = TrackingState.Tracking(
            routeLabel            = routeLabel,
            stops                 = orderedStops,
            currentIdx            = currentIdx,
            atStop                = atStop,
            distanceToNextMetres  = distance
        )
    }

    // ── Location plumbing ─────────────────────────────────────────────────
    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { onFix(it) }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocUpdates() {
        // 1-second, high-accuracy updates while a journey is active. The
        // 60 m arrival radius is crossed in ~4 s at city bus speed, so the
        // update rate must guarantee several fixes inside it. Battery cost
        // is confined to the duration of the journey.
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L)
            .setMinUpdateIntervalMillis(1_000L)
            .build()
        fusedClient.requestLocationUpdates(req, locCallback, mainLooper)
    }

    private fun stopLocUpdates() {
        try { fusedClient.removeLocationUpdates(locCallback) } catch (_: Exception) {}
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        // No engine name passed → binds to the engine chosen by the user in
        // system Settings → "Синтезиран говор" (works together with the
        // <queries> TTS_SERVICE declaration in the manifest).
        tts = TextToSpeech(this, this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()

        val notif = buildNotif("Следене на пътуването…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        stopLocUpdates()
        // If the system kills us mid-journey, don't leave the UI thinking
        // a journey is still active.
        if (_trackingState.value is TrackingState.Tracking) {
            _trackingState.value = TrackingState.Idle
        }
        try { tts.shutdown() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ── Notification ──────────────────────────────────────────────────────
    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "Пътуване", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
    }

    private fun buildNotif(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bus)
            .setContentTitle("Градски транспорт")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotif(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_ID, buildNotif(text))
    }
}
