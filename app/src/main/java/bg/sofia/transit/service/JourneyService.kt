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
import bg.sofia.transit.data.repository.VehicleMatcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
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

        /**
         * How much closer to the stop one fix must bring us before we accept
         * that we are heading for it. Wide enough not to be read out of
         * ordinary scatter between two consecutive fixes.
         */
        const val APPROACHING_MARGIN = 5.0

        /** Accuracy required to attach to a stop, eased over time. */
        private const val SNAP_ACCURACY_STRICT = 30.0f
        private const val SNAP_ACCURACY_MEDIUM = 70.0f
        private const val SNAP_ACCURACY_LOOSE  = 150.0f
        private const val SNAP_STEP1_MS = 10_000L
        private const val SNAP_STEP2_MS = 20_000L
        /** After this long without a usable fix the journey is abandoned. */
        private const val SNAP_GIVE_UP_MS = 3 * 60 * 1000L

        /**
         * How long tracking may stay silent while the actual line is being
         * identified. If it cannot be settled in that time, continuing serves
         * no purpose: nothing can be announced and the GPS runs for nothing.
         */

        /** How much nearer a new candidate must be to displace the current. */
        private const val SWITCH_MARGIN = 20.0
        /** With the current vehicle silent, a candidate must be this close. */
        private const val TAKEOVER_RADIUS = 10.0

        /** Below this we are standing, and no vehicle can be identified. */
        private const val MIN_SPEED_FOR_IDENTIFY = 10.0

        /** Older than this, our own position cannot be compared with a moving
         *  vehicle's. Normal operation delivers a fix every second. */
        private const val MAX_FIX_AGE_FOR_DECISIONS_MS = 4_000L
        /** At or below this many fixes a minute, positioning is degraded. */
        private const val MIN_CADENCE_FOR_DECISIONS = 25
        /** Thirty-second windows averaged; two of them make a minute. */
        private const val CADENCE_WINDOWS = 2
        /** How often the degraded-positioning notice may repeat. */
        private const val DEGRADED_NOTICE_INTERVAL_MS = 60_000L


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

        /**
         * The same idea before the journey has begun, with a longer allowance.
         *
         * Standing at a stop is not idleness — a bus may be twenty minutes
         * away, and tracking started early is tracking used as intended. But
         * it cannot be unlimited either: forgotten at the stop, tracking would
         * hold the processor awake and poll GPS every second indefinitely.
         */
        private const val WAITING_TIMEOUT_MS = 30 * 60 * 1000L

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

        /**
         * First check after the direction has been settled from our own
         * movement. That grace period above exists for the receiver to
         * settle; by the time a direction is resolved, fixes have been
         * arriving for minutes and the vehicle is moving — waiting another
         * half minute only delays the answer.
         */
        private const val AFTER_DIRECTION_CHECK_DELAY_MS = 5_000L

        /**
         * Retry interval when a check was skipped because we were not moving.
         *
         * Skipping used to cost the whole interval: a bus standing at a light
         * when the check fell due was not looked at again for a minute. On
         * the ride that prompted this, two of five checks were lost that way.
         * Re-trying every few seconds costs nothing — no data is fetched
         * until we are moving — and the first check after the bus pulls away
         * comes at once.
         */
        private const val DEFERRED_CHECK_RETRY_MS = 5_000L

        /**
         * Retry interval while a parting is awaiting its confirming reading.
         * Short enough that alighting is still recognised promptly now that
         * it takes two readings, long enough for the feed to have produced a
         * fresh report in between.
         */
        private const val PARTING_RECHECK_MS = 15_000L

        /** A parting reading counts only from a report at most this old. */
        private const val PARTING_MAX_REPORT_AGE_SEC = 30L

        /**
         * Memory of the vehicles seen at the previous reading. Older than
         * this, it no longer says who was beside us a moment ago.
         */
        private const val READING_MEMORY_MS = 120_000L

        /**
         * Consecutive accurate fixes above the speed threshold needed to call
         * the journey under way.
         *
         * A single one used to be enough, and the first fixes after the
         * receiver wakes jump by metres between seconds — which reads as
         * vehicle speed. Every journey in one log was "under way" within four
         * seconds of starting, one of them while the passenger stood at the
         * stop for five more minutes; the faster early checking, timed from
         * departure, had run out before the bus arrived.
         */
        private const val DEPARTURE_CONFIRM_FIXES = 3
        /** Fixes vaguer than this take no part in detecting departure. */
        private const val DEPARTURE_MAX_ACCURACY = 30.0f

        /**
         * Speed samples in the short average, used where the question is
         * whether we are moving NOW. The long average lags a standstill at a
         * light by up to fifteen seconds in both directions.
         */
        private const val SHORT_SPEED_SAMPLE_COUNT = 5

        /**
         * How far we must move away from every one of the next stops, beyond
         * the closest we have been to each, before we are taken to be off the
         * route.
         *
         * Measured against the stops ahead rather than against the line of
         * the route: the app keeps only the stops, so a line drawn between
         * them would be a guess wherever the street bends. Travelling a route
         * always brings one of its next stops closer — including when a stop
         * was missed in a GPS gap, since the one after it is in the set too.
         * Only a different route takes us away from all of them at once.
         */
        private const val OFF_ROUTE_GROWTH = 300.0

        /**
         * How long we may remain off the chosen route with no vehicle
         * identified before tracking is ended.
         */
        private const val LOST_TIMEOUT_MS = 10 * 60 * 1000L

        /**
         * After "Слизате тук", how long the phone may go without moving at
         * vehicle speed before the journey is taken as over.
         *
         * The automatic end is protection for the battery, not a precise
         * detector of getting off, so it errs towards ending late rather
         * than ever ending during a ride. A passenger who stays aboard is
         * carried off at vehicle speed within a minute or so, which is an
         * ordinary departure and brings "Спирката за слизане е подмината";
         * one who got off stands or walks, and five minutes later tracking
         * stops.
         */
        private const val DESTINATION_DWELL_MS = 5 * 60 * 1000L

        /**
         * How long, after the vehicle has gone on without us while we were not
         * moving, the phone is watched for vehicle speed before getting off is
         * concluded.
         *
         * Standing still while the vehicle we followed leaves has two
         * explanations: we got off, or we are in a different vehicle that is
         * waiting at a red light or a stop while the one we took for ours
         * turned away. The second shows itself as soon as our vehicle moves
         * on, and a red light rarely lasts longer than this.
         */
        private const val ALIGHT_SETTLE_MS = 90_000L

        /**
         * Off its route, how long the identified vehicle may go unseen beside
         * us before it is no longer believed to be ours.
         *
         * A detour — road works, a diversion — is taken in silence as long as
         * the vehicle shows it is with us. One that is not seen at all may be
         * a vehicle wrongly taken for ours that went its own way and then
         * stopped reporting, which left the parting check nothing to judge
         * by: tracking then stayed silent until stopped by hand.
         */
        private const val DETOUR_PROOF_MS = 3 * 60 * 1000L

        /**
         * A reading of the vehicle feed at most this old that found the
         * vehicle we are following within riding range says we are still in
         * it. Longer than the slow checking interval, so one reading always
         * covers the time until the next.
         */
        private const val VEHICLE_BESIDE_MEMORY_MS = 90_000L

        /**
         * With positioning degraded, parting may still be judged if we are
         * moving slowly and the latest fix is no older than this: at walking
         * pace a position this old is a few metres out, against a parting
         * radius of 250.
         */
        private const val PARTING_MAX_FIX_AGE_MS = 15_000L

        /**
         * Two readings of the feed closer together than this mostly see the
         * same reports, and a repeated report confirms nothing.
         */
        private const val MIN_READING_SPACING_MS = 20_000L

        /** Without any fix for this long, weak signal is announced. */
        private const val NO_FIX_NOTICE_MS = 30_000L
        /**
         * Without a usable fix for this long, tracking is ended. Usable means
         * within SNAP_ACCURACY_LOOSE, the loosest accuracy the tracker ever
         * relies on: announcements run on vaguer fixes than the stricter
         * thresholds, and ending tracking while they still work would be
         * wrong. Beyond 150 m a fix cannot tell one stop from the next.
         */
        private const val NO_USABLE_FIX_TIMEOUT_MS = 10 * 60 * 1000L
        /** How often the journey timers are looked at. */
        private const val TIMER_TICK_MS = 5_000L

        /** Spacing of the "still off the route" diagnostic. */
        private const val OFF_ROUTE_LOG_INTERVAL_MS = 30_000L

        /** How long the faster early checking lasts. */
        private const val EARLY_PHASE_MS = 4 * 60 * 1000L
        /** Interval during that early phase. */
        private const val EARLY_CHECK_INTERVAL_MS = 30_000L
        /** Within this, we and the vehicle count as travelling together. */
        private const val SAME_VEHICLE_RADIUS = 150.0

        /**
         * A stop can only be treated as passed while we are this close to the
         * one we are catching up to. Without the bound, distance comparisons
         * alone declare stops passed from kilometres away.
         */
        const val CATCHUP_MAX_DISTANCE = 250.0


        /**
         * Beyond this the vehicle has plainly gone on without us. Wider than
         * SAME_VEHICLE_RADIUS so that a single imprecise position cannot end a
         * journey, yet close enough to notice within a stop's distance.
         */
        private const val PARTED_RADIUS = 250.0

        /** Consecutive checks naming the same other line before we speak. */


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
        /**
         * Trip whose adoption was last spoken aloud.
         *
         * Deliberately here rather than on the instance: the point is to
         * survive the service being destroyed and recreated, which is exactly
         * what power saving did — three identical "проследяването се
         * превключва" for one bus, because each new instance started afresh.
         * Cleared when a journey ends.
         */
        private var lastAnnouncedTripId: String? = null

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
    /**
     * Fixes per minute for the last two completed windows — a minute of
     * history, kept as two numbers rather than a record of every fix.
     */
    private val cadenceHistory = ArrayDeque<Int>()
    /** When the degraded-positioning notice was last spoken. */
    private var lastDegradedNoticeMs = 0L

    /** Chosen alighting stop; null until the user picks one. */
    private var destinationIdx: Int? = null
    /** Which of the "2 stops / 1 stop / next stop" warnings already fired. */
    private val alightWarningsFired = mutableSetOf<Int>()
    /**
     * When "Слизате тук" was said for the present destination; 0 while it
     * has not been. Both keeps it to once and times the limit after it.
     */
    private var destinationArrivedMs = 0L
    /** Timestamp of the last forward progress, for the inactivity timeout. */
    private var lastProgressMs = System.currentTimeMillis()


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
    // (timerJob is declared with the other alighting state above.)

    /** The line the passenger picked, kept only to notice when it was wrong. */
    private var selectedRouteId = ""
    /** True once a vehicle has been identified and adopted. */
    private var identified = false
    /** Candidate awaiting its second reading — kept for logging it once. */
    private var candidateVote: String? = null
    /**
     * Vehicles within range at the previous reading, by trip, and when that
     * reading was taken. A candidate is confirmed against this.
     */
    private var lastInRange: Map<String, VehicleMatcher.Sighting> = emptyMap()
    private var lastReadingMs = 0L
    /** True while a run of deferred checks has already been logged. */
    private var deferLogged = false

    /** Report time of the first reading that put our vehicle beyond reach. */
    private var partingFirstStamp = 0L
    /** Highest short-average speed since that reading, km/h. */
    private var partingPeakKmh = 0.0

    /**
     * The line as the passenger chose it, kept so that tracking can return
     * to it when a vehicle identified on the way proves not to be ours.
     */
    private data class ChosenLine(
        val label: String,
        val tripId: String,
        val routeId: String,
        val headsign: String,
        val stops: List<StopWithSequence>,
        val latLons: List<Pair<Double, Double>>,
        val candidates: List<DirectionCandidate>
    ) {
        /** "94" out of "Автобус 94 → Младост 1". */
        val shortName: String
            get() = label.substringBefore(" → ").substringAfter(' ', label)
    }
    private var chosen: ChosenLine? = null

    /** True once "Изглежда не пътувате с линия X" has been said. */
    private var wrongLineNoticeGiven = false

    /** True while we are moving away from all the next stops. */
    private var offRoute = false
    /** When we became off route with no vehicle identified; 0 otherwise. */
    private var lostSinceMs = 0L
    /** When we became off the identified vehicle's route; 0 otherwise. */
    private var detourSinceMs = 0L
    /** Closest we have been to each of the next stops, by stop index. */
    private val windowMin = mutableMapOf<Int, Double>()
    /** Closest we have been, while moving, to the nearest stop ahead before
     *  attaching to any; negative when not yet measured. */
    private var unattachedMin = -1.0
    private var lastOffRouteLogMs = 0L
    /** Consecutive accurate fixes above the departure threshold. */
    private var departureStreak = 0

    /**
     * Highest speed, from accurate fixes, since we moved out of the arrival
     * circle of the stop we are at. A vehicle leaving a stop reaches vehicle
     * speed within those first tens of metres; a passenger walking away
     * never does.
     */
    private var leavingPeakKmh = 0.0
    /** True after leaving a stop at walking pace, until that is explained. */
    private var leftOnFoot = false
    /** The stop that was left on foot. */
    private var footStopIdx = 0

    /** Last time the vehicle being followed was seen within riding range. */
    private var trackedWithUsMs = 0L

    /**
     * When the vehicle was found to have gone on without us while we stood
     * or walked; 0 when no such conclusion is pending. See ALIGHT_SETTLE_MS.
     */
    private var alightPendingSinceMs = 0L
    /** Whether that happened at the chosen stop, after "Слизате тук". */
    private var alightPendingAtChosenStop = false
    /** How far the vehicle was then, for the log and the withdrawal. */
    private var alightPendingDistance = 0.0
    private var lastDwellHoldLogMs = 0L

    /** Last fix within SNAP_ACCURACY_LOOSE. */
    private var lastUsableFixMs = 0L
    /** When the timers started; stands in for fixes not yet received. */
    private var timersStartMs = 0L
    private var timerJob: Job? = null
    /** Logged once, when announcements begin on the passenger's own choice. */
    private var proceedingOnChoiceLogged = false

    /** When the vehicle first got under way, for timing the faster checks. */
    private var movingSinceMs = 0L



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
        // Guarded here rather than trusted to the caller: with an empty list
        // the boarding index below is coerced into an empty range, which
        // throws, and a crash in the tracking service is the worst possible
        // way to learn that a route had no stops.
        if (stops.isEmpty()) {
            FileLogger.w(TAG, "beginJourney called with no stops — ignoring")
            return
        }

        this.tripId       = tripId
        this.routeId      = routeId
        this.headsign     = headsign
        selectedRouteId   = routeId
        identified        = false
        proceedingOnChoiceLogged = false
        resetIdentificationState()
        stopsMatchTrip    = tripId.isNotBlank()
        movingSinceMs     = 0L
        chosen = ChosenLine(label, tripId, routeId, headsign, stops, latLons, emptyList())
        routeLabel        = label
        orderedStops      = stops
        stopLatLon        = latLons
        currentIdx        = boardingStopIdx.coerceIn(0, stops.lastIndex)
        atStop            = false
        approachAnnounced = false
        awaitingFirstFix  = true
        // Cleared here as well as in endJourney: a new journey must not
        // inherit anything from the last one, and endJourney is not
        // guaranteed to have run — the service can be recreated after the
        // system kills it, or a journey started while another is active.
        candidates        = emptyList()
        anchorIdx         = emptyList()
        destinationEtaEpoch = null
        etaSource         = EtaSource.NONE
        lastAnnouncedTripId = null
        destinationIdx    = null
        alightWarningsFired.clear()
        destinationArrivedMs   = 0L
        lastProgressMs    = System.currentTimeMillis()

        lastFixMs = 0L
        fixCount = 0
        maxGapMs = 0L
        lastCadenceLogMs = 0L
        speedSamples.clear()
        cadenceHistory.clear()
        lastDegradedNoticeMs = 0L
        snapWaitStartedMs = 0L
        lastWeakSignalNoticeMs = 0L
        lastAccuracy = null

        publish(distance = null)
        acquireWakeLock()
        startLocUpdates()
        startVehicleTracking()
        startJourneyTimers()
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
        selectedRouteId   = routeId
        identified        = false
        proceedingOnChoiceLogged = false
        resetIdentificationState()
        chosen = ChosenLine(label, "", routeId, "", emptyList(), emptyList(), candidates)
        this.candidates   = candidates
        anchorIdx         = emptyList()
        destinationEtaEpoch = null
        etaSource         = EtaSource.NONE
        lastAnnouncedTripId = null
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
        destinationArrivedMs   = 0L
        movingSinceMs     = 0L
        lastProgressMs    = System.currentTimeMillis()
        determinationStartMs = System.currentTimeMillis()
        lastAmbiguityLogMs = 0L
        anchorLat = 0.0; anchorLon = 0.0; anchorIdx = emptyList()

        lastFixMs = 0L; fixCount = 0; maxGapMs = 0L; lastCadenceLogMs = 0L
        speedSamples.clear()

        publish(distance = null)
        acquireWakeLock()
        startLocUpdates()
        // Identification runs from the outset now, not only once a direction
        // has been settled: it is the thing that settles the direction.
        startVehicleTracking()
        startJourneyTimers()
        announce("Следене на пътуването започна. Посоката се определя.")
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

        // No vehicle probe here any more: identification runs continuously in
        // checkVehicle and settles the direction the moment it succeeds. What
        // remains below is the fallback for when no vehicle can be identified
        // at all — a line that publishes no positions — where our own
        // displacement is the only evidence there is.

        // Guard 3 — speed. Walking to the far end of the stop, or to a shop
        // and back, can accumulate the required displacement while the vehicle
        // has not moved at all; the direction would then be read off the
        // pedestrian. A bus pulling away exceeds this within seconds, and a
        // jumping fix produces no sustained speed.
        val kmh = recentSpeedKmh()
        if (kmh == null || kmh < MIN_SPEED_FOR_DIRECTION) {
            return false
        }

        val moved = LocationHelper.distanceMetres(
            anchorLat, anchorLon, loc.latitude, loc.longitude)
        if (moved < DIRECTION_MIN_MOVEMENT) {
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

        // The anchor goes stale once its stop is behind us.
        //
        // Progress is measured towards the stop that followed the anchor, so
        // after passing that stop the distance grows again — in both
        // directions at once, leaving nothing to choose between them. With
        // the anchor never moved, the comparison could stay deadlocked for
        // the rest of the journey and the direction would never be settled.
        // This only re-anchors when both readings have gone negative, which
        // means the anchor is behind us, not merely that the answer is not
        // yet clear.
        if (progress.all { it < 0 }) {
            anchorLat = loc.latitude
            anchorLon = loc.longitude
            anchorIdx = nowIdx
            FileLogger.i(TAG, "Direction: anchor left behind — re-anchoring")
            return false
        }

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
        // The passenger's choice, now complete with its direction, is what
        // tracking returns to should an identified vehicle prove not ours.
        if (!identified) {
            this.chosen = this.chosen?.copy(
                label = routeLabel, headsign = chosen.headsign,
                stops = chosen.stops, latLons = chosen.latLons,
                candidates = emptyList())
        }
        // Spoken at once. It used to wait until identification had been given
        // up, so as not to name a direction on a line that might turn out to
        // be the wrong one — but choosing the wrong line is rare, while the
        // silence was paid for on every journey. Should the line prove wrong,
        // the switch says so plainly a moment later.
        announce("Посока, ${chosen.headsign}.")
        // Not sooner than MIN_READING_SPACING_MS after the last reading: one
        // taken five seconds after another saw the very same report.
        val sinceReading = System.currentTimeMillis() - lastReadingMs
        startVehicleTracking(
            if (lastReadingMs != 0L && sinceReading < MIN_READING_SPACING_MS)
                maxOf(AFTER_DIRECTION_CHECK_DELAY_MS, MIN_READING_SPACING_MS - sinceReading)
            else AFTER_DIRECTION_CHECK_DELAY_MS)
        return true
    }

    // The limit on how long the direction may stay unknown is kept with the
    // other journey limits, in checkJourneyTimers.



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
        val now = System.currentTimeMillis()
        if (snapWaitStartedMs == 0L) snapWaitStartedMs = now
        val waited = now - snapWaitStartedMs

        // Fixes arriving too sparsely disqualify the position as surely as a
        // wide error radius does, and by the same measure already used to
        // suspend decisions about the vehicle. Underground, the receiver
        // reported six fixes a minute and each one looked accurate enough on
        // its own; tracking attached to a stop on the strength of them and
        // then had nothing to work with.
        val sparse = positionUnreliable()

        val acc = lastAccuracy
        val required = when {
            waited < SNAP_STEP1_MS -> SNAP_ACCURACY_STRICT
            waited < SNAP_STEP2_MS -> SNAP_ACCURACY_MEDIUM
            else                   -> SNAP_ACCURACY_LOOSE
        }
        if (!sparse && (acc == null || acc <= required)) return true

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
        destinationArrivedMs = 0L
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
        timerJob?.cancel()
        timerJob = null
        timersStartMs = 0L

        orderedStops = emptyList()
        stopLatLon   = emptyList()
        currentIdx   = 0
        atStop       = false
        destinationIdx = null
        destinationEtaEpoch = null
        etaSource = EtaSource.NONE
        alightWarningsFired.clear()
        destinationArrivedMs = 0L
        awaitingFirstFix = true
        candidates = emptyList()
        anchorIdx = emptyList()
        stopsMatchTrip = false
        identified = false
        proceedingOnChoiceLogged = false
        lastAnnouncedTripId = null
        resetIdentificationState()
        chosen = null
        selectedRouteId = ""
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

        // While a parting awaits confirmation, note whether we keep moving
        // at vehicle speed: that is what separates getting off from having
        // followed the wrong vehicle. See checkParted.
        if (partingFirstStamp != 0L) {
            partingPeakKmh = maxOf(partingPeakKmh, shortSpeedKmh() ?: 0.0)
        }

        // Only fixes this good count as evidence of how we are moving: a
        // phone indoors, placed by the mobile network to within a hundred
        // metres, jumps about in a way that reads as vehicle speed.
        val accurateFix = loc.hasAccuracy() && loc.accuracy <= DEPARTURE_MAX_ACCURACY

        // Getting off pending: moving at vehicle speed now means we are in
        // another vehicle, not on the pavement. See ALIGHT_SETTLE_MS.
        if (alightPendingSinceMs != 0L && accurateFix) {
            val kmh = shortSpeedKmh() ?: 0.0
            if (kmh >= MIN_SPEED_FOR_IDENTIFY) {
                alightPendingSinceMs = 0L
                FileLogger.i(TAG, "Vehicle speed (${kmh.toInt()} km/h) while getting off " +
                    "was pending — we were not in the vehicle we followed")
                revokeIdentification("Vehicle left ${alightPendingDistance.toInt()} m " +
                    "while we stood; now moving at ${kmh.toInt()} km/h")
            }
        }
        if (!loc.hasAccuracy() || loc.accuracy <= SNAP_ACCURACY_LOOSE) {
            lastUsableFixMs = System.currentTimeMillis()
        }

        // How fast we move away from the stop we are at, once outside its
        // arrival circle — see leavingPeakKmh.
        if (atStop && accurateFix && currentIdx in stopLatLon.indices &&
            distTo(loc, currentIdx) > ARRIVAL_RADIUS) {
            leavingPeakKmh = maxOf(leavingPeakKmh, shortSpeedKmh() ?: 0.0)
        }

        lastLat = loc.latitude
        lastLon = loc.longitude

        // Announcements never wait for the vehicle to be identified.
        //
        // Both things are worked out at once and whichever finishes first is
        // used: the direction from the route's stop order, the vehicle from
        // the live feed. Holding the announcements until the vehicle was
        // known made every journey start in silence for the sake of a wrong
        // line — a rare mistake, and one the switch corrects aloud within a
        // minute of it happening.
        //
        // Choosing the automatic direction says only that the passenger does
        // not know the termini, not that they are unsure which line they
        // want; there was never a reason for that choice to cost them the
        // wait. Which of the two settled first is the app's business, not
        // theirs.
        if (!identified && !proceedingOnChoiceLogged && movingSinceMs != 0L) {
            proceedingOnChoiceLogged = true
            FileLogger.i(TAG, "Announcing on the chosen line; " +
                "vehicle identification continues in the background")
        }

        // (The limit on a journey that never starts is in checkJourneyTimers.)


        // Direction still unknown: nothing else can run, because the order of
        // the stops — and so which one is "next" — depends on it. Resolved
        // either from a vehicle, in checkVehicle, or from our own displacement
        // here; whichever arrives first.
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

            // Off the route, attaching again needs us back near it.
            //
            // The ordinary test below — are we closing on the nearest stop —
            // would be met by any street that happens to run towards one of
            // this route's stops, kilometres away, and tracking would resume
            // announcing a route we are not on. Being within catch-up range
            // of one of its stops is evidence of being back; approaching one
            // from afar is not.
            if (offRoute && bestDist > CATCHUP_MAX_DISTANCE) {
                awaitingFirstFix = true
                val now = System.currentTimeMillis()
                if (now - lastOffRouteLogMs >= OFF_ROUTE_LOG_INTERVAL_MS) {
                    lastOffRouteLogMs = now
                    FileLogger.d(TAG, "Off route: nearest stop ahead " +
                        "${orderedStops[best].stopName} ${bestDist.toInt()} m")
                }
                publish(distance = null)
                return
            }

            // Attached only when we are actually heading for the stop.
            //
            // Distance alone says nothing: stops two kilometres apart are
            // ordinary — Хотел Плиска to Орлов мост is over two — so being
            // far from the next one is no evidence of anything. What
            // distinguished the case that went wrong was not the distance but
            // the direction: underground, the stop chosen was not being
            // approached at all, and tracking settled on it for the rest of
            // the journey.
            //
            // Standing at the stop is the exception: there the distance is
            // small and unchanging, and waiting for it to shrink would mean
            // never starting.
            if (bestDist > ARRIVAL_RADIUS) {
                if (prevLat == 0.0 && prevLon == 0.0) {
                    // Nothing to compare against yet; one more fix will tell.
                    awaitingFirstFix = true
                    publish(distance = null)
                    return
                }
                val before = LocationHelper.distanceMetres(
                    prevLat, prevLon,
                    stopLatLon[best].first, stopLatLon[best].second)
                if (before - bestDist < APPROACHING_MARGIN) {
                    awaitingFirstFix = true
                    FileLogger.d(TAG, "Not approaching ${orderedStops[best].stopName} " +
                        "(${before.toInt()} m → ${bestDist.toInt()} m) — not attaching yet")
                    watchUnattached(bestDist, orderedStops[best].stopName)
                    publish(distance = null)
                    return
                }
            }

            currentIdx = best
            windowMin.clear()
            unattachedMin = -1.0
            if (offRoute) {
                offRoute = false
                lostSinceMs = 0L
                detourSinceMs = 0L
                lastProgressMs = System.currentTimeMillis()
                FileLogger.i(TAG, "Back on route at ${orderedStops[best].stopName} " +
                    "(${bestDist.toInt()} m)")
            }

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

        // Having left a stop on foot, the stop logic stays silent unless
        // vehicle speed shows we were aboard after all.
        if (leftOnFoot && handleLeftOnFoot(loc, accurateFix)) return

        // Look-ahead window: nearest stop among current..current+LOOKAHEAD.
        val end = (currentIdx + LOOKAHEAD).coerceAtMost(orderedStops.lastIndex)
        var nearest = currentIdx
        var nearestDist = distTo(loc, currentIdx)
        for (i in (currentIdx + 1)..end) {
            val d = distTo(loc, i)
            if (d < nearestDist) { nearestDist = d; nearest = i }
        }

        // ── Off-route watch ──────────────────────────────────────────────
        if (watchOffRoute(loc, end)) {
            publish(distance = null)
            return
        }

        when {
            // ── Inside a stop's radius → we are AT that stop ──────────────
            nearestDist <= ARRIVAL_RADIUS -> {
                if (!atStop || nearest != currentIdx) {
                    // Stops the positioning never saw are passed over in
                    // silence, not recited.
                    //
                    // They were announced once, on the reasoning that a stop
                    // gone by deserves saying. But they only ever arise when
                    // fixes have been sparse, which is precisely when the
                    // announcement is late — sometimes by minutes, and often
                    // triggered by the screen being turned on rather than by
                    // the bus arriving anywhere. Reciting three stop names at
                    // once tells a passenger who cannot look out of the window
                    // nothing they can act on, and invites them to act on it
                    // anyway. Only where we are now is spoken.
                    val skipped = nearest - (if (atStop) currentIdx + 1 else currentIdx)
                    if (skipped > 0) {
                        FileLogger.i(TAG, "Passed $skipped stop(s) unseen — not announcing them")
                    }
                    currentIdx = nearest
                    atStop = true
                    leavingPeakKmh = 0.0
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
            // Leaving the stop at walking pace: not the vehicle departing, but
            // very probably the passenger walking away from it. Nothing is
            // announced — "Следваща спирка" and "Спирката за слизане е
            // подмината" were both said to a passenger already on the
            // pavement — and what it was is settled on the fixes that follow.
            // See handleLeftOnFoot.
            atStop && distTo(loc, currentIdx) > DEPART_RADIUS &&
                    leavingPeakKmh < MIN_SPEED_FOR_IDENTIFY -> {
                atStop = false
                approachAnnounced = false
                leftOnFoot = true
                footStopIdx = currentIdx
                FileLogger.i(TAG, "Left ${orderedStops[currentIdx].stopName} at walking pace " +
                    "(peak ${leavingPeakKmh.toInt()} km/h) — taken as alighted; announcements paused")
            }

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
                // The gap in positioning swallowed the stop entirely. The
                // tracker moves on to where we actually are; the stops behind
                // us are not recited, for the reasons above.
                FileLogger.i(TAG, "Caught up past ${nearest - currentIdx} " +
                    "stop(s) — not announcing them")
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
            if (destDist <= ALIGHT_ANNOUNCE_RADIUS && destinationArrivedMs == 0L) {
                destinationArrivedMs = System.currentTimeMillis()
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
                // Its arrival time is of no further use; the poll used to run
                // on regardless until the journey ended.
                etaJob?.cancel()
                etaJob = null
                destinationIdx = null
                alightWarningsFired.clear()
                destinationArrivedMs = 0L
                destinationEtaEpoch = null
                etaSource = EtaSource.NONE
                announce("Спирката за слизане е подмината.")
            } else if (currentIdx != idxBefore) {
                // 3) We advanced a stop — check the countdown warnings.
                evaluateAlightWarnings(announceNow = true)
            }
        }

        // ── Progress, for the inactivity limit (see checkJourneyTimers) ──
        if (currentIdx != idxBefore) {
            lastProgressMs = System.currentTimeMillis()
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
     *  - Identification. Which vehicle we are in, of whatever line — see
     *    checkVehicle. A vehicle of the chosen line and direction is taken
     *    over silently: nothing the passenger hears changes, only the arrival
     *    prediction and, for shortened depot runs, the stop list itself
     *    become correct. Anything else is confirmed across readings first
     *    and then announced.
     *
     *  - Noticing that the passenger has got off. While riding, we and the
     *    vehicle move together and the gap stays small. Once they alight, the
     *    vehicle drives away and the gap grows and keeps growing. That is a
     *    far better signal than the inactivity timer, which has to wait ten
     *    minutes. Requires two readings from fresh reports, because a single
     *    stale position must not end a journey — and if we are still moving
     *    at vehicle speed when it is confirmed, it means the vehicle was not
     *    ours, and only the identification ends. See checkParted.
     */
    private fun startVehicleTracking(initialDelayMs: Long = FIRST_VEHICLE_CHECK_DELAY_MS) {
        vehicleJob?.cancel()
        if (routeId.isBlank()) return

        vehicleJob = serviceScope.launch {
            delay(initialDelayMs)
            while (isActive) {
                // A check may ask to be repeated sooner than the regular
                // interval: when it was skipped because we were standing,
                // and while a parting awaits its confirming reading.
                var sooner: Long? = null
                try {
                    sooner = checkVehicle()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Vehicle check failed: ${e.message}")
                }
                // Faster early on. Boarding the wrong line is only worth
                // catching while few stops have passed, so the first minutes
                // are checked twice as often; afterwards the slower rate is
                // enough for noticing that the rider has got off.
                //
                // Timed from DEPARTURE, not from the journey being started.
                // Waiting four minutes at the stop used to consume the whole
                // fast period before the bus had even arrived, so the checks
                // that mattered ran at the slow rate. Departure itself must
                // therefore be real, not GPS scatter — see recordSpeed.
                val since = if (movingSinceMs == 0L) 0L
                            else System.currentTimeMillis() - movingSinceMs
                val early = movingSinceMs == 0L || since < EARLY_PHASE_MS
                val regular = if (early) EARLY_CHECK_INTERVAL_MS else VEHICLE_CHECK_INTERVAL_MS
                delay(sooner?.let { minOf(it, regular) } ?: regular)
            }
        }
    }

    /**
     * The one recurring question: which vehicle are we in?
     *
     * Everything the journey needs follows from the answer — the line, the
     * direction, and the ordered stops — so it is asked directly rather than
     * inferred from the line the passenger picked. Their choice matters only
     * as a fallback for when no vehicle can be identified at all.
     *
     * This replaced five separate mechanisms that each answered a slice of
     * the same question: confirming the chosen line, detecting a vehicle of
     * another line, reading the direction off a vehicle, ruling the chosen
     * line out, and suspending announcements while in doubt. They disagreed
     * with one another at the edges and were slow, because each waited for
     * the previous one to finish.
     *
     * Returns how soon to check again when that should be sooner than the
     * regular interval, or null for the regular interval.
     */
    private suspend fun checkVehicle(): Long? {
        if (lastLat == 0.0 && lastLon == 0.0) return null

        // Nothing is checked before the journey is under way: at the stop,
        // vehicles standing beside us prove nothing, and polling for twenty
        // minutes while waiting would spend data for no purpose.
        if (movingSinceMs == 0L) return null

        // Nor is anything decided while our own position is unreliable.
        //
        // Every judgement here rests on where WE are. With the phone in a
        // power-saving mode the fixes dropped from sixty a minute to ten,
        // with gaps of up to twenty-seven seconds — at 40 km/h that is a
        // position two hundred metres out of date. The consequences were all
        // of a piece: the vehicle being ridden fell out of range and a
        // passing one was adopted instead, and later the same staleness put
        // the tracked vehicle "484 m away" and ended the journey with
        // "Изглежда слязохте" while the passenger was still aboard.
        //
        // Announcements are left running: they compare our position with
        // stops rather than with another moving object, and they held up
        // perfectly well at ten fixes a minute.
        //
        // One exception, below: parting is still judged at walking pace.

        // The long average for correcting report ages, the short one for
        // deciding whether we are moving at this moment.
        val mps = (recentSpeedKmh() ?: 0.0) / 3.6
        val kmhNow = shortSpeedKmh() ?: 0.0
        val underWay = kmhNow >= MIN_SPEED_FOR_IDENTIFY

        val unreliable = positionUnreliable()
        // The staleness that made degraded positioning dangerous was at
        // vehicle speed. On foot, a fix fifteen seconds old is a few metres
        // out, and the parting radius is 250: getting off is exactly when the
        // signal tends to weaken, as the passenger walks into a building, so
        // this is the one judgement not suspended.
        val fixAgeMs = if (lastFixMs == 0L) Long.MAX_VALUE
                       else System.currentTimeMillis() - lastFixMs
        val partingJudgeable = !unreliable ||
            (!underWay && fixAgeMs <= PARTING_MAX_FIX_AGE_MS)

        // Parting is tested FIRST and regardless of speed.
        //
        // It used to sit behind the speed condition below, so once the
        // passenger got off and walked away — a few kilometres an hour — every
        // check returned before reaching it. On one ride the app went on
        // believing the journey continued for thirteen minutes, until the
        // inactivity timer finally stopped it. Walking is exactly when this
        // test matters most.
        if (partingJudgeable) {
            when (checkParted(mps, underWay)) {
                PartingOutcome.REVOKED -> return DEFERRED_CHECK_RETRY_MS
                PartingOutcome.PENDING -> { /* carry on, but come back soon */ }
                PartingOutcome.NONE    -> { }
            }
        }
        val partingSooner = if (partingFirstStamp != 0L) PARTING_RECHECK_MS else null

        if (unreliable) {
            FileLogger.d(TAG, "Degraded positioning — no vehicle decisions" +
                if (partingJudgeable) " (parting still judged)" else "")
            return partingSooner
        }

        // Standing, with nothing identified: nothing can be decided, since a
        // vehicle standing beside us at a stop may not be the one we board.
        // Retried within seconds rather than at the next regular check — the
        // bus usually pulls away long before that, and the first reading
        // after it does is the one that matters. Nothing is fetched meanwhile.
        if (!identified && !underWay) {
            if (!deferLogged) {
                deferLogged = true
                FileLogger.d(TAG, "Vehicle check deferred: ${kmhNow.toInt()} km/h — " +
                    "retrying every ${DEFERRED_CHECK_RETRY_MS / 1000} s until moving")
            }
            return DEFERRED_CHECK_RETRY_MS
        }
        deferLogged = false

        val nowMs = System.currentTimeMillis()
        val previous = if (nowMs - lastReadingMs <= READING_MEMORY_MS) lastInRange else emptyMap()

        val seen = vehicleMatcher.findRidingVehicle(
            lastLat, lastLon, mps, watchTripIds = previous.keys)

        if (seen == null) {
            // Nobody beside us. Whatever was in range before is no longer, so
            // it cannot vouch for anything at the next reading.
            lastInRange = emptyMap()
            lastReadingMs = nowMs
            return partingSooner
        }

        val inRange = seen.inRange
        lastInRange = inRange.associateBy { it.tripId }
        lastReadingMs = nowMs

        // The group the nearest cannot be told apart from: itself, and every
        // vehicle within DECISIVE_MARGIN of it.
        val tied = inRange.filter { it.distanceMetres - seen.distanceMetres < VehicleMatcher.DECISIVE_MARGIN }
        val tiedIds = tied.map { it.tripId }.toSet()

        // Already following one of them: nothing to decide. Once identified,
        // being in range at all is enough — ours need not be the nearest of
        // two running abreast. Before that, the trip picked from the arrivals
        // list is confirmed only when it is among the nearest.
        if (tripId.isNotBlank() &&
            (if (identified) lastInRange.containsKey(tripId) else tripId in tiedIds)) {
            identified = true
            candidateVote = null
            return partingSooner
        }

        // At a standstill an unfamiliar vehicle is not accepted: it may simply
        // have stopped at the same stop, and we may not have boarded it. (Only
        // reached once identified; before that, standing defers the check.)
        if (!underWay) {
            FileLogger.d(TAG, "Stationary — not adopting an unfamiliar vehicle")
            return partingSooner
        }

        // Which of them, if any, this reading speaks for.
        //
        // A reading is decisive when the nearest stands clear of every other
        // vehicle in range by DECISIVE_MARGIN. When it does not, the line the
        // passenger chose breaks the tie if one of the group belongs to it:
        // taking it changes nothing they hear, and should it be wrong, the
        // parting check withdraws it the moment the vehicles separate.
        // Otherwise the reading votes for nobody.
        val chosenLineTied = if (identified) emptyList()
                             else tied.filter { it.routeId == selectedRouteId }
        val pick: VehicleMatcher.Sighting? = when {
            tied.size == 1           -> tied.first()
            chosenLineTied.isNotEmpty() -> chosenLineTied.first().also {
                FileLogger.d(TAG, "Tie broken in favour of the chosen line")
            }
            else -> null
        }

        if (pick == null) {
            FileLogger.d(TAG, "Undecided: " + tied.joinToString(", ") {
                "${it.routeId}/${it.tripId}@${it.distanceMetres.toInt()} m" } + " — no vote")
            return partingSooner
        }

        val candidate = if (pick.tripId == seen.tripId) seen else try {
            vehicleMatcher.describe(pick, seen) ?: return partingSooner
        } catch (e: Exception) { return partingSooner }

        // Confirmation is asked for only where the candidate contradicts what
        // is already believed.
        //
        // A vehicle of the line the passenger chose, seen while nothing has
        // been identified yet, agrees with everything we think: even if it is
        // not the exact vehicle they boarded, the line and direction are the
        // ones already being announced, so nothing they hear can change for
        // the worse. Taking it at once gives them the arrival time and the
        // alighting detection immediately.
        //
        // Anything else — a different line, or a replacement for a vehicle
        // already being followed — is believed only when this decisive
        // reading follows a previous one, from an older report, in which the
        // same vehicle was already within range. A vehicle passing the other
        // way is beside us for one reading and gone by the next; one that
        // reports rarely cannot produce a second, fresher report at all.
        //
        // The previous reading need not have been decisive. Two vehicles
        // running abreast give undecided readings; when they separate, the
        // one still with us is confirmed at once — provided the other is seen
        // in the feed, freshly reported, outside range. That shows the pair
        // really has parted. If the other has merely not reported, the one
        // left beside us may be the stranger and ours the silent one, so the
        // ordinary second reading is waited for instead.
        //
        // "Agrees" means the direction too. A vehicle of the chosen line going
        // the other way passes within range on a narrow street; taken at once,
        // it had "Посоката е коригирана" announced to a passenger travelling
        // exactly as announced. While the direction is still unknown nothing
        // can agree with it, so the ordinary confirmation applies then too.
        val agreesWithBelief = !identified && candidate.routeId == selectedRouteId &&
            headsign.isNotBlank() && candidate.headsign.equals(headsign, ignoreCase = true)

        if (!agreesWithBelief) {
            val before = previous[candidate.tripId]
            val rivals = previous.keys - candidate.tripId
            val rivalsGone = rivals.all { id ->
                val now = seen.watched[id]
                val then = previous[id]
                now != null && then != null && now.timestamp > then.timestamp &&
                    now.distanceMetres > VehicleMatcher.RIDING_WITH_RADIUS
            }
            val confirmed = before != null &&
                before.timestamp != candidate.timestamp && rivalsGone
            if (!confirmed) {
                if (candidateVote != candidate.tripId) {
                    FileLogger.d(TAG, "Candidate ${candidate.routeShortName} at " +
                        "${candidate.distanceMetres.toInt()} m" +
                        (if (inRange.size > 1) " (${inRange.size} in range)" else "") +
                        " — awaiting a second reading")
                }
                candidateVote = candidate.tripId
                return partingSooner
            }
            FileLogger.i(TAG, if (rivals.isEmpty()) "Candidate confirmed on a second reading"
                else "Candidate confirmed: the vehicles beside it have moved away")
        }

        // A vehicle already being followed is not abandoned merely because
        // another one is in range.
        //
        // Applies only once something has been identified, so it cannot get in
        // the way of the first identification — there is nothing to compare
        // against then. Afterwards it matters: our own vehicle reports
        // intermittently, and a gap in its reporting used to be enough for
        // whatever else happened to be nearby to take its place. Switching now
        // requires the newcomer to be nearer than the one we are following, by
        // a margin wide enough not to be scatter.
        if (identified && tripId.isNotBlank()) {
            val ours = vehicleMatcher.sightTrackedVehicle(tripId, lastLat, lastLon, mps)
                ?.distanceMetres
            if (ours != null && ours - candidate.distanceMetres < SWITCH_MARGIN) {
                FileLogger.d(TAG, "Keeping current vehicle: ours ${ours.toInt()} m, " +
                    "candidate ${candidate.distanceMetres.toInt()} m")
                return partingSooner
            }
            if (ours == null) {
                // Not reporting at all. Absence is not evidence that we have
                // left it, so the candidate must at least be right on top of
                // us before it is believed.
                if (candidate.distanceMetres > TAKEOVER_RADIUS) {
                    FileLogger.d(TAG, "Current vehicle silent; candidate at " +
                        "${candidate.distanceMetres.toInt()} m is not close enough to take over")
                    return partingSooner
                }
            }
        }

        candidateVote = null
        adoptVehicle(candidate)

        partingFirstStamp = 0L
        return null
    }

    /**
     * Takes over the line, direction and stop order of the vehicle we have
     * been found to be in, and says so.
     *
     * The passenger is told what is being followed, and — when it is not what
     * they picked — that it differs. There is no separate warning beforehand:
     * one statement of the truth is worth more than a warning about a doubt.
     */
    private suspend fun adoptVehicle(
        v: bg.sofia.transit.data.repository.VehicleMatcher.RidingVehicle
    ): Boolean {
        // The vehicle's own trip is preferred, but it is hardly ever in the
        // static data.
        //
        // CGM issue different trip ids in the live feed from the ones in the
        // timetable: of every vehicle checked against the bundled data, not
        // one full id matched, though the prefixes did — A91-A967 has 111
        // trips published, A84-A2228 has 133, yet the exact ids the vehicles
        // report appear nowhere. So a lookup by full id returns nothing,
        // which is why adoption failed silently every time and no vehicle was
        // ever identified through this path.
        //
        // The line itself is never in doubt; it comes from route_id. The
        // route's own stop order therefore stands in for the missing one,
        // exactly as it does when a journey starts with no vehicle at all.
        var hs = v.headsign
        var stops = try {
            if (v.tripId.isNotBlank())
                gtfsRepo.getRemainingStops(v.tripId, fromSequence = 0)
            else emptyList()
        } catch (e: Exception) {
            FileLogger.w(TAG, "adoptVehicle: stops unavailable: ${e.message}")
            emptyList()
        }

        if (stops.isEmpty() || hs == null) {
            val directions = try {
                gtfsRepo.getDirectionHeadsigns(v.routeId)
            } catch (e: Exception) { emptyList() }

            // Which way it is going has to come from somewhere. The direction
            // already being followed is used when it is one of this line's,
            // and otherwise nothing is assumed — announcing stops in the wrong
            // order would be worse than not adopting at all.
            val fallbackHs = hs
                ?: directions.firstOrNull { it.equals(headsign, ignoreCase = true) }
                ?: return false

            val fallbackStops = try {
                gtfsRepo.getStopsForRouteDirection(v.routeId, fallbackHs)
            } catch (e: Exception) { emptyList() }
            if (fallbackStops.isEmpty()) {
                FileLogger.w(TAG, "Cannot adopt ${v.routeShortName}: no stops for " +
                    "trip ${v.tripId} nor for route towards $fallbackHs")
                return false
            }

            FileLogger.i(TAG, "Trip ${v.tripId} not in static data — " +
                "using route ${v.routeShortName} towards $fallbackHs instead")
            hs = fallbackHs
            stops = fallbackStops
        }

        val latLons = stops.map { sw ->
            val st = try { gtfsRepo.getStopById(sw.stopId) } catch (e: Exception) { null }
            Pair(st?.stopLat ?: 0.0, st?.stopLon ?: 0.0)
        }

        val word = try {
            bg.sofia.transit.util.VehicleLabels
                .singular(v.routeType, gtfsRepo.isTrolleyRoute(v.routeId))
        } catch (e: Exception) { "Превозно средство" }

        val differentLine      = v.routeId != selectedRouteId
        val differentDirection = headsign.isNotBlank() &&
            !hs.equals(headsign, ignoreCase = true)
        val firstTime          = !identified
        // The direction had not been announced yet only if it was unknown.
        val directionUnspoken  = headsign.isBlank()
        // Having said the chosen line is in doubt, confirming it is news too.
        val doubtWasVoiced     = wrongLineNoticeGiven

        FileLogger.i(TAG, "Identified ${v.routeShortName} → $hs " +
            "(trip=${v.tripId}, ${v.distanceMetres.toInt()} m, " +
            "line changed=$differentLine)")

        routeId        = v.routeId
        headsign       = hs
        tripId         = v.tripId
        orderedStops   = stops
        stopLatLon     = latLons
        // Only true when the stops really came from this vehicle's own trip;
        // a stand-in order has other times attached to it.
        stopsMatchTrip = v.headsign != null
        routeLabel     = "$word ${v.routeShortName} → $hs"
        identified     = true

        // Any pending direction work belongs to the discarded assumption.
        candidates = emptyList()
        anchorIdx  = emptyList()

        // Position along this order is unknown, and an alighting stop chosen
        // from the previous one may not exist here.
        awaitingFirstFix  = true
        atStop            = false
        approachAnnounced = false
        currentIdx        = 0
        snapWaitStartedMs = 0L
        // Whatever held for the previous stop order does not hold for this.
        offRoute          = false
        lostSinceMs       = 0L
        detourSinceMs     = 0L
        windowMin.clear()
        unattachedMin     = -1.0
        leftOnFoot        = false
        leavingPeakKmh    = 0.0
        wrongLineNoticeGiven = false
        partingFirstStamp = 0L
        partingPeakKmh    = 0.0
        trackedWithUsMs   = 0L
        alightPendingSinceMs = 0L
        if (differentLine || differentDirection) {
            destinationIdx      = null
            destinationEtaEpoch = null
            etaSource           = EtaSource.NONE
            alightWarningsFired.clear()
            destinationArrivedMs     = 0L
        }

        // The same switch is not announced twice.
        //
        // Under power saving the system killed and restarted the service
        // repeatedly; each restart began again from the line the passenger
        // had chosen, re-identified the same vehicle, and said "проследяването
        // се превключва" all over again — three times in as many minutes for
        // one and the same bus. Remembering the last trip announced makes the
        // repetition silent while leaving a genuine change audible.
        val alreadyAnnounced = lastAnnouncedTripId == v.tripId
        lastAnnouncedTripId = v.tripId

        // Spoken only when it tells the passenger something new. A vehicle
        // of the chosen line in the direction already announced changes
        // nothing they hear, so it passes in silence — "Посока, X" used to be
        // repeated half a minute after the direction had been announced.
        when {
            alreadyAnnounced -> FileLogger.d(TAG, "Same vehicle as last announced — silent")
            differentLine -> announce(
                "Изглежда пътувате с ${word.lowercase()} ${v.routeShortName}, " +
                "посока $hs. Проследяването на спирките се превключва.")
            doubtWasVoiced -> announce(
                "Потвърдено: пътувате с ${word.lowercase()} ${v.routeShortName}, посока $hs.")
            differentDirection -> announce("Посоката е коригирана. Посока $hs.")
            firstTime && directionUnspoken -> announce("Посока, $hs.")
            else -> FileLogger.d(TAG, "Line and direction as announced — silent")
        }

        publishLastKnown()
        restartEtaPolling()
        return true
    }

    /**
     * True when our own position is too sparse or too old to compare against
     * anything that is itself moving.
     */
    private fun positionUnreliable(): Boolean {
        val ageMs = if (lastFixMs == 0L) Long.MAX_VALUE
                    else System.currentTimeMillis() - lastFixMs
        if (ageMs > MAX_FIX_AGE_FOR_DECISIONS_MS) return true
        // A rate this low means the gaps between fixes are long even when the
        // latest one happens to be recent.
        val recent = cadenceHistory.lastOrNull() ?: return false
        return recent <= MIN_CADENCE_FOR_DECISIONS
    }

    /**
     * Says that positioning has degraded — but only once it has stayed that
     * way for a while, and then briefly.
     *
     * Signal dips for a few seconds constantly and announcing each one would
     * be noise; what deserves saying is a spell long enough to explain why
     * the app has gone quiet. The wording is short on purpose, since it
     * repeats: a long sentence wears out at the second hearing.
     */
    private fun noticeDegradedPositioning() {
        // Judged on the average over the last minute, not on how long the
        // present dip has lasted.
        //
        // Counting an unbroken spell missed the case that matters as much:
        // a run of short dips, none of them long enough to mention, which
        // between them leave the tracker unable to decide for half the
        // journey. An average catches both — ten brief outages pull it down
        // exactly as far as one long one.
        if (cadenceHistory.size < CADENCE_WINDOWS) return
        val average = cadenceHistory.average()
        if (average > MIN_CADENCE_FOR_DECISIONS) return

        val now = System.currentTimeMillis()
        if (now - lastDegradedNoticeMs < DEGRADED_NOTICE_INTERVAL_MS) return
        lastDegradedNoticeMs = now
        FileLogger.i(TAG, "Positioning degraded: ${average.toInt()} fixes/min " +
            "over the last minute")
        announce("Слаб сигнал за местоположение.")
    }

    private enum class PartingOutcome { NONE, PENDING, REVOKED }

    /**
     * Has the vehicle gone on without us? The one place where the vehicle's
     * own position decides that the passenger is no longer in it.
     *
     * Two standards, by what we already know:
     *
     *  - At the chosen stop, after "Слизате тук", standing or walking: the
     *    vehicle freshly reported outside riding range is enough, on one
     *    reading, to begin concluding that we got off. The passenger has just been told to get off, and a fresh
     *    report, corrected for its age, of the very bus we are in cannot put
     *    it outside riding range while we stand or crawl along with it. On
     *    the ride that prompted this, the bus had left the walking
     *    passenger's side by 18:10:18, yet tracking ran on until stopped by
     *    hand at 18:19.
     *
     *  - Anywhere else: two readings beyond [PARTED_RADIUS], from two
     *    different reports each at most [PARTING_MAX_REPORT_AGE_SEC] old. One
     *    used to be enough, uncorrected for the report's age, and a single
     *    stale position of the very bus we were sitting in could end the
     *    journey. A report too old neither confirms a parting nor cancels
     *    one; a reading showing the vehicle close again cancels it. What the
     *    parting means then depends on what we did meanwhile: still at
     *    vehicle speed, we were never in that vehicle, and only the
     *    identification is withdrawn — see [revokeIdentification]; standing
     *    or walking, we probably got off.
     *
     * "Probably", in both cases: standing still is also what a passenger
     * does in another vehicle held at a red light just as the one we took
     * for theirs turns away. So getting off is not concluded at once but
     * after ALIGHT_SETTLE_MS — see [beginAlightPending].
     *
     * Every sighting within riding range is also remembered, for the limit
     * at the chosen stop in checkJourneyTimers.
     */
    private suspend fun checkParted(mps: Double, underWay: Boolean): PartingOutcome {
        if (!identified || tripId.isBlank()) {
            partingFirstStamp = 0L
            return PartingOutcome.NONE
        }
        val pending = if (partingFirstStamp != 0L) PartingOutcome.PENDING else PartingOutcome.NONE
        val s = vehicleMatcher.sightTrackedVehicle(tripId, lastLat, lastLon, mps)
            ?: return pending
        val fresh = s.timestamp > 0 && s.ageSec in 0..PARTING_MAX_REPORT_AGE_SEC

        if (s.distanceMetres <= VehicleMatcher.RIDING_WITH_RADIUS) {
            trackedWithUsMs = System.currentTimeMillis()
            if (alightPendingSinceMs != 0L) {
                alightPendingSinceMs = 0L
                FileLogger.i(TAG, "Tracked vehicle with us again " +
                    "(${s.distanceMetres.toInt()} m) — getting off no longer pending")
            }
        }
        // Already concluded, awaiting only ALIGHT_SETTLE_MS: nothing to add.
        if (alightPendingSinceMs != 0L) return PartingOutcome.NONE

        val atChosenStop = destinationIdx != null && destinationArrivedMs != 0L
        val ownFixAccurate = lastAccuracy?.let { it <= DEPARTURE_MAX_ACCURACY } == true
        if (atChosenStop && !underWay && fresh && ownFixAccurate &&
            s.distanceMetres > VehicleMatcher.RIDING_WITH_RADIUS) {
            FileLogger.i(TAG, "At the chosen stop; our vehicle is now " +
                "${s.distanceMetres.toInt()} m away (report ${s.ageSec}s old) " +
                "and we are not riding")
            beginAlightPending(s.distanceMetres, atChosenStop = true)
            return PartingOutcome.NONE
        }

        if (s.distanceMetres <= PARTED_RADIUS) {
            if (partingFirstStamp != 0L) {
                FileLogger.i(TAG, "Tracked vehicle close again (${s.distanceMetres.toInt()} m) — " +
                    "parting cancelled")
            }
            partingFirstStamp = 0L
            partingPeakKmh = 0.0
            return PartingOutcome.NONE
        }

        if (!fresh) {
            FileLogger.d(TAG, "Tracked vehicle ${s.distanceMetres.toInt()} m away, but the " +
                "report is ${s.ageSec}s old — not judged on it")
            return pending
        }

        if (partingFirstStamp == 0L) {
            partingFirstStamp = s.timestamp
            partingPeakKmh = shortSpeedKmh() ?: 0.0
            FileLogger.i(TAG, "Tracked vehicle ${s.distanceMetres.toInt()} m away — " +
                "awaiting a fresh report to confirm")
            return PartingOutcome.PENDING
        }
        if (s.timestamp == partingFirstStamp) return PartingOutcome.PENDING

        // Confirmed on a second, newer report.
        partingFirstStamp = 0L
        val peak = maxOf(partingPeakKmh, shortSpeedKmh() ?: 0.0)
        partingPeakKmh = 0.0

        if (peak >= MIN_SPEED_FOR_IDENTIFY) {
            revokeIdentification("Tracked vehicle left while we kept moving " +
                "(${s.distanceMetres.toInt()} m, our peak ${peak.toInt()} km/h)")
            return PartingOutcome.REVOKED
        }

        FileLogger.i(TAG, "Vehicle left without us (${s.distanceMetres.toInt()} m, " +
            "our peak ${peak.toInt()} km/h)")
        beginAlightPending(s.distanceMetres, atChosenStop)
        return PartingOutcome.NONE
    }

    /**
     * The vehicle has gone on without us while we were not moving. Getting
     * off is concluded only after ALIGHT_SETTLE_MS without vehicle speed —
     * in checkJourneyTimers — and withdrawn meanwhile if we move at vehicle
     * speed (onFix) or the vehicle is found with us again (checkParted).
     */
    private fun beginAlightPending(distance: Double, atChosenStop: Boolean) {
        alightPendingSinceMs = System.currentTimeMillis()
        alightPendingAtChosenStop = atChosenStop
        alightPendingDistance = distance
        FileLogger.i(TAG, "Getting off concluded unless we move at vehicle speed " +
            "within ${ALIGHT_SETTLE_MS / 1000} s")
    }

    /**
     * The vehicle we were following has gone its way while we kept going
     * ours: it was never the one we were in. Tracking returns to what the
     * passenger chose.
     *
     * Spoken only when that changes the line being followed. Withdrawing a
     * vehicle of the chosen line itself changes nothing the passenger hears
     * — the line is the same — and announcing a return to line 94 there would be
     * contradicted a moment later if we turn out to be off that line's route
     * as well. The off-route notice, if due, says what matters.
     */
    private fun revokeIdentification(reason: String) {
        val c = chosen ?: return
        val revokedTrip = tripId
        val sameLine = routeId == c.routeId
        // "Автобус 76" out of "Автобус 76 → Гоце Делчев", for the message:
        // the vehicle word lower-cased, the line number left as it is.
        val revokedName = routeLabel.substringBefore(" → ").let { l ->
            val word = l.substringBefore(' ')
            word.lowercase() + l.removePrefix(word)
        }
        FileLogger.i(TAG, "$reason — identification of $routeLabel withdrawn " +
            "(trip=$revokedTrip)")

        identified = false
        candidateVote = null
        trackedWithUsMs = 0L
        alightPendingSinceMs = 0L
        detourSinceMs = 0L
        lastInRange = emptyMap()
        lastAnnouncedTripId = null
        lastProgressMs = System.currentTimeMillis()

        if (sameLine) {
            // Same line and, in practice, the direction we were moving in:
            // the stop order stays, only the claim to a particular vehicle
            // goes. So does the trip, and with it the live arrival time.
            tripId = if (c.tripId == revokedTrip) "" else c.tripId
            stopsMatchTrip = false
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
        } else {
            tripId = c.tripId
            routeId = c.routeId
            headsign = c.headsign
            routeLabel = c.label
            stopsMatchTrip = c.tripId.isNotBlank()
            orderedStops = c.stops
            stopLatLon = c.latLons
            candidates = c.candidates
            anchorIdx = emptyList()
            anchorLat = 0.0; anchorLon = 0.0
            determinationStartMs = System.currentTimeMillis()
            lastAmbiguityLogMs = 0L
            currentIdx = 0
            atStop = false
            approachAnnounced = false
            awaitingFirstFix = true
            snapWaitStartedMs = 0L
            offRoute = false
            lostSinceMs = 0L
            detourSinceMs = 0L
            windowMin.clear()
            unattachedMin = -1.0
            leftOnFoot = false
            leavingPeakKmh = 0.0
            destinationIdx = null
            destinationEtaEpoch = null
            etaSource = EtaSource.NONE
            alightWarningsFired.clear()
            destinationArrivedMs = 0L
            wrongLineNoticeGiven = false
            // Worded as the counterpart of the switch announcement ("Изглежда
            // пътувате с автобус 76…"), so that it plainly undoes what the
            // passenger heard then, in the same words.
            announce("Изглежда не пътувате с $revokedName. " +
                "Следенето се връща към линия ${c.shortName}.")
        }

        // Already off the route: we are on neither that vehicle nor, it now
        // appears, the chosen line.
        if (offRoute) {
            lostSinceMs = System.currentTimeMillis()
            giveWrongLineNotice("off route when the vehicle was withdrawn")
        }
        publishLastKnown()
        restartEtaPolling()
    }

    /**
     * "Изглежда не пътувате с линия X" — said once, on moving away from the
     * chosen route while no vehicle is identified.
     *
     * That is the only sign it is given on. There was a second — two vehicles
     * of other lines beside us twice running, for a line that publishes
     * positions — but a line publishing positions does not mean the vehicle
     * we are in does: with ours silent in a jam between two others, it told
     * a passenger on the right line that they were on the wrong one. Moving
     * away from the route rests on our own positions, which are always there.
     */
    private fun giveWrongLineNotice(reason: String) {
        if (wrongLineNoticeGiven || identified) return
        val name = chosen?.shortName ?: return
        wrongLineNoticeGiven = true
        FileLogger.i(TAG, "Wrong-line notice: $reason")
        announce("Изглежда не пътувате с линия $name. Изчаква се установяване на линията.")
    }

    /**
     * Watches for travel away from the route: every one of the next stops
     * further than the closest we have been to it by [OFF_ROUTE_GROWTH],
     * while moving at vehicle speed. Returns true when that has just
     * happened, having switched tracking into the off-route state.
     *
     * Walking does not count. A passenger who got off unnoticed and walks
     * away is not on another line, and telling them so would be wrong.
     */
    private fun watchOffRoute(loc: Location, end: Int): Boolean {
        if (offRoute) return false
        val window = currentIdx..end
        windowMin.keys.retainAll { it in window }

        var allGrown = true
        var least = Double.MAX_VALUE
        for (i in window) {
            val d = distTo(loc, i)
            val m = windowMin[i]
            if (m == null || d < m) windowMin[i] = d
            val growth = d - (windowMin[i] ?: d)
            if (growth < least) least = growth
            if (growth < OFF_ROUTE_GROWTH) allGrown = false
        }
        if (!allGrown) return false
        val kmh = shortSpeedKmh() ?: 0.0
        if (kmh < MIN_SPEED_FOR_IDENTIFY) return false

        enterOffRoute("every stop ahead ${least.toInt()}+ m further than it was, " +
            "at ${kmh.toInt()} km/h")
        return true
    }

    /**
     * The off-route watch for while we are not yet attached to any stop.
     *
     * Attaching needs us to be closing on the nearest stop ahead, so travel
     * that leads away from the route from the outset never attaches — and
     * [watchOffRoute], which works from the attached stop onwards, never
     * starts. Tracking then waited in silence for as long as the ride
     * lasted: after returning to the chosen line from a vehicle that was not
     * ours, or when tracking was begun aboard a different line.
     *
     * The same rule is applied to the nearest stop ahead instead of to the
     * next few. On the route, attaching takes seconds, so this has no time
     * to act; off it, the distance grows steadily. Only distances taken while
     * moving at vehicle speed count, so a walk to the stop before boarding,
     * or around it while waiting, cannot build up a false lead.
     */
    private fun watchUnattached(nearestAhead: Double, stopName: String) {
        if (offRoute) return
        val kmh = shortSpeedKmh() ?: 0.0
        if (kmh < MIN_SPEED_FOR_IDENTIFY) return
        if (unattachedMin < 0 || nearestAhead < unattachedMin) {
            unattachedMin = nearestAhead
            return
        }
        if (nearestAhead - unattachedMin < OFF_ROUTE_GROWTH) return
        enterOffRoute("not attached; nearest stop ahead $stopName ${nearestAhead.toInt()} m, " +
            "closest was ${unattachedMin.toInt()} m, at ${kmh.toInt()} km/h")
    }

    /**
     * Stops announcing, and waits to be back within reach of the route.
     *
     * With a vehicle identified this is a detour — road works, a diversion
     * — and passes in silence: the line is known, and the parting check
     * still notices if we leave the vehicle. With nothing identified it is
     * evidence that the chosen line is not ours, and the passenger is told.
     */
    private fun enterOffRoute(detail: String) {
        offRoute = true
        awaitingFirstFix = true
        snapWaitStartedMs = 0L
        atStop = false
        approachAnnounced = false
        windowMin.clear()
        unattachedMin = -1.0
        lastOffRouteLogMs = System.currentTimeMillis()
        if (identified) {
            detourSinceMs = System.currentTimeMillis()
            FileLogger.i(TAG, "Off the vehicle's route ($detail) — taken as a detour; " +
                "stop announcements paused")
        } else {
            lostSinceMs = System.currentTimeMillis()
            FileLogger.i(TAG, "Off the chosen route ($detail); stop announcements paused")
            giveWrongLineNotice("moving away from the route")
        }
    }

    /**
     * What follows leaving a stop at walking pace. Returns true when this fix
     * has been dealt with here; false to let the ordinary stop logic process
     * it.
     *
     * Only one outcome is looked for: movement at vehicle speed, on an
     * accurate fix, which means the vehicle was crawling out of the stop in
     * traffic with us aboard — the departure is then carried out as usual.
     * Anything else is taken as the passenger having got off, and nothing is
     * announced: no "Следваща спирка", no "Спирката за слизане е подмината".
     * Ending the journey is left to the timers — see checkJourneyTimers —
     * and to the parting check, since the automatic end exists to spare the
     * battery, not to time the alighting to the second.
     */
    private fun handleLeftOnFoot(loc: Location, accurateFix: Boolean): Boolean {
        val kmh = shortSpeedKmh() ?: 0.0
        if (!accurateFix || kmh < MIN_SPEED_FOR_IDENTIFY ||
            footStopIdx !in orderedStops.indices) {
            publish(distance = null)
            return true
        }
        leftOnFoot = false
        FileLogger.i(TAG, "Vehicle speed (${kmh.toInt()} km/h) after leaving " +
            "${orderedStops[footStopIdx].stopName} slowly — a crawl in traffic, not alighting")
        if (currentIdx < orderedStops.lastIndex) {
            currentIdx += 1
            approachAnnounced = false
            announce("Следваща спирка, ${orderedStops[currentIdx].stopName}.")
            suppressRedundantApproach(loc)
        }
        return false
    }

    /**
     * The limits that end a journey on their own, all looked at here, on a
     * timer of their own rather than on each fix.
     *
     * That is the point of gathering them. Checked on each fix, none of them
     * ran when fixes stopped coming: tracking left on in a building without
     * signal ran for as long as the building was stood in, and not even
     * "Слаб сигнал" was said, since that too was computed from fixes. And
     * with each limit checked at a different place in the fix handling, some
     * could not run in states that returned early — which is how two of them
     * came to race each other before.
     *
     * Each is independent of the others and of how often fixes arrive; the
     * first to fall due ends the journey. The one limit not here is giving
     * up on attaching to the route for want of accuracy (accuracyGoodEnoughToSnap),
     * which is a judgement about the fixes themselves and so is made on them.
     */
    private fun startJourneyTimers() {
        timerJob?.cancel()
        timersStartMs = System.currentTimeMillis()
        lastUsableFixMs = 0L
        timerJob = serviceScope.launch {
            while (isActive) {
                delay(TIMER_TICK_MS)
                withContext(Dispatchers.Main) { checkJourneyTimers() }
            }
        }
    }

    private fun checkJourneyTimers() {
        if (timersStartMs == 0L) return
        val now = System.currentTimeMillis()

        // Signal. Fixes too sparse to decide by, or none at all for half a
        // minute, are said; no usable fix for ten minutes ends tracking —
        // nothing can be announced without one.
        noticeDegradedPositioning()
        val sinceFix = now - (if (lastFixMs != 0L) lastFixMs else timersStartMs)
        if (sinceFix >= NO_FIX_NOTICE_MS &&
            now - lastDegradedNoticeMs >= DEGRADED_NOTICE_INTERVAL_MS) {
            lastDegradedNoticeMs = now
            FileLogger.i(TAG, "No position for ${sinceFix / 1000}s")
            announce("Слаб сигнал за местоположение.")
        }
        val sinceUsable = now - (if (lastUsableFixMs != 0L) lastUsableFixMs else timersStartMs)
        if (sinceUsable >= NO_USABLE_FIX_TIMEOUT_MS) {
            end("No usable position for ${sinceUsable / 60_000} min",
                "Няма сигнал за местоположение. Следенето се прекратява.")
            return
        }

        // Never boarded: still at the start, half an hour on.
        if (movingSinceMs == 0L && now - lastProgressMs > WAITING_TIMEOUT_MS) {
            end("Journey never started", "Пътуването не започна. Следенето се прекратява.")
            return
        }

        // Direction still unknown after ten minutes.
        if (candidates.isNotEmpty() && determinationStartMs != 0L &&
            now - determinationStartMs > INACTIVITY_TIMEOUT_MS) {
            end("Direction not determined in 10 min",
                "Посоката не беше определена. Следенето се прекратява.")
            return
        }

        // Getting off pending, and no vehicle speed since: we got off.
        if (alightPendingSinceMs != 0L && now - alightPendingSinceMs >= ALIGHT_SETTLE_MS) {
            val atChosen = alightPendingAtChosenStop
            FileLogger.i(TAG, "No vehicle speed for ${ALIGHT_SETTLE_MS / 1000} s after the " +
                "vehicle left (${alightPendingDistance.toInt()} m) — got off")
            if (atChosen) {
                // Silent: "Слизате тук" has been said.
                _events.tryEmit(JourneyEvent.DestinationReached)
                endJourney()
            } else {
                end("Got off", "Изглежда слязохте. Следенето се прекратява.")
            }
            return
        }

        // At the chosen stop. "Слизате тук" has been said; if the phone has
        // not since been carried off at vehicle speed — which would have
        // been a departure, clearing the destination — the passenger got off.
        // Ends silently: they already know they have arrived.
        //
        // Unless the vehicle we are following is still where we are. Speed
        // alone cannot tell a passenger who stayed aboard a bus crawling in a
        // jam from one standing on the pavement; the vehicle's own position
        // can, when it reports. When it reports that it has gone, the end
        // comes sooner still — see checkParted; this limit is for when it
        // does not report at all, and speed is all there is.
        if (destinationArrivedMs != 0L && destinationIdx != null &&
            now - destinationArrivedMs >= DESTINATION_DWELL_MS) {
            if (vehicleWithUs(now)) {
                if (now - lastDwellHoldLogMs >= 60_000L) {
                    lastDwellHoldLogMs = now
                    FileLogger.d(TAG, "At the chosen stop 5+ min, but the vehicle is " +
                        "still with us — not ending")
                }
            } else {
                FileLogger.i(TAG, "5 min at the chosen stop without departing — journey over")
                _events.tryEmit(JourneyEvent.DestinationReached)
                endJourney()
                return
            }
        }

        // Off the identified vehicle's route, and the vehicle not seen with us
        // for DETOUR_PROOF_MS: not a detour of ours. Withdrawn as at a parting;
        // tracking returns to the passenger's choice.
        if (offRoute && identified && detourSinceMs != 0L &&
            now - maxOf(detourSinceMs, trackedWithUsMs) > DETOUR_PROOF_MS) {
            revokeIdentification("Off its route and not seen with us for " +
                "${DETOUR_PROOF_MS / 60_000} min")
            return
        }

        // Off the chosen route, nothing identified.
        if (offRoute && !identified && lostSinceMs != 0L &&
            now - lostSinceMs > LOST_TIMEOUT_MS) {
            end("Off route for 10 min with no vehicle identified",
                "Линията не беше установена. Изберете линията отново.")
            return
        }

        // No progress along the route. Counted from the first movement
        // rather than from the first stop reached: tied to reaching a stop,
        // the timer never started for a journey that made no progress from
        // the outset. Not applied off the route, which has its own limit
        // above with a truer message, nor while tracking is still finding its
        // place on the route.
        //
        // Nor while the vehicle we follow is still with us: that is a ride
        // held up in traffic, not a journey abandoned — the same reason the
        // limit at the chosen stop above waits.
        if (movingSinceMs != 0L && orderedStops.isNotEmpty() &&
            !offRoute && !awaitingFirstFix && !vehicleWithUs(now) &&
            now - lastProgressMs > INACTIVITY_TIMEOUT_MS) {
            // Having left a stop on foot, that is what the silence was.
            if (leftOnFoot) {
                end("No progress for 10 min since leaving a stop on foot",
                    "Изглежда слязохте. Следенето се прекратява.")
            } else {
                end("No progress for 10 min", "Няма движение по маршрута. Следенето е спряно.")
            }
        }
    }

    /** The vehicle being followed was within riding range at a recent reading. */
    private fun vehicleWithUs(now: Long): Boolean =
        identified && now - trackedWithUsMs <= VEHICLE_BESIDE_MEMORY_MS

    private fun end(why: String, message: String) {
        FileLogger.i(TAG, "$why — ending journey automatically")
        announce(message)
        _events.tryEmit(JourneyEvent.RouteEnded)
        endJourney()
    }

    /** Clears everything identification keeps between readings. */
    private fun resetIdentificationState() {
        candidateVote = null
        lastInRange = emptyMap()
        lastReadingMs = 0L
        deferLogged = false
        partingFirstStamp = 0L
        partingPeakKmh = 0.0
        wrongLineNoticeGiven = false
        offRoute = false
        lostSinceMs = 0L
        detourSinceMs = 0L
        windowMin.clear()
        unattachedMin = -1.0
        lastOffRouteLogMs = 0L
        departureStreak = 0
        leavingPeakKmh = 0.0
        leftOnFoot = false
        footStopIdx = 0
        trackedWithUsMs = 0L
        lastDwellHoldLogMs = 0L
        alightPendingSinceMs = 0L
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
            else -> { departureStreak = 0; return }
        }
        // 25 m/s is 90 km/h — above anything a city bus or tram does, so a
        // higher reading is a bad fix and must not skew the average.
        if (mps.isNaN() || mps > MAX_PLAUSIBLE_SPEED_MPS) { departureStreak = 0; return }

        // Departure needs several accurate fixes in a row above the
        // threshold, not one — see DEPARTURE_CONFIRM_FIXES.
        if (movingSinceMs == 0L) {
            val accurate = loc.hasAccuracy() && loc.accuracy <= DEPARTURE_MAX_ACCURACY
            departureStreak =
                if (accurate && mps * 3.6 >= MIN_SPEED_FOR_FOREIGN_CHECK) departureStreak + 1 else 0
            if (departureStreak >= DEPARTURE_CONFIRM_FIXES) {
                movingSinceMs = System.currentTimeMillis()
                FileLogger.i(TAG, "Departure detected: $departureStreak fixes in a row " +
                    "at ${(mps * 3.6).toInt()} km/h and above, ±${loc.accuracy.toInt()} m")
            }
        }
        speedSamples.addLast(mps)
        while (speedSamples.size > SPEED_SAMPLE_COUNT) speedSamples.removeFirst()
    }

    /** Average of the last few samples in km/h — whether we are moving now. */
    private fun shortSpeedKmh(): Double? =
        if (speedSamples.isEmpty()) null
        else speedSamples.takeLast(SHORT_SPEED_SAMPLE_COUNT).average() * 3.6

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
            cadenceHistory.addLast(perMin.toInt())
            while (cadenceHistory.size > CADENCE_WINDOWS) cadenceHistory.removeFirst()
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
