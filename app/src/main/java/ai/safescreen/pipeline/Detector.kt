package ai.safescreen.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import kotlin.math.exp

data class NsfwResult(val score: Float, val latencyMs: Long, val backend: String)

interface NsfwDetector { fun classify(bitmap: Bitmap): NsfwResult }

// ---------- ExecuTorch-backed detector (used when a .pte is present) ----------

class ExecuTorchNsfwDetector(
    private val runtime: ModelRuntime,
    private val cfg: ModelConfig,
    private val fallback: NsfwDetector = HeuristicNsfwDetector(),
) : NsfwDetector {
    private val pre = Preprocessor(cfg.inputSize, cfg.mean, cfg.std)
    @Volatile private var degraded = false
    override fun classify(bitmap: Bitmap): NsfwResult {
        if (degraded) return fallback.classify(bitmap)
        return try {
            val t0 = SystemClock.elapsedRealtime()
            val logits = runtime.run(pre.toTensor(bitmap), pre.shape)
            NsfwResult(scoreOf(logits, cfg), SystemClock.elapsedRealtime() - t0, runtime.backend)
        } catch (t: Throwable) {
            Log.e("ExecuTorchNsfwDetector", "forward failed -> heuristic fallback: ${t.message}")
            degraded = true
            fallback.classify(bitmap)
        }
    }
}

private fun scoreOf(logits: FloatArray, cfg: ModelConfig): Float {
    if (cfg.outputIsProbability) {
        var s = 0f
        for (i in cfg.positiveIndices) s += logits[i]
        return s.coerceIn(0f, 1f)
    }
    if (logits.size == 1) return 1f / (1f + exp(-logits[0]))
    var maxL = Float.NEGATIVE_INFINITY
    for (l in logits) if (l > maxL) maxL = l
    var sum = 0f
    for (l in logits) sum += exp(l - maxL)
    var pos = 0f
    for (i in cfg.positiveIndices) pos += exp(logits[i] - maxL)
    return pos / sum
}

// ---------- Model-free heuristic (so the pipeline runs and demos before a .pte exists) ----------

/** NSFW proxy = fraction of skin-tone pixels. An honest placeholder, not a real classifier. */
class HeuristicNsfwDetector : NsfwDetector {
    override fun classify(bitmap: Bitmap): NsfwResult {
        val t0 = SystemClock.elapsedRealtime()
        return NsfwResult(skinRatio(bitmap), SystemClock.elapsedRealtime() - t0, "HEURISTIC")
    }
}

/** Fraction of skin-tone pixels (YCbCr chrominance rule). Used as the heuristic NSFW backstop. */
fun skinRatio(bitmap: Bitmap): Float {
    val step = 4
    val w = bitmap.width
    val h = bitmap.height
    if (w == 0 || h == 0) return 0f
    var skin = 0
    var total = 0
    var y = 0
    while (y < h) {
        var x = 0
        while (x < w) {
            val p = bitmap.getPixel(x, y)
            val r = Color.red(p); val g = Color.green(p); val b = Color.blue(p)
            // YCbCr skin-chrominance rule — tone-invariant, so it also covers dark skin. Warm-scene false
            // positives (sunset/autumn/wood also look "skin") are handled downstream by gating the skin term
            // on the ML model having some signal — see SafeScreenEngine.analyzeScreen.
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (cb in 77f..127f && cr in 133f..173f) skin++
            total++
            x += step
        }
        y += step
    }
    return if (total == 0) 0f else skin.toFloat() / total
}

// ---------- Factory: prefer models, fall back to heuristic ----------

object DetectorFactory {
    private const val TAG = "DetectorFactory"

    data class Detectors(
        val nsfw: NsfwDetector,
        val usingModels: Boolean,
        val backend: String,
        /** True when the NSFW model is the calibrated Marqo classifier (trust its score directly;
         *  no skin backstop / UI-FP cap, lower blur threshold). False for the weak MobileNetV4. */
        val nsfwCalibrated: Boolean = false,
    )

    fun create(context: Context): Detectors {
        // NSFW model preference: Marqo on QNN/NPU  >  Marqo on CPU  >  MobileNetV4 on CPU.
        val nsfwRt: ModelRuntime?
        val nsfwCfg: ModelConfig
        val marqoQnnRt = ExecuTorchRuntime.tryLoad(context, ModelConfig.NSFW_MARQO_QNN.assetPath, "QNN/HTP", qnn = true)
        if (marqoQnnRt != null) {
            nsfwRt = marqoQnnRt; nsfwCfg = ModelConfig.NSFW_MARQO_QNN
        } else {
            val marqoRt = ExecuTorchRuntime.tryLoad(context, ModelConfig.NSFW_MARQO.assetPath)
            if (marqoRt != null) {
                nsfwRt = marqoRt; nsfwCfg = ModelConfig.NSFW_MARQO
            } else {
                nsfwRt = ExecuTorchRuntime.tryLoad(context, ModelConfig.NSFW.assetPath); nsfwCfg = ModelConfig.NSFW
            }
        }
        val calibrated = nsfwCfg === ModelConfig.NSFW_MARQO_QNN || nsfwCfg === ModelConfig.NSFW_MARQO
        val nsfw: NsfwDetector =
            if (nsfwRt != null) ExecuTorchNsfwDetector(nsfwRt, nsfwCfg) else HeuristicNsfwDetector()
        val usingModels = nsfwRt != null
        val backend = nsfwRt?.backend ?: "HEURISTIC"
        Log.i(TAG, "Detectors ready (models=$usingModels, backend=$backend, nsfwModel=${nsfwCfg.assetPath}, calibrated=$calibrated)")
        return Detectors(nsfw, usingModels, backend, nsfwCalibrated = calibrated)
    }
}
