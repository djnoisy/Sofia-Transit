package bg.sofia.transit.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import bg.sofia.transit.MainActivity
import bg.sofia.transit.R
import bg.sofia.transit.data.db.dao.StopWithSequence
import com.google.android.gms.location.*
import java.util.*

/**
 * Foreground service – announces current / next stops via TTS.
 * Fully compatible with TalkBack (uses QUEUE_ADD so TalkBack can still speak).
 */
class JourneyService : Service(), TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "JourneyService"
        const val CHANNEL_ID   = "journey_channel"
        const val NOTIF_ID     = 1001
        const val ARRIVAL_RADIUS = 120.0   // metres

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, JourneyService::class.java))
        fun stop(ctx: Context)  = ctx.stopService(Intent(ctx, JourneyService::class.java))
    }

    inner class LocalBinder : Binder() { fun get() = this@JourneyService }
    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    /** Announcements that arrived before TTS finished initialising. */
    private val pendingAnnouncements = mutableListOf<String>()
    private lateinit var fusedClient: FusedLocationProviderClient

    // Journey state
    private var orderedStops: List<StopWithSequence> = emptyList()
    private var stopLatLon: List<Pair<Double,Double>> = emptyList()
    var currentIdx = 0; private set
    var atStop     = false; private set

    var locationListener: ((Location) -> Unit)? = null

    // ── TTS ───────────────────────────────────────────────────────────────
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.setLanguage(Locale("bg", "BG"))
            ttsReady = true
            // Flush anything that came in while we were initialising
            synchronized(pendingAnnouncements) {
                pendingAnnouncements.forEach {
                    tts.speak(it, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
                }
                pendingAnnouncements.clear()
            }
        }
    }

    fun announce(text: String) {
        Log.d(TAG, "TTS: $text")
        if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_ADD, null, UUID.randomUUID().toString())
        } else {
            // Don't lose announcements that fire during init (~1–2 s on cold start)
            synchronized(pendingAnnouncements) { pendingAnnouncements.add(text) }
        }
        updateNotif(text)
    }

    // ── Journey control ───────────────────────────────────────────────────
    fun beginJourney(stops: List<StopWithSequence>, latLons: List<Pair<Double,Double>>) {
        orderedStops = stops
        stopLatLon   = latLons
        currentIdx   = 0
        atStop       = false
        startLocUpdates()
        stops.firstOrNull()?.let { announce("Следваща спирка: ${it.stopName}") }
    }

    fun endJourney() {
        orderedStops = emptyList()
        currentIdx = 0
        try { fusedClient.removeLocationUpdates(locCallback) } catch (_: Exception) {}
    }

    fun onArrival() {
        atStop = true
        announce("Спирка: ${orderedStops.getOrNull(currentIdx)?.stopName ?: ""}")
    }

    fun onDeparture() {
        atStop = false
        currentIdx++
        val next = orderedStops.getOrNull(currentIdx)
        if (next != null) announce("Следваща спирка: ${next.stopName}")
        else              announce("Крайна спирка. Пристигнахте.")
    }

    // ── Location ──────────────────────────────────────────────────────────
    private val locCallback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            r.lastLocation?.let { locationListener?.invoke(it) }
        }
    }

    @Suppress("MissingPermission")
    private fun startLocUpdates() {
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000L)
            .setMinUpdateDistanceMeters(10f).build()
        fusedClient.requestLocationUpdates(req, locCallback, mainLooper)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this, this)
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        createChannel()

        val notif = buildNotif("Следене на пътуването…")
        // Android 10+ requires the FGS type to match the manifest declaration.
        // On 14+ (API 34) it MUST be passed explicitly or the service crashes.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onDestroy() {
        try { fusedClient.removeLocationUpdates(locCallback) } catch (_: Exception) {}
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
