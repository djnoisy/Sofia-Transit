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
    @Inject lateinit var vehicleMatcher: bg.sofia.transit.data.repository.VehicleMatcher
    @Inject lateinit var gtfsRepo: bg.sofia.transit.data.repository.GtfsRepository
    @Inject lateinit var settings: AppSettings

    /** Service-lifetime scope for the ETA polling loop. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "JourneyService"
        const val CHANNEL_ID = "journey_channel"
        const val NOTIF_ID   = 1001

        /**
         * "At the stop" radius, measured as the crow flies.
         *
         * Lowered from 60 m because straight-line distance shortcuts corners:
         * approaching the turn from Ал. Малинов into Цариградско шосе, the
         * bus came within 53 m of ДЪРЖАВНА ПЕЧАТНИЦА while still a couple of
         * hundred metres from it by road, and the stop was announced early.
         * A tighter radius costs nothing here — measured fixes arrive 60 per
         * minute with a maximum gap of one second, so even at 65 km/h the
         * zone spans four or five of them.
         */
        const val ARRIVAL_RADIUS = 45.0

        /** Hysteresis: we only count as "departed" beyond this, so GPS
         *  jitter at the stop cannot fire arrive/depart repeatedly. */
        const val DEPART_RADIUS = 90.0

        /** Upper bound for the "Наближава спирка X" warning distance. */
        const val APPROACH_RADIUS = 500.0

        /** Lower bound, for crawling traffic where speed says almost nothing. */
        const val APPROACH_MIN_RADIUS = 120.0

        /**
         * How much notice the warning aims to give, in seconds. Measured
         * warnings on line 213 came 13–16 s before arrival at boulevard speed,
         * which is short for signalling the driver and reaching the door.
         */
        const val APPROACH_TARGET_SECONDS = 20.0

        /**
         * Stops closer together than this get no approach warning: it would
         * arrive within seconds of leaving the previous stop.
         */
        const val MIN_SPACING_FOR_APPROACH = 400.0

        /**
         * On the first fix, a stop within this distance counts as "we are
         * already here", so no approach warning is given for it. Wider than
         * ARRIVAL_RADIUS because the very first fix is the least accurate one.
         */
        const val SNAP_SUPPRESS_APPROACH_RADIUS = 150.0

        /**
         * How much nearer to the following stop we must be before deciding a
         * stop is behind us. Guards the case of standing at the stop itself,
         * where the comparison is otherwise decided by GPS noise.
         */
        const val PASSED_STOP_MARGIN = 30.0

        /** Accuracy required to attach to a stop, eased over time. */
        private const val SNAP_ACCURACY_STRICT = 30.0f
        private const val SNAP_ACCURACY_MEDIUM = 70.0f
        private const val SNAP_ACCURACY_LOOSE  = 150.0f
        private const val SNAP_STEP1_MS = 10_000L
        private const val SNAP_STEP2_MS = 20_000L
        /** After this long without a usable fix the journey is abandoned. */
        private const val SNAP_GIVE_UP_MS = 3 * 60 * 1000L
        /** How often the "weak signal" notice repeats while waiting. */
        private const val WEAK_SIGNAL_NOTICE_MS = 30_000L

        /** How many stops ahead of the current one we scan on each fix.
         *  Covers up to that many consecutively missed stops in one gap. */
        private const val LOOKAHEAD = 3

        /**
         * Distance at which "Слизате тук" is announced — the same radius as a
         * normal arrival, deliberately.
         *
         * They were separate while the arrival radius was 60 m and this one
         * matched it by coincidence. Lowering arrival to 45 m silently
         * reversed the order: the instruction to alight arrived before the
         * stop it referred to had been named. One value keeps "Спирка, X"
         * first and "Слизате тук" straight after it, which is the order
         * agreed.
         */
        const val ALIGHT_ANNOUNCE_RADIUS = ARRIVAL_RADIUS

        /** If the vehicle makes no forward progress for this long, the
         *  journey is assumed over (user forgot to stop tracking). */
        private const val INACTIVITY_TIMEOUT_MS = 10 * 60 * 1000L

        /** How often we confirm which vehicle we are in. */
        private const val VEHICLE_CHECK_INTERVAL_MS = 60_000L

        /**
         * How often the arrival prediction at the alighting stop is refreshed.
         *
         * Each poll downloads the entire trip-updates feed — some 725 KB — to
         * read a single number, so a ten-minute ride cost about seven
         * megabytes at one-minute polling.
         *
         * Two minutes costs the display nothing, because the figure shown is
         * not the polled value: the poll stores an absolute arrival time, and
         * the screen recomputes the remaining minutes against the clock on
         * every GPS fix, once a second. The countdown therefore runs smoothly
         * between polls; what a poll does is correct it for traffic.
         */
        private const val ETA_POLL_INTERVAL_MS = 120_000L

        /**
         * Grace period between a journey ending and the service stopping, so
         * queued speech can finish before the engine is shut down.
         */
        private const val TEARDOWN_DELAY_MS = 10_000L
        /** Grace period after boarding, so the first GPS fixes can arrive. */
        private const val FIRST_VEHICLE_CHECK_DELAY_MS = 30_000L

        /** How long the faster early checking lasts. */
        private const val EARLY_PHASE_MS = 4 * 60 * 1000L
        /** Interval during that early phase. */
        private const val EARLY_CHECK_INTERVAL_MS = 30_000L
        /** Within this, we and the vehicle count as travelling together. */
        private const val SAME_VEHICLE_RADIUS = 150.0
        /** Consecutive far readings before we accept the passenger has left. */
        private const val DIVERGENCE_STREAK_LIMIT = 3

        /**
         * A stop can only be treated as passed while we are this close to the
         * one we are catching up to. Without the bound, distance comparisons
         * alone declare stops passed from kilometres away.
         */
        const val CATCHUP_MAX_DISTANCE = 250.0

        /** Within this of the tracked vehicle, we are unmistakably in it. */
        private const val RIDING_TOGETHER_RADIUS = 60.0

        /**
         * Beyond this the vehicle has plainly gone on without us. Wider than
         * SAME_VEHICLE_RADIUS so that a single imprecise position cannot end a
         * journey, yet close enough to notice within a stop's distance.
         */
        private const val PARTED_RADIUS = 250.0

        /** Consecutive checks naming the same other line before we speak. */
        /**
         * Three rather than two. On a shared corridor in slow traffic, two
         * vehicles can travel abreast for a minute or more, so two sightings
         * are attainable by coincidence; a third, with the count only ever
         * advancing while moving, is not.
         */
        private const val FOREIGN_STREAK_LIMIT = 3

        /**
         * Sightings older than this no longer count towards the total, so the
         * evidence has to come from one continuous stretch of the journey
         * rather than from scattered moments.
         */
        private const val FOREIGN_SIGHTING_WINDOW_MS = 4 * 60 * 1000L

        /**
         * Below this we are standing, not riding, so the wrong-line check is
         * skipped. Matches the threshold used for deciding direction: a
         * pedestrian and a stationary passenger both fall under it, while a
         * vehicle in traffic stays above.
         */
        private const val MIN_SPEED_FOR_FOREIGN_CHECK = 10.0

        /**
         * How far the vehicle must travel before the direction test is even
         * attempted. Lowered from 150 m once the test itself became fine
         * grained: the old index comparison needed hundreds of metres to
         * register anything, whereas closing distance is measurable almost at
         * once. The speed guard already excludes a stationary vehicle, so this
         * only has to clear ordinary scatter.
         */
        private const val DIRECTION_MIN_MOVEMENT = 80.0

        /**
         * How much closer to its next stop a direction must have come, and by
         * how much it must beat the other, before the direction is called.
         * Comfortably above the scatter of a good fix.
         */
        private const val DIRECTION_MIN_PROGRESS = 60.0

        /** Minimum spacing between ambiguity diagnostics. */
        private const val AMBIGUITY_LOG_INTERVAL_MS = 10_000L

        /** Speed samples averaged; at one fix a second this is ~15 seconds. */
        private const val SPEED_SAMPLE_COUNT = 15

        /** Readings above this are discarded as GPS error (90 km/h). */
        private const val MAX_PLAUSIBLE_SPEED_MPS = 25.0

        /** Fixes vaguer than this take no part in deciding the direction. */
        private const val MAX_FIX_ACCURACY = 50.0f

        /** Time the receiver is given to settle before the anchor is taken. */
        private const val DIRECTION_SETTLE_MS = 5_000L

        /**
         * Below this the movement is a pedestrian's, not a vehicle's. Set at
         * 10 rather than 15 km/h because the average covers the last fifteen
         * seconds, which still include the standing still before departure —
         * and because traffic in the centre measured 14 km/h on line 76, so a
         * higher bar could leave the direction unresolved in a jam.
         */
        private const val MIN_SPEED_FOR_DIRECTION = 10.0

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
            /**
             * True while the direction of travel is still being worked out
             * from the vehicle's movement. Nothing can be announced yet: the
             * stop order itself depends on which way we are going.
             */
            val determiningDirection: Boolean = false,
            /**
             * Accuracy of the latest fix in metres, or null when unknown.
             * Shown throughout the journey: while waiting to attach it
             * explains what is being waited for, and afterwards it tells the
             * passenger how much to trust what they hear.
             */
            val fixAccuracyMetres: Int? = null,
            /** Current speed in km/h, or null before enough samples. */
            val speedKmh: Int? = null,
            /** True while waiting for a fix good enough to attach to a stop. */
            val awaitingAccurateFix: Boolean = false,
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
    /** When the wait for an accurate enough fix began. */
    private var snapWaitStartedMs = 0L
    /** Last time the "weak signal" notice was spoken. */
    private var lastWeakSignalNoticeMs = 0L
    /** Accuracy of the most recent fix, metres. */
    private var lastAccuracy: Float? = null

    /** Chosen alighting stop; null until the user picks one. */
    private var destinationIdx: Int? = null
    /** Which of the "2 stops / 1 stop / next stop" warnings already fired. */
    private val alightWarningsFired = mutableSetOf<Int>()
    /** Guards the "Слизате тук" announcement so it is spoken once. */
    private var alightAnnounced = false
    /** Timestamp of the last forward progress, for the inactivity timeout. */
    private var lastProgressMs = System.currentTimeMillis()

    /**
     * True once the journey has actually advanced past its first stop.
     *
     * Until then the passenger is still waiting to board, and standing beside
     * one stop looks exactly like the "no progress" the inactivity timer
     * watches for. Without this the timer would end a journey started ten
     * minutes before the bus arrives — precisely when the user needs it.
     */
    private var hasStartedMoving = false

    /** trip_id being ridden — needed to query its real-time predictions. */
    private var tripId: String = ""
    /**
     * True while [orderedStops] really belongs to [tripId].
     *
     * It stops being true after a mid-journey re-match: the stop list is kept
     * from the original trip, so its scheduled times belong to that run, not
     * to the vehicle now being ridden. Two runs of one line can depart twenty
     * minutes apart, so the timetable tiers must be skipped until the lists
     * agree again — the live prediction, which is keyed by trip, stays valid.
     */
    private var stopsMatchTrip = false

    /** Latest real-time ETA at the destination stop, epoch seconds. */
    private var destinationEtaEpoch: Long? = null
    private var etaSource: EtaSource = EtaSource.NONE
    private var etaJob: Job? = null

    /** One possible direction, until movement shows which one we are on. */
    data class DirectionCandidate(
        val headsign: String,
        val stops: List<StopWithSequence>,
        val latLons: List<Pair<Double, Double>>
    )

    /** Non-empty while the direction is being determined. */
    private var candidates: List<DirectionCandidate> = emptyList()
    /** Position where determination began, and the stop indices there. */
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorIdx: List<Int> = emptyList()
    private var determinationStartMs = 0L
    private var lastAmbiguityLogMs = 0L

    /** Route and direction being ridden, for re-matching the vehicle. */
    private var routeId = ""
    private var headsign = ""
    /** Last GPS fix, so background checks can use the passenger's position. */
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var vehicleJob: Job? = null
    /**
     * Consecutive checks where the tracked vehicle was far from us. Acted on
     * only after several in a row: one stale or jumpy position must never end
     * a journey by itself.
     */
    private var divergenceStreak = 0

    /** Set once we have concluded the vehicle went on without us. */
    private var partedFromVehicle = false

    /**
     * The other line we appear to be riding, and how many checks in a row it
     * has looked that way. Two are required: at a stop, vehicles of several
     * lines pass within metres, and one snapshot would accuse the rider of
     * boarding the wrong bus every time another pulls alongside.
     */
    private var foreignRouteId: String? = null
    private var foreignStreak = 0
    /** When the previous foreign sighting was recorded. */
    private var lastForeignSightingMs = 0L

    private lateinit var fusedClient: FusedLocationProviderClient

    /**
     * Held for the duration of a journey. Without it the CPU suspends
     * between GPS callbacks once the screen goes off, so fixes arrive
     * batched and late — announcements then lag by tens of seconds or are
     * skipped entirely, which is exactly the failure the user reported.
     * A foreground service alone does not prevent this; it only prevents
     * the process being killed.
     */
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    /**
     * Recent speed samples, in metres per second, for sizing the approach
     * warning by speed rather than by a fixed distance. Measurements on line
     * 76 showed 14 km/h in the centre and 58 km/h on the boulevard, so one
     * distance cannot serve both: 300 m is 77 seconds of warning at the
     * former and 19 at the latter. Recorded now so the thresholds can be set
     * from real figures instead of guesses.
     */
    private val speedSamples = ArrayDeque<Double>()

    /** Timestamp of the previous GPS fix, for measuring update cadence. */
    private var lastFixMs = 0L
    private var fixCount = 0
    private var maxGapMs = 0L
    private var lastCadenceLogMs = 0L

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
        routeId: String,
        headsign: String,
        stops: List<StopWithSequence>,
        latLons: List<Pair<Double, Double>>,
        boardingStopIdx: Int
    ) {
        this.tripId       = tripId
        this.routeId      = routeId
        this.headsign     = headsign
        stopsMatchTrip    = tripId.isNotBlank()
        divergenceStreak  = 0
        partedFromVehicle = false
        foreignRouteId    = null
        foreignStreak     = 0
        lastForeignSightingMs = 0L
        hasStartedMoving  = false
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

        lastFixMs = 0L
        fixCount = 0
        maxGapMs = 0L
        lastCadenceLogMs = 0L
        speedSamples.clear()
        snapWaitStartedMs = 0L
        lastWeakSignalNoticeMs = 0L
        lastAccuracy = null

        publish(distance = null)
        acquireWakeLock()
        startLocUpdates()
        startVehicleTracking()
        announce("Следене на пътуването започна.")
    }

    /**
     * Starts tracking without knowing the direction yet, working it out from
     * how the vehicle moves.
     *
     * Deliberately based on our own displacement rather than on a nearby
     * vehicle: at a stop, and while two vehicles pass each other, the closest
     * vehicle is often the one going the other way. Our own movement cannot
     * mislead in that manner — if the stop we are approaching comes later in
     * direction A's order and earlier in B's, we are travelling A.
     *
     * Nothing is announced until the direction is settled, because the order
     * of the stops — and therefore which one is "next" — depends on it.
     */
    fun beginJourneyAuto(
        label: String,
        routeId: String,
        candidates: List<DirectionCandidate>
    ) {
        this.routeId      = routeId
        this.candidates   = candidates
        tripId            = ""
        stopsMatchTrip    = false
        headsign          = ""
        routeLabel        = label
        orderedStops      = emptyList()
        stopLatLon        = emptyList()
        currentIdx        = 0
        atStop            = false
        approachAnnounced = false
        awaitingFirstFix  = true
        destinationIdx    = null
        destinationEtaEpoch = null
        etaSource         = EtaSource.NONE
        alightWarningsFired.clear()
        alightAnnounced   = false
        divergenceStreak  = 0
        partedFromVehicle = false
        foreignRouteId    = null
        foreignStreak     = 0
        lastForeignSightingMs = 0L
        hasStartedMoving  = false
        lastProgressMs    = System.currentTimeMillis()
        determinationStartMs = System.currentTimeMillis()
        lastAmbiguityLogMs = 0L
        anchorLat = 0.0; anchorLon = 0.0; anchorIdx = emptyList()

        lastFixMs = 0L; fixCount = 0; maxGapMs = 0L; lastCadenceLogMs = 0L
        speedSamples.clear()

        publish(distance = null)
        acquireWakeLock()
        startLocUpdates()
        announce("Изчаква се определяне на посоката.")
    }

    /**
     * Decides the direction once we have moved far enough for the answer to
     * be unambiguous. Returns true when settled.
     *
     * The test is which candidate's stop order we are advancing through:
     * between the anchor position and now, the index of the nearest stop
     * rises in the direction we are travelling and falls in the other.
     */
    private fun tryResolveDirection(loc: Location): Boolean {
        if (candidates.isEmpty()) return true

        // Guard 1 — accuracy. The first fixes after a cold start can be
        // hundreds of metres out, especially between buildings. Such a fix
        // would either look like movement while standing still, or anchor the
        // comparison at a place we were never at. Anything vaguer than
        // MAX_FIX_ACCURACY is ignored outright.
        if (loc.hasAccuracy() && loc.accuracy > MAX_FIX_ACCURACY) {
            FileLogger.d(TAG, "Direction: skipping fix, accuracy ${loc.accuracy.toInt()} m")
            checkDirectionTimeout()
            return false
        }

        // Guard 2 — settling time. Even accurate-looking early fixes drift, so
        // the anchor is taken only after the receiver has been reporting for a
        // few seconds.
        val sinceStart = System.currentTimeMillis() - determinationStartMs
        if (sinceStart < DIRECTION_SETTLE_MS) return false

        if (anchorIdx.isEmpty()) {
            anchorLat = loc.latitude
            anchorLon = loc.longitude
            anchorIdx = candidates.map { nearestIdx(it, loc.latitude, loc.longitude) }
            FileLogger.i(TAG, "Direction: anchored after ${sinceStart / 1000}s — " +
                describeIndices(anchorIdx, loc))
            return false
        }

        // Guard 3 — speed. Walking to the far end of the stop, or to a shop
        // and back, can accumulate the required displacement while the vehicle
        // has not moved at all; the direction would then be read off the
        // pedestrian. A bus pulling away exceeds this within seconds, and a
        // jumping fix produces no sustained speed.
        val kmh = recentSpeedKmh()
        if (kmh == null || kmh < MIN_SPEED_FOR_DIRECTION) {
            checkDirectionTimeout()
            return false
        }

        val moved = LocationHelper.distanceMetres(
            anchorLat, anchorLon, loc.latitude, loc.longitude)
        if (moved < DIRECTION_MIN_MOVEMENT) {
            checkDirectionTimeout()
            return false
        }

        // Which direction are we making progress along?
        //
        // The test used to be whether the index of the nearest stop had
        // increased. That index only changes once the vehicle passes the
        // midpoint between two stops, so with stops 700 m apart it stood
        // still for over two minutes while the answer was plainly visible in
        // the data: on one ride the distance to the next stop ahead fell from
        // 91 m to 79 m within eighteen seconds, while in the other direction
        // it rose from 278 m to 430 m.
        //
        // So the decision is made on that distance instead. For each
        // direction we take the stop that follows the one we anchored at, and
        // ask whether we are getting closer to it. Travelling a route means
        // closing on its next stop; travelling the opposite way means leaving
        // it behind. The measure changes with every metre rather than every
        // few hundred, so the answer arrives in seconds.
        val nowIdx = candidates.map { nearestIdx(it, loc.latitude, loc.longitude) }

        val progress = candidates.indices.map { i ->
            val c = candidates[i]
            val nextIdx = (anchorIdx[i] + 1).coerceAtMost(c.latLons.lastIndex)
            val (lat, lon) = c.latLons.getOrNull(nextIdx) ?: return@map 0.0
            val atAnchor = LocationHelper.distanceMetres(anchorLat, anchorLon, lat, lon)
            val atNow    = LocationHelper.distanceMetres(loc.latitude, loc.longitude, lat, lon)
            atAnchor - atNow          // positive = closing in
        }

        // One direction must be closing while the other is not, by a margin
        // wide enough that GPS scatter cannot produce it.
        val best = progress.indices.maxByOrNull { progress[it] } ?: return false
        val others = progress.indices.filter { it != best }
        val decisive = progress[best] >= DIRECTION_MIN_PROGRESS &&
            others.all { progress[best] - progress[it] >= DIRECTION_MIN_PROGRESS }

        if (!decisive) {
            val now = System.currentTimeMillis()
            if (now - lastAmbiguityLogMs >= AMBIGUITY_LOG_INTERVAL_MS) {
                lastAmbiguityLogMs = now
                FileLogger.i(TAG, "Direction unclear after ${moved.toInt()} m — " +
                    "progress=" + progress.mapIndexed { i, v ->
                        "${candidates[i].headsign.take(14)}:${v.toInt()}m"
                    }.joinToString(", ") +
                    " | now: ${describeIndices(nowIdx, loc)}")
            }
            return false
        }

        val advanced = listOf(best)

        val chosen = candidates[advanced.first()]
        headsign     = chosen.headsign
        orderedStops = chosen.stops
        stopLatLon   = chosen.latLons
        candidates   = emptyList()
        awaitingFirstFix = true      // snap to the right stop in this order
        routeLabel = "$routeLabel → ${chosen.headsign}"

        FileLogger.i(TAG, "Direction resolved after ${moved.toInt()} m " +
            "at ${kmh.toInt()} km/h: ${chosen.headsign}")
        announce("Посока, ${chosen.headsign}.")
        startVehicleTracking()
        return true
    }

    /**
     * Ends the journey if the direction has stayed unknown for too long.
     * Shared by every branch that gives up on this fix, so the timeout cannot
     * be skipped by whichever guard happens to reject the fix.
     */
    private fun checkDirectionTimeout() {
        if (System.currentTimeMillis() - determinationStartMs <= INACTIVITY_TIMEOUT_MS) return
        announce("Посоката не беше определена. Следенето се прекратява.")
        _events.tryEmit(JourneyEvent.RouteEnded)
        endJourney()
    }

    /**
     * Renders one index per candidate as "headsign #idx name (dist)", for the
     * direction diagnostics. [loc] may be null, in which case distances are
     * omitted — used for the anchor, whose position is no longer current.
     */
    private fun describeIndices(indices: List<Int>, loc: Location?): String =
        candidates.mapIndexed { i, c ->
            val idx = indices.getOrNull(i) ?: -1
            val name = c.stops.getOrNull(idx)?.stopName ?: "?"
            val dist = if (loc == null) "" else {
                val (lat, lon) = c.latLons.getOrNull(idx) ?: Pair(0.0, 0.0)
                " ${LocationHelper.distanceMetres(loc.latitude, loc.longitude, lat, lon).toInt()}m"
            }
            "${c.headsign.take(14)} #$idx $name$dist"
        }.joinToString(" || ")

    /**
     * Whether the current fix is precise enough to choose a stop, easing the
     * requirement the longer we wait:
     *   first 10 s — under 30 m, which makes the choice certain;
     *   to 20 s    — under 70 m, still well inside typical stop spacing;
     *   afterwards — under 150 m, below which the nearest stop is still
     *                meaningful for stops a few hundred metres apart.
     *
     * The bar is not lowered further: with worse accuracy the choice would be
     * a guess. If it is still not met after [SNAP_GIVE_UP_MS] the journey is
     * ended, because tracking that cannot place the passenger on the route
     * announces nothing useful and would merely drain the battery.
     */
    private fun accuracyGoodEnoughToSnap(): Boolean {
        val acc = lastAccuracy ?: return true      // no figure: proceed
        val now = System.currentTimeMillis()
        if (snapWaitStartedMs == 0L) snapWaitStartedMs = now
        val waited = now - snapWaitStartedMs

        val required = when {
            waited < SNAP_STEP1_MS -> SNAP_ACCURACY_STRICT
            waited < SNAP_STEP2_MS -> SNAP_ACCURACY_MEDIUM
            else                   -> SNAP_ACCURACY_LOOSE
        }
        if (acc <= required) return true

        if (waited >= SNAP_GIVE_UP_MS) {
            announce("Няма достатъчно точен сигнал. Следенето се прекратява.")
            _events.tryEmit(JourneyEvent.RouteEnded)
            endJourney()
            return false
        }

        // Spoken every 30 s so the silence is explained rather than looking
        // like a failure — but never at once. Accuracy is usually poor for
        // the first seconds after the receiver wakes and settles by itself,
        // and announcing "weak signal" immediately would alarm the passenger
        // about something that resolves before they can react.
        val sinceLastNotice =
            if (lastWeakSignalNoticeMs == 0L) waited
            else now - lastWeakSignalNoticeMs
        if (sinceLastNotice >= WEAK_SIGNAL_NOTICE_MS) {
            lastWeakSignalNoticeMs = now
            announce("Определяне на местоположението.")
        }
        return false
    }

    /** Index of the stop nearest the given position within a candidate. */
    private fun nearestIdx(c: DirectionCandidate, lat: Double, lon: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        c.latLons.forEachIndexed { i, (sLat, sLon) ->
            val d = LocationHelper.distanceMetres(lat, lon, sLat, sLon)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
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
        releaseWakeLock()

        // Every background job must stop HERE, not merely in onDestroy().
        // stopSelf() only asks the system to stop the service, and the system
        // waits for bound clients to unbind — so onDestroy can be minutes
        // away. The ETA poll was left running through that window: it
        // re-downloaded the whole trip-updates feed (over half a megabyte)
        // every 30 seconds long after the journey had ended, and kept the
        // service scope alive so the service never really finished.
        vehicleJob?.cancel()
        vehicleJob = null
        etaJob?.cancel()
        etaJob = null

        orderedStops = emptyList()
        stopLatLon   = emptyList()
        currentIdx   = 0
        atStop       = false
        destinationIdx = null
        destinationEtaEpoch = null
        etaSource = EtaSource.NONE
        alightWarningsFired.clear()
        alightAnnounced = false
        awaitingFirstFix = true
        divergenceStreak = 0
        partedFromVehicle = false
        foreignRouteId = null
        foreignStreak = 0
        lastForeignSightingMs = 0L
        candidates = emptyList()
        anchorIdx = emptyList()
        stopsMatchTrip = false
        tripId = ""
        routeId = ""
        headsign = ""
        _trackingState.value = TrackingState.Idle

        // Idle is published at once so the UI is correct immediately, but the
        // service itself lingers briefly: onDestroy shuts down the speech
        // engine, and the last announcements ("Слизате тук", "Крайна спирка")
        // are queued at the very moment the journey ends. Stopping straight
        // away would cut them off mid-sentence — which used not to happen only
        // because a bound client accidentally kept the service alive.
        android.os.Handler(mainLooper).postDelayed({
            // A new journey may have begun during the grace period — the user
            // ending one trip and immediately starting another is ordinary.
            // Stopping then would kill the new journey seconds after it began.
            if (_trackingState.value is TrackingState.Idle) {
                stopSelf()
            } else {
                FileLogger.i(TAG, "New journey started; teardown cancelled")
            }
        }, TEARDOWN_DELAY_MS)
    }

    // ── Progress engine ───────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        val prevLat = lastLat
        val prevLon = lastLon
        val gapMs = if (lastFixMs == 0L) 0L else System.currentTimeMillis() - lastFixMs

        lastAccuracy = if (loc.hasAccuracy()) loc.accuracy else null

        logFixCadence()
        recordSpeed(loc, prevLat, prevLon, gapMs)

        lastLat = loc.latitude
        lastLon = loc.longitude

        // Direction not settled yet: nothing else can run, because the stop
        // order it all depends on is not known.
        if (candidates.isNotEmpty()) {
            if (!tryResolveDirection(loc)) {
                publish(distance = null)
                return
            }
        }

        if (orderedStops.isEmpty()) return

        val idxBefore = currentIdx

        // On the very first fix, snap to the route: among ALL stops from the
        // boarding index onward, pick the nearest. This is what makes
        // starting mid-journey work — if the user began tracking three stops
        // after boarding, we land on the right stop immediately instead of
        // announcing ancient history.
        if (awaitingFirstFix) {
            // Attaching to the wrong stop is not self-correcting: the choice
            // is locked in and the first thing the passenger hears may name a
            // stop they have long passed. With an accuracy of, say, 500 m,
            // several stops fall inside the circle of possible positions and
            // "the nearest" becomes arbitrary — so the requirement is
            // relaxed in steps rather than accepting whatever arrives first.
            if (!accuracyGoodEnoughToSnap()) {
                publish(distance = null)
                return
            }
            awaitingFirstFix = false
            snapWaitStartedMs = 0L
            var best = currentIdx
            var bestDist = Double.MAX_VALUE
            for (i in currentIdx..orderedStops.lastIndex) {
                val d = distTo(loc, i)
                if (d < bestDist) { bestDist = d; best = i }
            }

            // The nearest stop may already be behind us. Starting tracking
            // between two stops, the one just left can easily be the closer of
            // the two, and announcing it as "next" is wrong — for the journey
            // it is done with.
            //
            // Test: are we nearer to the stop AFTER the candidate than the
            // candidate itself is? If so we lie beyond it along the route and
            // have passed it. The margin keeps a rider standing AT the stop,
            // where the two distances are nearly equal, from being pushed
            // forward by GPS scatter.
            if (best < orderedStops.lastIndex) {
                val toNext = distTo(loc, best + 1)
                val (bLat, bLon) = stopLatLon.getOrNull(best) ?: Pair(0.0, 0.0)
                val (nLat, nLon) = stopLatLon.getOrNull(best + 1) ?: Pair(0.0, 0.0)
                val segment = LocationHelper.distanceMetres(bLat, bLon, nLat, nLon)
                if (segment > 0 && toNext < segment - PASSED_STOP_MARGIN) {
                    FileLogger.i(TAG, "First fix: ${orderedStops[best].stopName} " +
                        "already passed (${toNext.toInt()} m to next of ${segment.toInt()} m)")
                    best += 1
                    bestDist = toNext
                }
            }

            currentIdx = best

            // Suppress the approach warning for the stop we just snapped to
            // when we are already at it. Standing at a stop, the first fix is
            // often tens of metres out — enough to fall outside the arrival
            // radius but inside the approach one — so tracking would open with
            // "Наближава спирка X" and then, once the position settled a
            // couple of seconds later, "Спирка X". The rider is standing there
            // and needs no warning about it. Beyond this distance the stop is
            // genuinely ahead and the warning is left to fire normally.
            if (bestDist <= SNAP_SUPPRESS_APPROACH_RADIUS) {
                approachAnnounced = true
            }

            // Say which stop is coming, unless we are standing at it — in
            // which case the arrival announcement follows within seconds
            // anyway. Without this the passenger could be left in silence
            // right after tracking begins: the approach warning may be
            // switched off, and then nothing at all is spoken until the stop
            // is reached. It matters most after switching lines mid-journey,
            // where the route has just changed under them.
            if (bestDist > ARRIVAL_RADIUS) {
                announce("Следваща спирка, ${orderedStops[best].stopName}.")
            }

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
                    FileLogger.i(TAG, "ARRIVE at ${nearestDist.toInt()} m, " +
                        "speed ${recentSpeedKmh()?.toInt() ?: -1} km/h → " +
                        orderedStops[nearest].stopName)
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
                    suppressRedundantApproach(loc)
                }
                // No terminus case here any more — arriving at the final stop
                // already ended the journey above.
            }

            // ── Moving; did we silently pass the current stop? ────────────
            // Catching up on stops the GPS never saw inside their radius.
            // Requires being genuinely NEAR the stop we are catching up to:
            // comparing distances alone is not enough, because a route that
            // bends can put a later stop closer in a straight line than the
            // next one. Leaving Хотел Плиска, Военна академия lies 2212 m
            // away while Орлов мост — the actual next stop — lies 2355 m, so
            // the tracker announced Орлов мост as passed while still two
            // kilometres short of it.
            !atStop && nearest > currentIdx
                    && nearestDist <= CATCHUP_MAX_DISTANCE
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
                suppressRedundantApproach(loc)
            }

            // ── Approaching warning ───────────────────────────────────────
            !atStop && !approachAnnounced
                    && approachRadiusFor(currentIdx)
                        ?.let { distTo(loc, currentIdx) <= it } == true -> {
                approachAnnounced = true
                // Logged with speed and distance so the warning's lead time
                // can be worked out afterwards from the log alone.
                FileLogger.i(TAG, "APPROACH at ${distTo(loc, currentIdx).toInt()} m, " +
                    "speed ${recentSpeedKmh()?.toInt() ?: -1} km/h → " +
                    orderedStops[currentIdx].stopName)
                announce("Наближава спирка, ${orderedStops[currentIdx].stopName}.")
            }
        }

        // ── Destination handling ─────────────────────────────────────────
        val dest = destinationIdx
        if (dest != null) {

            val destDist = distTo(loc, dest)

            // 1a) Announce at 60 m — early enough to signal the driver and
            //     reach the door.
            //
            //     The stop name is NOT repeated here: the ordinary "Спирка,
            //     X" announcement fires from the same fix, so saying the name
            //     again made it the third mention within seconds. Keeping the
            //     familiar arrival phrasing intact and adding a bare
            //     instruction after it is both shorter and clearer.
            if (destDist <= ALIGHT_ANNOUNCE_RADIUS && !alightAnnounced) {
                alightAnnounced = true
                announce("Слизате тук.")
            }

            // The journey is NOT ended by distance here any more.
            //
            // A vehicle stops at the stop whether or not this passenger gets
            // off, so ending at 30 m cut the journey short for anyone who
            // chose to stay aboard — and the safety net for a missed stop
            // could never run, because the end came first. Getting off is
            // instead recognised generally: the vehicle pulls away and we do
            // not. See checkVehicle.

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
            hasStartedMoving = true
        } else if (hasStartedMoving &&
                   System.currentTimeMillis() - lastProgressMs > INACTIVITY_TIMEOUT_MS) {
            // Only after the journey has actually begun: see hasStartedMoving.
            FileLogger.i(TAG, "No progress for 10 min — ending journey automatically")
            announce("Няма движение по маршрута. Следенето е спряно.")
            _events.tryEmit(JourneyEvent.RouteEnded)
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
            // No name here: "Следваща спирка, X" has just been announced from
            // the same fix, so repeating X would be the second mention in one
            // breath.
            0 -> announce("Слизате на следващата спирка.")
        }
    }

    /** Re-emits state using the last known distance (for non-GPS changes,
     *  e.g. the user picking a destination). */
    private fun publishLastKnown() {
        val prev = _trackingState.value as? TrackingState.Tracking
        publish(distance = prev?.distanceToNextMetres)
    }

    /**
     * Periodically confirms which vehicle we are actually in, and notices
     * when we have left it.
     *
     * Two jobs, one loop:
     *
     *  - Re-matching. The vehicle chosen at boarding may not be the one we
     *    got on. If another vehicle of the same line and direction is
     *    consistently the nearest, we switch to its trip silently: the stop
     *    list is identical for a given direction, so nothing the passenger
     *    hears changes — only the arrival prediction and, for shortened depot
     *    runs, the stop list itself become correct.
     *
     *  - Noticing that the passenger has got off. While riding, we and the
     *    vehicle move together and the gap stays small. Once they alight, the
     *    vehicle drives away and the gap grows and keeps growing. That is a
     *    far better signal than the inactivity timer, which has to wait ten
     *    minutes. Requires several consecutive readings, because a single
     *    stale position must not end a journey.
     */
    private fun startVehicleTracking() {
        vehicleJob?.cancel()
        if (routeId.isBlank()) return

        vehicleJob = serviceScope.launch {
            // Let the first GPS fixes arrive before the first check.
            delay(FIRST_VEHICLE_CHECK_DELAY_MS)
            val startedAt = System.currentTimeMillis()
            while (isActive) {
                try {
                    checkVehicle()
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Vehicle check failed: ${e.message}")
                }
                // Faster early on. Boarding the wrong line is only worth
                // catching while few stops have passed, so the first minutes
                // are checked twice as often; afterwards the slower rate is
                // enough for noticing that the rider has got off.
                val early = System.currentTimeMillis() - startedAt < EARLY_PHASE_MS
                delay(if (early) EARLY_CHECK_INTERVAL_MS else VEHICLE_CHECK_INTERVAL_MS)
            }
        }
    }

    private suspend fun checkVehicle() {
        if (lastLat == 0.0 && lastLon == 0.0) return

        val distance = vehicleMatcher.distanceToTrackedVehicle(tripId, lastLat, lastLon)

        // Riding the wrong line is the one finding that must come quickly —
        // it is only recoverable while few stops have passed. But it is not
        // even asked when our own vehicle is right here: at that range we are
        // plainly aboard it, and another line's vehicle alongside is just
        // traffic.
        val definitelyAboard = distance != null && distance <= RIDING_TOGETHER_RADIUS
        if (!definitelyAboard && checkForeignVehicle()) return
        if (definitelyAboard) {
            foreignRouteId = null
            foreignStreak = 0
        }

        if (distance != null && distance <= SAME_VEHICLE_RADIUS) {
            // We are where our vehicle is: nothing to do.
            divergenceStreak = 0
            partedFromVehicle = false
            return
        }


        // Either the feed has nothing for this trip, or the vehicle is far
        // away. Before concluding anything, see whether some other vehicle of
        // the same line and direction is right here — that would mean we
        // boarded a different one.
        val match = vehicleMatcher.findVehicle(
            routeId, headsign.ifBlank { null }, lastLat, lastLon)

        if (match != null && match.unambiguous && match.vehicle.tripId != tripId) {
            FileLogger.i(TAG, "Re-matched to trip=${match.vehicle.tripId} " +
                "(${match.distanceMetres.toInt()} m); previous was " +
                "${distance?.toInt() ?: -1} m away")
            tripId = match.vehicle.tripId
            // The stop list still describes the previous trip.
            stopsMatchTrip = false
            divergenceStreak = 0
            // The stop list can differ on a shortened run, so re-check that
            // the chosen alighting stop is still on the route.
            verifyDestinationStillReachable()
            restartEtaPolling()
            return
        }

        // Only now, with no other vehicle of this line beside us, may the
        // distance mean what it looks like: the vehicle went on without us.
        //
        // The order matters. Boarding the NEXT vehicle of the same line also
        // leaves the tracked one far behind, and concluding "you got off"
        // there would end a journey that is just beginning. The re-match above
        // covers that case first.
        //
        // Recognised identically wherever it happens — at the chosen
        // alighting stop, at any other stop, or between them — because the
        // meaning is the same: this passenger has stopped travelling. It also
        // replaces ending the journey by distance at the destination, which
        // used to cut short anyone who decided to stay aboard.
        if (distance != null && distance > PARTED_RADIUS && !partedFromVehicle) {
            partedFromVehicle = true
            FileLogger.i(TAG, "Vehicle left without us (${distance.toInt()} m)")
            val reachedChosenStop = destinationIdx != null && alightAnnounced
            if (reachedChosenStop) {
                _events.tryEmit(JourneyEvent.DestinationReached)
            } else {
                announce("Изглежда слязохте. Следенето се прекратява.")
                _events.tryEmit(JourneyEvent.RouteEnded)
            }
            endJourney()
            return
        }

        if (distance == null) {
            // No data at all — not evidence of anything. The inactivity timer
            // remains the fallback for this case.
            return
        }

        divergenceStreak++
        FileLogger.i(TAG, "Vehicle ${distance.toInt()} m away " +
            "(streak $divergenceStreak/$DIVERGENCE_STREAK_LIMIT)")
        if (divergenceStreak >= DIVERGENCE_STREAK_LIMIT) {
            // Neutral wording: the same divergence occurs when the user
            // never boarded at all — the bus arrived, they let it go, and it
            // drove off. "Изглежда слязохте" was wrong in that case.
            announce("Връзката с превозното средство е изгубена. " +
                     "Следенето се прекратява.")
            _events.tryEmit(JourneyEvent.RouteEnded)
            endJourney()
        }
    }

    /**
     * Detects riding a vehicle of a different line. Returns true when it has
     * acted, so the caller stops.
     */
    private suspend fun checkForeignVehicle(): Boolean {
        // Only meaningful while actually moving. A vehicle of another line
        // standing beside us is the normal picture at a stop — both while
        // waiting to board and just after getting off — and calling that
        // "you are on the wrong bus" would be wrong in both cases. Being
        // aboard means moving together, so a near-zero speed rules the check
        // out entirely.
        val kmh = recentSpeedKmh()
        if (kmh == null || kmh < MIN_SPEED_FOR_FOREIGN_CHECK) {
            // Standing still is neither evidence for nor against: the check is
            // ignored outright and the count is left exactly as it was.
            //
            // Clearing it would make the requirement "three sightings with no
            // stop in between", which in city traffic depends on where the
            // lights happen to fall rather than on anything about the journey.
            // Counting it would be worse still: at a stop, vehicles of other
            // lines stand beside us as a matter of course.
            return false
        }

        val foreign = vehicleMatcher.findForeignVehicle(routeId, lastLat, lastLon)

        if (foreign == null) {
            // Nothing identifiable nearby is not evidence that the earlier
            // sightings were wrong. Clearing the count here was the main
            // reason the wrong line took nine minutes to report on one ride:
            // the count reached two, one check near a stop found the choice
            // ambiguous, and everything started again — while the vehicle in
            // question sat four metres away throughout.
            return false
        }

        // Sightings must belong to the same episode. Without this the count
        // could accumulate from unrelated moments far apart.
        val now = System.currentTimeMillis()
        if (foreignStreak > 0 && now - lastForeignSightingMs > FOREIGN_SIGHTING_WINDOW_MS) {
            FileLogger.i(TAG, "Foreign sightings expired; restarting count")
            foreignStreak = 0
            foreignRouteId = null
        }
        lastForeignSightingMs = now

        if (foreign.routeId == foreignRouteId) {
            foreignStreak++
        } else {
            foreignRouteId = foreign.routeId
            foreignStreak = 1
        }

        FileLogger.i(TAG, "Foreign vehicle streak $foreignStreak: " +
            "${foreign.routeShortName} at ${foreign.distanceMetres.toInt()} m")

        if (foreignStreak < FOREIGN_STREAK_LIMIT) return false

        // Switch to the line actually being ridden rather than stopping.
        //
        // Ending the journey here left the passenger with nothing at the
        // moment they most needed help — aboard an unfamiliar vehicle, having
        // to start again by hand. Everything required to continue is already
        // known: the vehicle, its trip, and from that its direction and stop
        // list. Only if any of it is missing do we fall back to stopping,
        // since announcing stops from the wrong route would be worse than
        // silence.
        if (switchToForeignLine(foreign)) return true

        announce("Изглежда пътувате с ${foreignVehicleWord(foreign)} " +
                 "${foreign.routeShortName}. Следенето се прекратява.")
        _events.tryEmit(JourneyEvent.RouteEnded)
        endJourney()
        return true
    }

    /** Vehicle word for a foreign line, lower case, for mid-sentence use. */
    private suspend fun foreignVehicleWord(
        foreign: bg.sofia.transit.data.repository.VehicleMatcher.ForeignVehicle
    ): String = try {
        bg.sofia.transit.util.VehicleLabels
            .singular(foreign.routeType, gtfsRepo.isTrolleyRoute(foreign.routeId))
            .lowercase()
    } catch (e: Exception) { "превозно средство" }

    /**
     * Re-points tracking at the line the passenger is actually on. Returns
     * false when the data needed to continue is incomplete, leaving the
     * caller to stop instead.
     */
    private suspend fun switchToForeignLine(
        foreign: bg.sofia.transit.data.repository.VehicleMatcher.ForeignVehicle
    ): Boolean {
        val hs = foreign.headsign ?: return false
        val stops = try {
            gtfsRepo.getRemainingStops(foreign.tripId, fromSequence = 0)
        } catch (e: Exception) {
            FileLogger.w(TAG, "Switch failed loading stops: ${e.message}")
            return false
        }
        if (stops.isEmpty()) return false

        val latLons = stops.map { sw ->
            val st = try { gtfsRepo.getStopById(sw.stopId) } catch (e: Exception) { null }
            Pair(st?.stopLat ?: 0.0, st?.stopLon ?: 0.0)
        }

        val vehicle = try {
            bg.sofia.transit.util.VehicleLabels.singular(
                foreign.routeType, gtfsRepo.isTrolleyRoute(foreign.routeId))
        } catch (e: Exception) { "Линия" }

        FileLogger.i(TAG, "Switching to ${foreign.routeShortName} → $hs " +
            "(trip=${foreign.tripId})")

        routeId        = foreign.routeId
        headsign       = hs
        tripId         = foreign.tripId
        orderedStops   = stops
        stopLatLon     = latLons
        stopsMatchTrip = true
        routeLabel     = "$vehicle ${foreign.routeShortName} → $hs"

        // Start afresh on the new route: our position along it is unknown, and
        // any chosen alighting stop belonged to the old one.
        awaitingFirstFix  = true
        atStop            = false
        approachAnnounced = false
        currentIdx        = 0
        destinationIdx    = null
        destinationEtaEpoch = null
        etaSource         = EtaSource.NONE
        alightWarningsFired.clear()
        alightAnnounced   = false
        divergenceStreak  = 0
        partedFromVehicle = false
        foreignRouteId    = null
        foreignStreak     = 0
        lastForeignSightingMs = 0L

        // "Автобус 76" rather than "линия 76": the passenger may well be on a
        // trolleybus or a tram, and naming the wrong kind of vehicle is both
        // wrong and confusing when it is the thing they are sitting in.
        announce("Изглежда пътувате с ${vehicle.lowercase()} " +
                 "${foreign.routeShortName}, посока $hs. " +
                 "Проследяването на спирките се превключва.")
        publishLastKnown()
        restartEtaPolling()
        return true
    }


    /**
     * After switching vehicles, the new trip may be a shortened run that never
     * reaches the chosen alighting stop. Silently keeping it would leave the
     * passenger waiting for an announcement that can never come.
     */
    private fun verifyDestinationStillReachable() {
        val dest = destinationIdx ?: return
        val destStop = orderedStops.getOrNull(dest) ?: return
        // orderedStops still describes the original trip; a mismatch shows up
        // as the destination no longer being ahead of us on this vehicle.
        if (dest < currentIdx) {
            announce("Това превозно средство не стига до избраната спирка " +
                     "за слизане.")
            destinationIdx = null
            alightWarningsFired.clear()
            alightAnnounced = false
            publishLastKnown()
            FileLogger.i(TAG, "Destination unreachable on new trip; cleared")
        } else {
            FileLogger.d(TAG, "Destination ${destStop.stopName} still ahead")
        }
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

        // Without an identified vehicle there is no arrival time to give.
        //
        // An earlier attempt showed the timetable here, reasoning that it does
        // not depend on the vehicle. That was wrong: with no vehicle the stop
        // list comes from a REPRESENTATIVE trip chosen only to describe the
        // route, and its times belong to that one run — often a five-in-the
        // morning departure. The result was "Пристигане: сега (по разписание)"
        // for every stop the rider picked. A blank row is honest; a wrong
        // time is not.
        if (tripId.isBlank()) {
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
            return
        }

        etaJob = serviceScope.launch {
            while (isActive) {
                computeEta(dest, destStop)
                if (destinationIdx == dest) {
                    withContext(Dispatchers.Main) { publishLastKnown() }
                }
                delay(ETA_POLL_INTERVAL_MS)
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
        // Reached only with a known trip (see restartEtaPolling), so the stop
        // times below belong to the vehicle actually being ridden.
        realtimeRepo.getArrivalForTripAtStop(tripId, destStop.stopId)?.let { live ->
            destinationEtaEpoch = live
            etaSource = EtaSource.REALTIME
            return
        }

        if (!stopsMatchTrip) {
            // Only the live prediction is trustworthy here; see stopsMatchTrip.
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
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
        if (tripId.isBlank()) return null   // no vehicle, no delay to observe
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

    /**
     * Records how often fixes actually arrive. Requested cadence is 1 s, so
     * a gap of many seconds means the system is throttling us — which is what
     * makes announcements late once the screen goes off. Individual long gaps
     * are logged immediately; a summary follows every 30 s so the log shows
     * the pattern without a line per second.
     */
    /**
     * Speed in m/s, preferring the receiver's own figure — it comes from
     * Doppler shift and does not accumulate the error of differencing two
     * positions. Falls back to displacement over time when absent.
     */
    private fun recordSpeed(loc: Location, prevLat: Double, prevLon: Double, gapMs: Long) {
        val mps = when {
            loc.hasSpeed() && loc.speed > 0f -> loc.speed.toDouble()
            gapMs in 1..30_000 && (prevLat != 0.0 || prevLon != 0.0) ->
                LocationHelper.distanceMetres(prevLat, prevLon, loc.latitude, loc.longitude) /
                    (gapMs / 1000.0)
            else -> return
        }
        // 25 m/s is 90 km/h — above anything a city bus or tram does, so a
        // higher reading is a bad fix and must not skew the average.
        if (mps.isNaN() || mps > MAX_PLAUSIBLE_SPEED_MPS) return
        speedSamples.addLast(mps)
        while (speedSamples.size > SPEED_SAMPLE_COUNT) speedSamples.removeFirst()
    }

    /** Average of the recent samples in km/h, or null before any arrive. */
    private fun recentSpeedKmh(): Double? =
        if (speedSamples.isEmpty()) null
        else speedSamples.average() * 3.6

    private fun logFixCadence() {
        val now = System.currentTimeMillis()
        if (lastFixMs != 0L) {
            val gap = now - lastFixMs
            if (gap > maxGapMs) maxGapMs = gap
            if (gap >= 5_000L) {
                FileLogger.w(TAG, "GPS gap: ${gap / 1000}s (requested 1s)")
            }
        }
        lastFixMs = now
        fixCount++

        if (lastCadenceLogMs == 0L) lastCadenceLogMs = now
        if (now - lastCadenceLogMs >= 30_000L) {
            val secs = (now - lastCadenceLogMs) / 1000.0
            val perMin = if (secs > 0) (fixCount / secs) * 60 else 0.0
            val spd = recentSpeedKmh()
                ?.let { String.format(java.util.Locale.US, ", speed %.0f km/h", it) }
                ?: ""
            FileLogger.i(TAG, "GPS cadence: $fixCount fixes in ${secs.toInt()}s " +
                "(${String.format(java.util.Locale.US, "%.1f", perMin)}/мин), " +
                "max gap ${maxGapMs / 1000}s$spd")
            fixCount = 0
            maxGapMs = 0
            lastCadenceLogMs = now
        }
    }

    /**
     * Warning distance for the stop we are heading to, or null when no
     * warning should be given at all.
     *
     * Where stops are close together the warning has nowhere to fit: the
     * departure from the previous stop is detected at DEPART_RADIUS, so with
     * only a couple of hundred metres between stops the warning would fire
     * seconds after pulling away — noise rather than notice. Below
     * [MIN_SPACING_FOR_APPROACH] the "Следваща спирка" announcement is
     * warning enough, and this returns null.
     *
     * Above it the warning fires at half the gap, capped at
     * [APPROACH_RADIUS]. Measured on line 76: most gaps are 400–2000 m, so
     * the warning is kept for nearly all of them; the exceptions are pairs
     * like Метростанция Бизнес парк → Бл. 437, which are 130 m apart.
     */
    private fun approachRadiusFor(idx: Int): Double? {
        val mode = settings.approachMode
        if (mode == AppSettings.APPROACH_OFF) return null

        val prev = idx - 1
        if (prev < 0) return APPROACH_RADIUS
        val (pLat, pLon) = stopLatLon.getOrNull(prev) ?: return APPROACH_RADIUS
        val (cLat, cLon) = stopLatLon.getOrNull(idx) ?: return APPROACH_RADIUS
        val spacing = LocationHelper.distanceMetres(pLat, pLon, cLat, cLon)
        if (spacing <= 0.0) return APPROACH_RADIUS

        // Sparse mode drops the warning where it would arrive seconds after
        // the previous stop. In "all" mode it is kept, though for very close
        // stops half the gap can fall inside the arrival radius, in which case
        // it simply never fires — nothing is broken, there is just no room.
        if (mode == AppSettings.APPROACH_SPARSE &&
            spacing < MIN_SPACING_FOR_APPROACH) return null

        // Distance sized by speed, so the warning arrives a roughly constant
        // TIME before the stop. A fixed distance cannot serve both ends of the
        // range measured on this route: at 14 km/h in the centre 300 m is over
        // a minute of notice, while at 56 km/h on Цариградско it was fifteen
        // seconds — too little to signal the driver and reach the door.
        //
        // Never more than half the gap between stops, or the warning would
        // land on top of the previous stop's departure.
        val mps = (recentSpeedKmh() ?: 0.0) / 3.6
        val bySpeed = mps * APPROACH_TARGET_SECONDS
        return bySpeed
            .coerceIn(APPROACH_MIN_RADIUS, APPROACH_RADIUS)
            .coerceAtMost(spacing / 2.0)
    }

    /**
     * Called right after "Следваща спирка, X" is announced. If X is already
     * inside its own approach radius, the warning that would follow adds
     * nothing — the passenger has just been told the stop is next, and would
     * hear its name again seconds later. This happens routinely when GPS is
     * throttled: several stops pass between fixes and the tracker catches up
     * mid-gap.
     */
    private fun suppressRedundantApproach(loc: Location) {
        val radius = approachRadiusFor(currentIdx)
        if (radius == null || distTo(loc, currentIdx) <= radius) {
            approachAnnounced = true
        }
    }

    private fun distTo(loc: Location, idx: Int): Double {
        val (lat, lon) = stopLatLon.getOrNull(idx) ?: return Double.MAX_VALUE
        return LocationHelper.distanceMetres(loc.latitude, loc.longitude, lat, lon)
    }

    private fun publish(distance: Int?) {
        _trackingState.value = TrackingState.Tracking(
            determiningDirection  = candidates.isNotEmpty(),
            routeLabel            = routeLabel,
            stops                 = orderedStops,
            currentIdx            = currentIdx,
            atStop                = atStop,
            distanceToNextMetres  = distance,
            fixAccuracyMetres     = lastAccuracy?.toInt(),
            speedKmh              = recentSpeedKmh()?.toInt(),
            awaitingAccurateFix   = awaitingFirstFix && orderedStops.isNotEmpty(),
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

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            wakeLock = pm.newWakeLock(
                android.os.PowerManager.PARTIAL_WAKE_LOCK,
                "SofiaTransit::Journey"
            ).apply {
                setReferenceCounted(false)
                // Safety timeout: if the service somehow dies without
                // releasing, the lock expires rather than draining the
                // battery indefinitely. Journeys longer than this are
                // re-acquired on the next fix.
                acquire(3 * 60 * 60 * 1000L)
            }
            FileLogger.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not acquire wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            // Only log when something was actually held: endJourney() and
            // onDestroy() both call this, which logged the release twice.
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    FileLogger.i(TAG, "Wake lock released")
                }
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Could not release wake lock: ${e.message}")
        }
        wakeLock = null
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
        releaseWakeLock()
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
