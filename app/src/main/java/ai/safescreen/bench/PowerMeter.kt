package ai.safescreen.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlin.math.abs

/**
 * Whole-device power estimate from the battery fuel gauge. Auto-calibrates for Samsung's sign/unit
 * quirks: CURRENT_NOW is spec'd as µA with positive=charging, but Samsung devices often flip the sign
 * and/or report mA. This is NOT NPU-isolated power and is only valid while UNPLUGGED (USB charging makes
 * battery current meaningless). See docs/NPU-EFFICIENCY-RESEARCH.md.
 */
class PowerMeter(private val context: Context) {
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private var unitToMicroAmps = 1.0 // 1.0 if device reports µA, 1000.0 if it reports mA

    private fun rawCurrent(): Int = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun isPluggedIn(): Boolean =
        (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0

    /** Detect the current unit once (implausibly large magnitude ⇒ device reports mA, scale to µA). */
    fun calibrate() {
        val mag = abs(rawCurrent().toDouble())
        unitToMicroAmps = if (mag > 500_000.0) 1000.0 else 1.0
    }

    fun voltageVolts(): Double =
        (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000.0

    /** Instantaneous whole-device power in milliwatts (absolute magnitude). */
    fun powerMilliWatts(): Double {
        val amps = abs(rawCurrent() * unitToMicroAmps) / 1_000_000.0
        return amps * voltageVolts() * 1000.0
    }
}
