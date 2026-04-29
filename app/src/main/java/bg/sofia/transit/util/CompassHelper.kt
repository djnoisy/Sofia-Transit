package bg.sofia.transit.util

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlin.math.abs

/**
 * Wraps Android's accelerometer + magnetometer to produce a heading in
 * degrees (0 = North, clockwise), emitted as a Flow.
 *
 * Uses SENSOR_DELAY_NORMAL (~200 ms) and downstream `sample(250)` to keep
 * UI updates at ≤ 4 Hz. This is more than sufficient for a compass UI and
 * dramatically reduces CPU/battery cost on slow devices compared to the
 * previous SENSOR_DELAY_UI (~16 ms = 60 Hz).
 *
 * Also drops emissions where the heading hasn't changed by at least 2°,
 * eliminating useless recompositions of the nearby-stops list when the
 * device is sitting still.
 */
object CompassHelper {

    private const val MIN_HEADING_CHANGE_DEG = 2f
    private const val SAMPLE_PERIOD_MS = 250L

    @OptIn(FlowPreview::class)
    fun headingFlow(context: Context): Flow<Float> = callbackFlow {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag   = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        if (accel == null || mag == null) {
            // No compass hardware – emit a single 0 (North) and stay open
            trySend(0f)
            awaitClose {}
            return@callbackFlow
        }

        val gravity     = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val R           = FloatArray(9)
        val I           = FloatArray(9)
        val orientation = FloatArray(3)
        val alpha       = 0.85f   // low-pass filter coefficient (smoother)

        var hasGravity = false
        var hasMag     = false

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0]
                        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1]
                        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2]
                        hasGravity = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        geomagnetic[0] = alpha * geomagnetic[0] + (1 - alpha) * event.values[0]
                        geomagnetic[1] = alpha * geomagnetic[1] + (1 - alpha) * event.values[1]
                        geomagnetic[2] = alpha * geomagnetic[2] + (1 - alpha) * event.values[2]
                        hasMag = true
                    }
                }
                if (hasGravity && hasMag &&
                    SensorManager.getRotationMatrix(R, I, gravity, geomagnetic)) {
                    SensorManager.getOrientation(R, orientation)
                    val azimuth = (Math.toDegrees(orientation[0].toDouble()).toFloat() + 360) % 360
                    trySend(azimuth)
                }
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        // SENSOR_DELAY_NORMAL (~200 ms) is plenty for a compass UI
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_NORMAL)
        sm.registerListener(listener, mag,   SensorManager.SENSOR_DELAY_NORMAL)

        awaitClose { sm.unregisterListener(listener) }
    }
        // 1) Bound to ≤ 4 Hz (one update every 250 ms)
        .sample(SAMPLE_PERIOD_MS)
        // 2) Drop micro-movements (< 2°) — phone laid on table doesn't fire
        .distinctUntilChanged { old, new ->
            val diff = abs(((new - old + 540) % 360) - 180)
            diff < MIN_HEADING_CHANGE_DEG
        }
}
