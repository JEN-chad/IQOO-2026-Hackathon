package ai.safescreen

import android.content.Context
import android.graphics.Bitmap
import ai.safescreen.feed.FeedItem
import ai.safescreen.pipeline.DetectorFactory
import ai.safescreen.pipeline.NsfwResult
import ai.safescreen.pipeline.skinRatio
import ai.safescreen.policy.Decision
import ai.safescreen.policy.PolicyEngine
import ai.safescreen.policy.Thresholds
import ai.safescreen.ui.HudState
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Wires the detection pipeline: NSFW detector -> PolicyEngine. Used by both the
 * background ScreenCaptureService (real screen frames) and the in-app test feed. Shared singleton
 * so models load once per process.
 */
class SafeScreenEngine private constructor(
    private val detectors: DetectorFactory.Detectors,
    private val policy: PolicyEngine,
) {
    val thresholds: Thresholds get() = policy.thresholds
    val backend: String get() = detectors.backend
    val usingModels: Boolean get() = detectors.usingModels

    data class Analyzed(val decision: Decision, val hud: HudState)

    // The native Module is not thread-safe; confine all inference to one thread.
    private val inferenceDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "safescreen-infer") }.asCoroutineDispatcher()

    suspend fun analyze(item: FeedItem): Analyzed = analyzeBitmap(item.bitmap, item.id)

    /** Classify a raw bitmap (feed image or photo scan) through the NSFW detector + skin gate. */
    suspend fun analyzeBitmap(bitmap: Bitmap, id: String): Analyzed = withContext(inferenceDispatcher) {
        val ml = detectors.nsfw.classify(bitmap)
        val blurT = policy.thresholds.nsfwBlur
        val score = if (detectors.nsfwCalibrated && detectors.backend == "QNN/HTP") {
            // int8 NPU: scores compressed (benign ~0.50, explicit ~0.55+). Use skin ratio to
            // gate: explicit images are skin-heavy, benign portraits/UI are not.
            val skin = skinRatio(bitmap)
            when {
                skin >= FEED_SKIN_MIN -> ml.score
                ml.score >= policy.thresholds.mlOnlyBlur -> ml.score
                else -> ml.score.coerceAtMost((blurT - 0.05f).coerceAtLeast(0f))
            }
        } else ml.score
        val nsfw = NsfwResult(score, ml.latencyMs, ml.backend)
        val decision = policy.decide(nsfw)
        android.util.Log.i(
            "SafeScreenEngine",
            "id=$id nsfw=${"%.3f".format(score)} (ml=${"%.3f".format(ml.score)}) t1=${ml.latencyMs}ms " +
                "backend=${ml.backend} action=${decision.action}",
        )
        val fps = if (ml.latencyMs > 0) 1000f / ml.latencyMs else 0f
        val hud = HudState(
            backend = detectors.backend,
            tier1Ms = ml.latencyMs,
            fps = fps,
            usingModels = detectors.usingModels,
        )
        Analyzed(decision, hud)
    }

    /**
     * Screen-monitor analysis: tile a non-square screen frame into square crops and take the MAX NSFW
     * score — a whole-frame classifier dilutes an explicit region that only occupies part of the screen,
     * so we localize by region instead.
     */
    suspend fun analyzeScreen(bitmap: Bitmap): Analyzed = withContext(inferenceDispatcher) {
        val crops = squareCrops(bitmap)
        val w = policy.thresholds.skinWeight
        val floor = policy.thresholds.mlNsfwFloor
        val skinMin = policy.thresholds.skinMin
        val mlOnly = policy.thresholds.mlOnlyBlur
        val blurT = policy.thresholds.nsfwBlur
        var bestScore = -1f
        var bestMl = 0f
        var bestSkin = 0f
        var t1 = 0L
        for (c in crops) {
            val ml = detectors.nsfw.classify(c)
            t1 += ml.latencyMs
            val skin = skinRatio(c)
            // Model-aware scoring:
            //  - Marqo (calibrated 2-class NSFW): accurate on photos, but the int8 NPU model is OUT-OF-
            //    DISTRIBUTION on UI/screenshots — it scores zero-skin app chrome ~0.6 (fp32 says ~0.07). On the
            //    SCREEN path real explicit content is SKIN-HEAVY, so require skin corroboration: no skin -> treat
            //    as a UI false-positive and cap below the blur line, unless the model is overwhelmingly confident
            //    (>=mlOnly). NSFW inference still runs 100% on the NPU — this is only a skin-pixel-ratio guard.
            //  - MobileNetV4 (weak, under-fires): the skin-backstop + UI-FP cascade. Nudity is SKIN-HEAVY;
            //    UI false-positives (a dial pad scored ml=0.55!) have ~zero skin, so gate on signal agreement:
            //    skin-corroborated -> ml + w*skin; ML alone -> only very-confident (>=mlOnly) acts; else capped.
            val combined = if (detectors.nsfwCalibrated) {
                when {
                    skin >= SCREEN_SKIN_MIN -> (ml.score + 0.3f * skin).coerceAtMost(1f)
                    ml.score >= mlOnly -> ml.score
                    else -> ml.score.coerceAtMost((blurT - 0.05f).coerceAtLeast(0f))
                }
            } else when {
                ml.score >= floor && skin >= skinMin -> (ml.score + w * skin).coerceAtMost(1f)
                ml.score >= mlOnly -> ml.score
                else -> ml.score.coerceAtMost(0.45f)
            }
            if (combined > bestScore) {
                bestScore = combined; bestMl = ml.score; bestSkin = skin
            }
        }
        crops.forEach { if (it !== bitmap) it.recycle() }
        val nsfw = NsfwResult(bestScore, t1, detectors.backend)
        val decision = policy.decide(nsfw)
        android.util.Log.i(
            "SafeScreenEngine",
            "id=screen nsfw=${"%.3f".format(bestScore)} (ml=${"%.3f".format(bestMl)} skin=${"%.3f".format(bestSkin)}) " +
                "t1=${t1}ms(${crops.size}c) action=${decision.action}",
        )
        val hud = HudState(detectors.backend, t1, if (t1 > 0) 1000f / t1 else 0f, detectors.usingModels)
        Analyzed(decision, hud)
    }

    /** Square sub-crops covering a non-square frame (vertical thirds for portrait, horizontal for landscape). */
    private fun squareCrops(b: Bitmap): List<Bitmap> {
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return listOf(b)
        val side = minOf(w, h)
        if (kotlin.math.abs(w - h) <= side / 5) return listOf(b) // already ~square
        return if (h > w) listOf(
            Bitmap.createBitmap(b, 0, 0, w, w),
            Bitmap.createBitmap(b, 0, (h - w) / 2, w, w),
            Bitmap.createBitmap(b, 0, h - w, w, w),
        ) else listOf(
            Bitmap.createBitmap(b, 0, 0, h, h),
            Bitmap.createBitmap(b, (w - h) / 2, 0, h, h),
            Bitmap.createBitmap(b, w - h, 0, h, h),
        )
    }

    companion object {
        // int8 NPU: scores compressed (benign ~0.50, explicit ~0.50-0.55), skin ratio is the
        // real discriminator. Screen captures dilute skin (UI chrome, multi-crop), so the gate
        // is lower + a proportional boost (0.3*skin) scales with skin confidence.
        // Feed images are full-res so the gate is higher (no boost, avoids portrait FPs).
        private const val SCREEN_SKIN_MIN = 0.20f
        private const val FEED_SKIN_MIN = 0.45f

        @Volatile
        private var INSTANCE: SafeScreenEngine? = null

        fun get(context: Context): SafeScreenEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val det = DetectorFactory.create(context.applicationContext)
                    val policy = PolicyEngine()
                    if (det.nsfwCalibrated) {
                        if (det.backend == "QNN/HTP") {
                            // int8 NPU: benign ~0.50, explicit ~0.50-0.55. Skin gate + boost
                            // on the screen path pushes skin-corroborated scores above 0.55.
                            policy.thresholds.nsfwBlur = 0.55f
                            policy.thresholds.nsfwBlock = 0.85f
                        } else {
                            policy.thresholds.nsfwBlur = 0.30f
                            policy.thresholds.nsfwBlock = 0.70f
                        }
                    }
                    SafeScreenEngine(det, policy).also { INSTANCE = it }
                }
            }
    }
}
