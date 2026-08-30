package ai.safescreen.bench

import android.graphics.Bitmap
import android.os.SystemClock
import ai.safescreen.SafeScreenEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Collections

data class BenchmarkResult(
    val backend: String,
    val n: Int,
    val avgMs: Double,
    val p50Ms: Long,
    val fps: Double,
    val avgPowerMw: Double,
    val energyPerInferenceMj: Double,
    val inferencesPerJoule: Double,
    val pluggedIn: Boolean,
)

/**
 * Fixed-loop benchmark producing the 40% "Technical" numbers: latency, throughput, and a whole-device
 * energy estimate. Latency/throughput are always valid; energy is only meaningful UNPLUGGED.
 * Measurement method follows DeepEn2023 (sample power over a fixed inference loop).
 */
object Benchmark {
    suspend fun run(
        engine: SafeScreenEngine,
        power: PowerMeter,
        sample: Bitmap,
        n: Int = 100,
        warmup: Int = 10,
    ): BenchmarkResult = coroutineScope {
        power.calibrate()
        repeat(warmup) { engine.analyzeBitmap(sample, "bench-warm") }

        val samples = Collections.synchronizedList(ArrayList<Double>())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                val mw = power.powerMilliWatts()
                if (mw > 50.0) samples.add(mw)
                delay(200)
            }
        }

        val lat = ArrayList<Long>(n)
        val t0 = SystemClock.elapsedRealtime()
        repeat(n) {
            val s = SystemClock.elapsedRealtime()
            engine.analyzeBitmap(sample, "bench")
            lat.add(SystemClock.elapsedRealtime() - s)
        }
        val totalMs = SystemClock.elapsedRealtime() - t0
        sampler.cancelAndJoin()

        lat.sort()
        val avgMs = lat.average()
        val p50 = lat[lat.size / 2]
        val fps = if (totalMs > 0) n * 1000.0 / totalMs else 0.0
        val avgPw = synchronized(samples) { if (samples.isEmpty()) 0.0 else samples.average() }
        val energyPerInfMj = if (n > 0) avgPw * (totalMs / 1000.0) / n else 0.0
        val infPerJoule = if (energyPerInfMj > 0) 1000.0 / energyPerInfMj else 0.0

        BenchmarkResult(
            backend = engine.backend,
            n = n, avgMs = avgMs, p50Ms = p50, fps = fps,
            avgPowerMw = avgPw, energyPerInferenceMj = energyPerInfMj,
            inferencesPerJoule = infPerJoule, pluggedIn = power.isPluggedIn(),
        )
    }
}
