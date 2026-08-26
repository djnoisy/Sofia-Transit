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
import bg.sofia.transit.util.AppSettings
import bg.sofia.transit.util.FileLogger
import bg.sofia.transit.util.LocationHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import bg.sofia.transit.data.repository.RealtimeRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
@AndroidEntryPoint
class JourneyService : Service(), TextToSpeech.OnInitListener {

    @Inject lateinit var realtimeRepo: RealtimeRepository
    @Inject lateinit var settings: AppSettings

    /** Service-lifetime scope for the ETA polling loop. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        /** Distance at which "Слизате тук" is announced. Matches the normal
         *  arrival radius so the passenger hears it in time to reach the door. */
        const val ALIGHT_ANNOUNCE_RADIUS = 60.0

        /** Distance at which the journey is considered complete and tracking
         *  stops. Tighter than the announcement, so the announcement always
         *  comes first and the journey only ends once genuinely at the stop. */
        const val ALIGHT_END_RADIUS = 30.0

        /** If the vehicle makes no forward progress for this long, the
         *  journey is assumed over (user forgot to stop tracking). */
        private const val INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * Tracking state, observable without binding to the service.
         * The UI collects this; the service is the only writer.
         */
        private val _trackingState = MutableStateFlow<TrackingState>(TrackingState.Idle)
        val trackingState: StateFlow<TrackingState> = _trackingState

        /**
         * One-shot events the UI must react to (navigation), separate from
         * state. Replay 0: an event missed while the app is backgrounded is
         * not re-fired when it returns.
         */
        private val _events = MutableSharedFlow<JourneyEvent>(extraBufferCapacity = 4)
        val events: SharedFlow<JourneyEvent> = _events

        fun start(ctx: Context) =
            ctx.startForegroundService(Intent(ctx, JourneyService::class.java))
        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, JourneyService::class.java))
    }

    /** Origin of the displayed arrival estimate. */
    enum class EtaSource {
        NONE,
        /** Live prediction from the CGM feed for this exact trip and stop. */
        REALTIME,
        /** Static timetable, shifted by the delay currently observed on this
         *  trip. Better than the raw timetable while the vehicle runs late. */
        SCHEDULE_ADJUSTED,
        /** Raw static timetable — no live data available at all. */
        SCHEDULE
    }

    /** One-shot journey events. */
    sealed class JourneyEvent {
        /** The chosen alighting stop was reached — UI should go to Stops. */
        object DestinationReached : JourneyEvent()
        /** The route's final stop was reached — the journey is over. */
        object RouteEnded : JourneyEvent()
    }

    /** Immutable snapshot of the journey, re-emitted on every change. */
    sealed class TrackingState {
        object Idle : TrackingState()
        data class Tracking(
            val routeLabel: String,          // "Автобус 102 → СТУДЕНТСКИ ГРАД"
            val stops: List<StopWithSequence>,
            val currentIdx: Int,             // index of the stop we're heading to / at
            val atStop: Boolean,
            val distanceToNextMetres: Int?,  // live distance; null until first fix
            /** Chosen alighting stop, or null if the user hasn't picked one. */
            val destinationIdx: Int? = null,
            /** Arrival of THIS vehicle at the chosen stop, epoch seconds.
             *  Null when no destination is set, or when neither the feed nor
             *  the timetable can supply a time. */
            val destinationEtaEpoch: Long? = null,
            /** Where [destinationEtaEpoch] came from. The UI must label a
             *  timetable-derived estimate differently from a live one — a
             *  scheduled time carries no traffic information and would
             *  otherwise be mistaken for a real prediction. */
            val etaSource: EtaSource = EtaSource.NONE
        ) : TrackingState() {

            /** The stop currently shown as "Спирка"/"Следваща спирка". */
            val currentStop: StopWithSequence? get() = stops.getOrNull(currentIdx)

            val destinationStop: StopWithSequence?
                get() = destinationIdx?.let { stops.getOrNull(it) }

            /**
             * Stops left to ride. Counts to the chosen destination when set,
             * otherwise to the end of the route.
             */
            val stopsRemaining: Int
                get() {
                    val target = destinationIdx ?: stops.lastIndex
                    return (target - currentIdx).coerceAtLeast(0)
                }
        }
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
            tts.setSpeechRate(settings.speechRate)

            // Announcements stay on the default media stream — the same one
            // used by audiobook readers and by the system's own "test speech"
            // button. Two reasons not to move them to the accessibility
            // stream: its volume is managed separately and on many devices has
            // no user-facing slider, so the passenger could not turn
            // announcements up or down with the hardware keys; and a screen
            // reader does not duck media by default anyway, so there is
            // nothing to escape. We never request audio focus, so we mix with
            // whatever else is playing rather than interrupting it.
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

    /** Binds TTS to the engine currently chosen in app settings. */
    private fun createTts() {
        val engine = settings.ttsEngine
        tts = if (engine.isNotBlank()) {
            TextToSpeech(this, this, engine)
        } else {
            TextToSpeech(this, this)
        }
    }

    /**
     * Rebinds to a newly chosen speech engine mid-journey, so a change in
     * Settings takes effect without having to restart tracking.
     */
    /** Applies the speaking rate from settings without recreating TTS. */
    fun applySpeechRate() {
        if (ttsReady) {
            try { tts.setSpeechRate(settings.speechRate) } catch (_: Exception) {}
        }
    }

    fun reloadTtsEngine() {
        try { tts.shutdown() } catch (_: Exception) {}
        ttsReady = false
        createTts()
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

    /** Chosen alighting stop; null until the user picks one. */
    private var destinationIdx: Int? = null
    /** Which of the "2 stops / 1 stop / next stop" warnings already fired. */
    private val alightWarningsFired = mutableSetOf<Int>()
    /** Guards the "Слизате тук" announcement so it is spoken once. */
    private var alightAnnounced = false
    /** Timestamp of the last forward progress, for the inactivity timeout. */
    private var lastProgressMs = System.currentTimeMillis()

    /** trip_id being ridden — needed to query its real-time predictions. */
    private var tripId: String = ""
    /** Latest real-time ETA at the destination stop, epoch seconds. */
    private var destinationEtaEpoch: Long? = null
    private var etaSource: EtaSource = EtaSource.NONE
    private var etaJob: Job? = null

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
        tripId: String,
        stops: List<StopWithSequence>,
        latLons: List<Pair<Double, Double>>,
        boardingStopIdx: Int
    ) {
        this.tripId       = tripId
        routeLabel        = label
        orderedStops      = stops
        stopLatLon        = latLons
        currentIdx        = boardingStopIdx.coerceIn(0, stops.lastIndex)
        atStop            = false
        approachAnnounced = false
        awaitingFirstFix  = true
        destinationIdx    = null
        alightWarningsFired.clear()
        alightAnnounced   = false
        lastProgressMs    = System.currentTimeMillis()

        publish(distance = null)
        startLocUpdates()
        announce("Следене на пътуването започна.")
    }

    /**
     * Sets (or replaces) the stop the user intends to get off at. Can be
     * called at any time during the journey; passing null clears it.
     * Warnings already fired for a previous destination are reset so the
     * new one gets its own full set.
     */
    fun setDestination(idx: Int?) {
        destinationIdx = idx?.coerceIn(0, orderedStops.lastIndex)
        alightWarningsFired.clear()
        alightAnnounced = false
        destinationEtaEpoch = null
        etaSource = EtaSource.NONE
        restartEtaPolling()
        // NOTE: the confirmation of the choice itself is NOT spoken here.
        // Picking or clearing a destination is an on-screen interaction, so
        // the UI announces it through TalkBack instead — that queues with the
        // screen reader rather than interrupting it, and stays silent for
        // users who have no screen reader but do want travel announcements.
        // Only travel events (approaching, arriving, alighting) go through
        // the speech engine, because those must be heard with the phone in a
        // pocket.
        if (destinationIdx != null) {
            // If the destination is already imminent, fire the right warning
            // immediately instead of waiting for the next stop transition.
            evaluateAlightWarnings(announceNow = true)
        }
        publishLastKnown()
    }

    fun endJourney() {
        stopLocUpdates()
        orderedStops = emptyList()
        stopLatLon   = emptyList()
        currentIdx   = 0
        atStop       = false
        destinationIdx = null
        alightWarningsFired.clear()
        _trackingState.value = TrackingState.Idle
        stopSelf()
    }

    // ── Progress engine ───────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        if (orderedStops.isEmpty()) return

        val idxBefore = currentIdx

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
                        announce("Спирка, ${orderedStops[i].stopName}.")
                    }
                    currentIdx = nearest
                    atStop = true
                    approachAnnounced = false
                    announce("Спирка, ${orderedStops[nearest].stopName}.")

                    // Final stop reached → the journey is over. Ending here
                    // rather than on departure matters: a vehicle standing at
                    // its terminus may never trigger a "departed" event, which
                    // would leave tracking running indefinitely.
                    // Skipped when the chosen alighting stop IS the terminus,
                    // so the destination logic can own the ending (it warns at
                    // 60 m and finishes at 30 m).
                    if (nearest == orderedStops.lastIndex &&
                        destinationIdx != orderedStops.lastIndex) {
                        announce("Крайна спирка. Пристигнахте.")
                        _events.tryEmit(JourneyEvent.RouteEnded)
                        endJourney()
                        return
                    }
                }
            }

            // ── We were at a stop and have now clearly left it ────────────
            atStop && distTo(loc, currentIdx) > DEPART_RADIUS -> {
                atStop = false
                approachAnnounced = false
                if (currentIdx < orderedStops.lastIndex) {
                    currentIdx += 1
                    announce("Следваща спирка, ${orderedStops[currentIdx].stopName}.")
                }
                // No terminus case here any more — arriving at the final stop
                // already ended the journey above.
            }

            // ── Moving; did we silently pass the current stop? ────────────
            !atStop && nearest > currentIdx
                    && nearestDist < distTo(loc, currentIdx) - 30.0 -> {
                // The GPS gap swallowed the stop entirely (never inside the
                // radius). Announce the passed stop(s) as agreed, then
                // continue toward the nearest upcoming one.
                for (i in currentIdx until nearest) {
                    announce("Спирка, ${orderedStops[i].stopName}.")
                }
                currentIdx = nearest
                approachAnnounced = false
                announce("Следваща спирка, ${orderedStops[nearest].stopName}.")
            }

            // ── Approaching warning ───────────────────────────────────────
            !atStop && !approachAnnounced
                    && distTo(loc, currentIdx) <= APPROACH_RADIUS -> {
                approachAnnounced = true
                announce("Наближава спирка, ${orderedStops[currentIdx].stopName}.")
            }
        }

        // ── Destination handling ─────────────────────────────────────────
        val dest = destinationIdx
        if (dest != null) {

            val destDist = distTo(loc, dest)

            // 1a) Announce at 60 m — early enough to signal the driver and
            //     reach the door.
            if (destDist <= ALIGHT_ANNOUNCE_RADIUS && !alightAnnounced) {
                alightAnnounced = true
                announce("Слизате тук, ${orderedStops[dest].stopName}.")
            }

            // 1b) End the journey only once genuinely at the stop (<30 m),
            //     so the announcement always precedes the screen switching
            //     away to the Stops tab.
            if (destDist <= ALIGHT_END_RADIUS) {
                _events.tryEmit(JourneyEvent.DestinationReached)
                endJourney()
                return
            }

            // 2) Safety net: the destination was passed without the 30 m
            //    radius ever registering — almost always because the user
            //    missed their stop. Keep tracking, but drop the destination
            //    so the journey behaves as if none had been chosen.
            if (currentIdx > dest) {
                FileLogger.i(TAG, "Destination passed without alighting; clearing it")
                destinationIdx = null
                alightWarningsFired.clear()
                alightAnnounced = false
                destinationEtaEpoch = null
                etaSource = EtaSource.NONE
                announce("Спирката за слизане е подмината.")
            } else if (currentIdx != idxBefore) {
                // 3) We advanced a stop — check the countdown warnings.
                evaluateAlightWarnings(announceNow = true)
            }
        }

        // ── Inactivity timeout ───────────────────────────────────────────
        if (currentIdx != idxBefore) {
            lastProgressMs = System.currentTimeMillis()
        } else if (System.currentTimeMillis() - lastProgressMs > INACTIVITY_TIMEOUT_MS) {
            FileLogger.i(TAG, "No progress for 10 min — ending journey automatically")
            announce("Няма движение по маршрута. Следенето е спряно.")
            endJourney()
            return
        }

        publish(distance = distTo(loc, currentIdx).toInt())
    }

    /**
     * Fires the alighting countdown announcements, each at most once per
     * chosen destination:
     *   2 stops to go → "Остават две спирки до слизане."
     *   1 stop to go  → "Остава една спирка до слизане."
     *   destination is the stop we're heading to → "Слизате на следващата спирка."
     *
     * Keyed by the number of stops remaining, so re-selecting the same
     * destination after passing it re-arms them.
     */
    private fun evaluateAlightWarnings(announceNow: Boolean) {
        val dest = destinationIdx ?: return
        val remaining = dest - currentIdx
        if (remaining !in 0..2) return
        if (!alightWarningsFired.add(remaining)) return   // already announced
        if (!announceNow) return

        when (remaining) {
            2 -> announce("Остават две спирки до слизане.")
            1 -> announce("Остава една спирка до слизане.")
            0 -> announce("Слизате на следващата спирка, " +
                          "${orderedStops[dest].stopName}.")
        }
    }

    /** Re-emits state using the last known distance (for non-GPS changes,
     *  e.g. the user picking a destination). */
    private fun publishLastKnown() {
        val prev = _trackingState.value as? TrackingState.Tracking
        publish(distance = prev?.distanceToNextMetres)
    }

    /**
     * Polls the real-time feed for THIS vehicle's predicted arrival at the
     * chosen alighting stop. Every 30 s: the feed itself only refreshes on
     * that order, so polling faster would just re-download the same bytes.
     *
     * A null result is expected and harmless — CGM publishes predictions
     * only a limited time ahead, so a distant stop simply has none yet and
     * the UI falls back to showing the stop count.
     */
    private fun restartEtaPolling() {
        etaJob?.cancel()
        val dest = destinationIdx ?: run {
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
            return
        }
        val destStop = orderedStops.getOrNull(dest) ?: return
        if (tripId.isBlank()) return

        etaJob = serviceScope.launch {
            while (isActive) {
                computeEta(dest, destStop)
                if (destinationIdx == dest) {
                    withContext(Dispatchers.Main) { publishLastKnown() }
                }
                delay(30_000L)
            }
        }
    }

    /**
     * Three-tier arrival estimate for the alighting stop, best source first:
     *
     *  1. REALTIME — the feed has a prediction for this exact trip at that
     *     stop. Always preferred.
     *  2. SCHEDULE_ADJUSTED — the feed has no entry for the far-off stop but
     *     DOES have one for a nearer stop on the same trip. Comparing that
     *     against the timetable yields the delay this vehicle is currently
     *     running, which we apply to the destination's scheduled time. A bus
     *     six minutes late now will still be roughly six minutes late later,
     *     so this is markedly better than the raw timetable.
     *  3. SCHEDULE — no live data at all; the plain timetable time.
     *
     * The tier is reported to the UI so a planned time is never displayed as
     * if it were a live prediction.
     */
    private suspend fun computeEta(dest: Int, destStop: StopWithSequence) {
        // Tier 1
        realtimeRepo.getArrivalForTripAtStop(tripId, destStop.stopId)?.let { live ->
            destinationEtaEpoch = live
            etaSource = EtaSource.REALTIME
            return
        }

        val scheduled = scheduledEpoch(destStop.arrivalTime)
        if (scheduled == null) {
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
            return
        }

        // Tier 2: find the delay from any nearer stop the feed does cover.
        val delaySec = observedDelaySeconds()
        if (delaySec != null) {
            destinationEtaEpoch = scheduled + delaySec
            etaSource = EtaSource.SCHEDULE_ADJUSTED
            FileLogger.d(TAG, "ETA from schedule + ${delaySec}s observed delay")
        } else {
            destinationEtaEpoch = scheduled
            etaSource = EtaSource.SCHEDULE
        }
    }

    /**
     * How late this vehicle is running right now, in seconds, or null if the
     * feed covers none of the stops we can compare against. Scans forward
     * from the current position so the sample is as fresh as possible.
     */
    private suspend fun observedDelaySeconds(): Int? {
        val upTo = (currentIdx + 6).coerceAtMost(orderedStops.lastIndex)
        for (i in currentIdx..upTo) {
            val st = orderedStops[i]
            val live = realtimeRepo.getArrivalForTripAtStop(tripId, st.stopId) ?: continue
            val planned = scheduledEpoch(st.arrivalTime) ?: continue
            return (live - planned).toInt()
        }
        return null
    }

    /**
     * Converts a GTFS "HH:MM:SS" into an epoch second for today. GTFS allows
     * hours ≥ 24 for trips running past midnight (e.g. "25:10:00" = 01:10 the
     * next day), which plain time parsing would reject.
     */
    private fun scheduledEpoch(hhmmss: String): Long? = try {
        val parts = hhmmss.split(":")
        val h = parts[0].toInt()
        val m = parts[1].toInt()
        val sec = parts.getOrNull(2)?.toIntOrNull() ?: 0
        val midnight = java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toEpochSecond()
        midnight + h * 3600L + m * 60L + sec
    } catch (e: Exception) {
        FileLogger.w(TAG, "Bad schedule time '$hhmmss': ${e.message}")
        null
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
            distanceToNextMetres  = distance,
            destinationIdx        = destinationIdx,
            destinationEtaEpoch   = destinationEtaEpoch,
            etaSource             = etaSource
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
        // Engine selection: the app setting wins when set, otherwise we bind
        // to the system default from Settings → "Синтезиран говор". Picking a
        // different engine from TalkBack's is what lets both speak at once —
        // one engine serialises its clients, so sharing it means whoever
        // speaks second cuts off the first.
        createTts()
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
        etaJob?.cancel()
        serviceScope.cancel()
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
