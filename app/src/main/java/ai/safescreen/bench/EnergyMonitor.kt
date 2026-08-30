package ai.safescreen.bench

import android.content.Context
import android.os.BatteryManager
import android.os.SystemClock

/**
 * Live, always-on energy estimate for the running protection service. Power comes from the battery
 * fuel gauge, so it is WHOLE-DEVICE, not NPU-isolated (true NPU-isolated energy needs Snapdragon
 * Profiler — that is the 0.2 ms / per-inference lab number). The marginal "protection overhead" is
 * power above the lowest sustained idle baseline observed since start. Energy figures (overhead,
 * mJ/inference, projected hours) are only meaningful UNPLUGGED — USB charging makes battery current
 * meaningless. See docs/NPU-EFFICIENCY-RESEARCH.md.
 */
class EnergyMonitor(context: Context) {
    private val power = PowerMeter(context)
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    @Volatile var batteryMw = 0.0; private set
    @Volatile var batteryLevel = 0; private set
    @Volatile var pluggedIn = false; private set
    @Volatile var latencyMs = 0L; private set
    @Volatile var backend = "—"

    private var baselineMw = Double.MAX_VALUE
    private var latEwma = 0.0
    private val window = ArrayDeque<Long>() // inference timestamps (ms), trimmed to WINDOW_MS

    fun start() = power.calibrate()

    /** One NPU inference completed; feeds the live latency EWMA and the actual-rate window. */
    @Synchronized
    fun recordInference(latency: Long) {
        val now = SystemClock.elapsedRealtime()
        window.addLast(now)
        trim(now)
        latEwma = if (latEwma == 0.0) latency.toDouble() else 0.3 * latency + 0.7 * latEwma
        latencyMs = latEwma.toLong().coerceAtLeast(0)
    }

    /** Refresh power + battery readings; call ~1/s from the service ticker. */
    fun samplePower() {
        pluggedIn = power.isPluggedIn()
        batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val mw = power.powerMilliWatts()
        if (mw > 50.0) {
            batteryMw = if (batteryMw == 0.0) mw else 0.4 * mw + 0.6 * batteryMw
            // Idle floor: adapt down quickly, never up — approximates power with no active scanning.
            baselineMw = if (baselineMw == Double.MAX_VALUE) batteryMw
            else if (batteryMw < baselineMw) batteryMw else baselineMw
        }
    }

    /** Compute throughput the NPU is capable of at the measured latency (frames/sec). */
    fun computeFps(): Double = if (latencyMs > 0) 1000.0 / latencyMs else 0.0

    /** Actual inferences per second over the trailing window (reflects throttle + content change). */
    @Synchronized
    fun inferencesPerSec(): Double {
        trim(SystemClock.elapsedRealtime())
        return window.size * 1000.0 / WINDOW_MS
    }

    /** Marginal whole-device power attributable to running protection (mW). */
    fun overheadMw(): Double =
        if (baselineMw == Double.MAX_VALUE) 0.0 else (batteryMw - baselineMw).coerceAtLeast(0.0)

    /** Marginal energy per NPU inference (mJ) = overhead power ÷ actual inference rate. */
    fun mjPerInference(): Double {
        val ips = inferencesPerSec()
        return if (ips > 0.0 && !pluggedIn) overheadMw() / ips else 0.0
    }

    fun inferencesPerJoule(): Double {
        val mj = mjPerInference()
        return if (mj > 0.0) 1000.0 / mj else 0.0
    }

    /** Rough projected screen-on hours at the current whole-device draw. */
    fun projectedHours(): Double {
        if (batteryMw <= 0.0 || pluggedIn) return 0.0
        val batteryWh = BATTERY_MAH / 1000.0 * NOMINAL_V
        return batteryWh / (batteryMw / 1000.0)
    }

    private fun trim(now: Long) {
        while (window.isNotEmpty() && now - window.first() > WINDOW_MS) window.removeFirst()
    }

    private companion object {
        const val WINDOW_MS = 5000L
        const val BATTERY_MAH = 5000.0 // S25 Ultra
        const val NOMINAL_V = 3.85
    }
}
