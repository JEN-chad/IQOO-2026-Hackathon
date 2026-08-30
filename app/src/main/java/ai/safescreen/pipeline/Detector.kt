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

internal fun scoreOf(logits: FloatArray, cfg: ModelConfig): Float {
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
    val score = (pos / sum).coerceIn(0f, 1f)

    if (logits.size == 5) {
        val p0 = exp(logits[0] - maxL) / sum
        val p1 = exp(logits[1] - maxL) / sum
        val p2 = exp(logits[2] - maxL) / sum
        val p3 = exp(logits[3] - maxL) / sum
        val p4 = exp(logits[4] - maxL) / sum
        runCatching {
            Log.i(
                "SAFESCREEN_MODEL_DEBUG",
                "raw_0=${"%.3f".format(logits[0])} raw_1=${"%.3f".format(logits[1])} raw_2=${"%.3f".format(logits[2])} raw_3=${"%.3f".format(logits[3])} raw_4=${"%.3f".format(logits[4])} " +
                    "prob_0=${"%.3f".format(p0)} prob_1=${"%.3f".format(p1)} prob_2=${"%.3f".format(p2)} prob_3=${"%.3f".format(p3)} prob_4=${"%.3f".format(p4)} " +
                    "nsfw_score=${"%.3f".format(score)} classification=${if (score >= 0.15f) "SUSPICIOUS" else "SAFE"} threshold=0.150"
            )
        }
    } else if (logits.size == 2) {
        val p0 = exp(logits[0] - maxL) / sum
        val p1 = exp(logits[1] - maxL) / sum
        runCatching {
            Log.i(
                "SAFESCREEN_MODEL_DEBUG",
                "MARQO raw_0=${"%.3f".format(logits[0])} raw_1=${"%.3f".format(logits[1])} " +
                    "prob_0(NSFW)=${"%.3f".format(p0)} prob_1(SFW)=${"%.3f".format(p1)} " +
                    "nsfw_score=${"%.3f".format(score)}"
            )
        }
    }

    return score
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

// ---------- Factory: prefer tiered models, fall back to heuristic ----------

object DetectorFactory {
    private const val TAG = "DetectorFactory"

    data class Detectors(
        val fastNsfw: NsfwDetector,
        val validatorNsfw: NsfwDetector?,
        val usingModels: Boolean,
        val backend: String,
        val nsfwCalibrated: Boolean = true,
    )

    fun create(context: Context): Detectors {
        val mobilenetRt = ExecuTorchRuntime.tryLoad(context, ModelConfig.NSFW_MOBILENET.assetPath)
        val marqoRt = ExecuTorchRuntime.tryLoad(context, ModelConfig.NSFW_MARQO.assetPath)

        val fastDetector: NsfwDetector = if (mobilenetRt != null) {
            ExecuTorchNsfwDetector(mobilenetRt, ModelConfig.NSFW_MOBILENET)
        } else if (marqoRt != null) {
            ExecuTorchNsfwDetector(marqoRt, ModelConfig.NSFW_MARQO)
        } else {
            HeuristicNsfwDetector()
        }

        val validatorDetector: NsfwDetector? = if (mobilenetRt != null && marqoRt != null) {
            ExecuTorchNsfwDetector(marqoRt, ModelConfig.NSFW_MARQO)
        } else null

        val usingModels = mobilenetRt != null || marqoRt != null
        val backend = if (mobilenetRt != null && marqoRt != null) {
            "MobileNetV4+Marqo(Tiered)"
        } else if (mobilenetRt != null) {
            "MobileNetV4"
        } else if (marqoRt != null) {
            "Marqo"
        } else {
            "HEURISTIC"
        }

        Log.i(TAG, "Detectors ready (models=$usingModels, backend=$backend, tiered=${validatorDetector != null})")
        return Detectors(
            fastNsfw = fastDetector,
            validatorNsfw = validatorDetector,
            usingModels = usingModels,
            backend = backend,
            nsfwCalibrated = true
        )
    }
}
