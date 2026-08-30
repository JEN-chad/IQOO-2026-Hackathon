package ai.safescreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import ai.safescreen.feed.FeedItem
import ai.safescreen.pipeline.DetectorFactory
import ai.safescreen.pipeline.NsfwResult
import ai.safescreen.pipeline.skinRatio
import ai.safescreen.policy.Decision
import ai.safescreen.policy.PolicyEngine
import ai.safescreen.policy.ProtectionLevel
import ai.safescreen.policy.Thresholds
import ai.safescreen.ui.HudState
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Wires the detection pipeline: NSFW detector -> PolicyEngine.
 * Used by both the background ScreenCaptureService and the in-app test feed.
 * Shared singleton so models load once per process.
 */
class SafeScreenEngine private constructor(
    private val detectors: DetectorFactory.Detectors,
    val policy: PolicyEngine,
) {
    val thresholds: Thresholds get() = policy.thresholds
    val backend: String get() = detectors.backend
    val usingModels: Boolean get() = detectors.usingModels
    val currentLevel: ProtectionLevel get() = policy.currentLevel

    fun setProtectionLevel(level: ProtectionLevel) {
        policy.setLevel(level)
    }

    data class CropRegion(val rect: Rect, val score: Float, val isRisky: Boolean)
    data class Analyzed(
        val decision: Decision,
        val hud: HudState,
        val cropRegions: List<CropRegion> = emptyList(),
        val needsValidation: Boolean = false,
        val validationSpec: Rect? = null,
        val fastScore: Float = 0f,
    )

    // The native ExecuTorch Module is not thread-safe; confine all inference to one thread per instance.
    private val inferenceDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "safescreen-infer") }.asCoroutineDispatcher()
        
    private val validatorDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "safescreen-validator") }.asCoroutineDispatcher()

    suspend fun analyze(item: FeedItem): Analyzed = analyzeBitmap(item.bitmap, item.id)

    /** Classify a raw bitmap (feed image or photo scan) through the NSFW detector + skin gate. */
    suspend fun analyzeBitmap(bitmap: Bitmap, id: String): Analyzed = withContext(inferenceDispatcher) {
        val ml = detectors.fastNsfw.classify(bitmap)
        val blurT = policy.thresholds.nsfwBlur
        val score = if (detectors.nsfwCalibrated && detectors.backend == "QNN/HTP") {
            val skin = skinRatio(bitmap)
            when {
                skin >= FEED_SKIN_MIN -> ml.score
                ml.score >= policy.thresholds.mlOnlyBlur -> ml.score
                else -> ml.score.coerceAtMost((blurT - 0.05f).coerceAtLeast(0f))
            }
        } else ml.score
        
        val needsValidation = score >= 0.15f && detectors.validatorNsfw != null
        val finalScore = if (needsValidation && detectors.validatorNsfw != null) {
            val vRes = withContext(validatorDispatcher) { detectors.validatorNsfw.classify(bitmap) }
            vRes.score
        } else score
        
        val nsfw = NsfwResult(finalScore, ml.latencyMs, ml.backend)
        val decision = policy.decide(nsfw)
        android.util.Log.i(
            "SafeScreenEngine",
            "id=$id nsfw=${"%.3f".format(finalScore)} (ml=${"%.3f".format(ml.score)}) t1=${ml.latencyMs}ms " +
                "backend=${ml.backend} level=$currentLevel action=${decision.action}",
        )
        val fps = if (ml.latencyMs > 0) 1000f / ml.latencyMs else 0f
        val status = when (decision.action) {
            ai.safescreen.policy.Action.BLOCK -> "THREAT BLOCKED"
            ai.safescreen.policy.Action.BLUR_REVEAL -> "PROTECTED"
            ai.safescreen.policy.Action.SHOW -> "MONITORING"
        }
        val hud = HudState(
            backend = detectors.backend,
            tier1Ms = ml.latencyMs,
            fps = fps,
            usingModels = detectors.usingModels,
            level = currentLevel.name,
            status = status,
            isDegraded = !detectors.usingModels,
        )
        Analyzed(
            decision = decision,
            hud = hud,
            cropRegions = listOf(CropRegion(Rect(0, 0, bitmap.width, bitmap.height), finalScore, finalScore >= blurT)),
        )
    }

    /**
     * Screen-monitor analysis: tile a non-square screen frame into square crops and take the MAX NSFW
     * score — tracks per-crop bounding coordinates and exits early on confirmed threat for low latency.
     */
    suspend fun analyzeScreen(bitmap: Bitmap, frameId: Long = 0): Analyzed = withContext(inferenceDispatcher) {
        val tPreStart = SystemClock.elapsedRealtime()
        android.util.Log.i("SafeScreenEngine", "[FRAME $frameId] PREPROCESS_START t=$tPreStart")
        val cropSpecs = computeCropSpecs(bitmap)
        val blurT = policy.thresholds.nsfwBlur

        var bestScore = -1f
        var bestMl = 0f
        var bestSkin = 0f
        var t1 = 0L
        val cropRegions = mutableListOf<CropRegion>()
        var needsValidation = false
        var validationSpec: Rect? = null

        for ((idx, spec) in cropSpecs.withIndex()) {
            val tInfStart = SystemClock.elapsedRealtime()
            if (idx == 0) {
                android.util.Log.i("SafeScreenEngine", "[FRAME $frameId] PREPROCESS_END t=$tInfStart")
                android.util.Log.i("SafeScreenEngine", "[FRAME $frameId] INFERENCE_START t=$tInfStart")
            }
            val cropBmp = Bitmap.createBitmap(bitmap, spec.left, spec.top, spec.width(), spec.height())
            val ml = detectors.fastNsfw.classify(cropBmp)
            t1 += ml.latencyMs
            val skin = skinRatio(cropBmp)
            if (cropBmp !== bitmap) cropBmp.recycle()

            // ML model score is the primary AI truth; skin ratio provides additive corroboration
            val combined = if (skin >= 0.25f && ml.score >= 0.15f) {
                (ml.score + 0.20f * skin).coerceAtMost(1.0f)
            } else {
                ml.score
            }

            cropRegions.add(CropRegion(spec, combined, combined >= blurT))

            if (combined > bestScore) {
                bestScore = combined
                bestMl = ml.score
                bestSkin = skin
            }

            if (combined >= 0.15f && detectors.validatorNsfw != null) {
                needsValidation = true
                validationSpec = spec
                break
            }
            
            if (combined >= blurT) {
                break
            }
        }

        val tInfEnd = SystemClock.elapsedRealtime()
        android.util.Log.i("SafeScreenEngine", "[FRAME $frameId] INFERENCE_END t=$tInfEnd (total=${tInfEnd - tPreStart}ms)")

        val nsfw = NsfwResult(bestScore, t1, detectors.backend)
        val decision = policy.decide(nsfw)
        val tPolicy = SystemClock.elapsedRealtime()
        android.util.Log.i(
            "SafeScreenEngine",
            "[FRAME $frameId] POLICY_RESULT t=$tPolicy nsfw=${"%.3f".format(bestScore)} " +
                "(ml=${"%.3f".format(bestMl)} skin=${"%.3f".format(bestSkin)}) t1=${t1}ms(${cropRegions.size}c) " +
                "level=$currentLevel action=${decision.action}",
        )
        val fps = if (t1 > 0) 1000f / t1 else 0f
        val status = when (decision.action) {
            ai.safescreen.policy.Action.BLOCK -> "THREAT BLOCKED"
            ai.safescreen.policy.Action.BLUR_REVEAL -> "PROTECTED"
            ai.safescreen.policy.Action.SHOW -> "MONITORING"
        }
        val hud = HudState(
            backend = detectors.backend,
            tier1Ms = t1,
            fps = fps,
            usingModels = detectors.usingModels,
            level = currentLevel.name,
            status = status,
            isDegraded = !detectors.usingModels,
        )
        Analyzed(decision, hud, cropRegions, needsValidation, validationSpec, bestScore)
    }

    suspend fun validateAsync(bitmap: Bitmap, spec: Rect): NsfwResult = withContext(validatorDispatcher) {
        val crop = Bitmap.createBitmap(bitmap, spec.left, spec.top, spec.width(), spec.height())
        val result = detectors.validatorNsfw!!.classify(crop)
        if (crop !== bitmap) crop.recycle()
        result
    }

    /** Compute coordinate bounds of square crops covering the input frame (Center first). */
    private fun computeCropSpecs(b: Bitmap): List<Rect> {
        val w = b.width
        val h = b.height
        if (w <= 0 || h <= 0) return listOf(Rect(0, 0, 1, 1))
        val side = minOf(w, h)
        if (kotlin.math.abs(w - h) <= side / 5) return listOf(Rect(0, 0, w, h)) // already ~square
        return if (h > w) listOf(
            Rect(0, (h - w) / 2, w, (h - w) / 2 + w), // Primary Center
            Rect(0, 0, w, w),                         // Top
            Rect(0, h - w, w, h),                     // Bottom
        ) else listOf(
            Rect((w - h) / 2, 0, (w - h) / 2 + h, h), // Primary Center
            Rect(0, 0, h, h),                         // Left
            Rect(w - h, 0, w, h),                     // Right
        )
    }

    companion object {
        private const val SCREEN_SKIN_MIN = 0.20f
        private const val FEED_SKIN_MIN = 0.45f

        @Volatile
        private var INSTANCE: SafeScreenEngine? = null

        fun get(context: Context): SafeScreenEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val det = DetectorFactory.create(context.applicationContext)
                    val policy = PolicyEngine(initialLevel = ProtectionLevel.PRIVATE)
                    SafeScreenEngine(det, policy).also { INSTANCE = it }
                }
            }
    }
}

