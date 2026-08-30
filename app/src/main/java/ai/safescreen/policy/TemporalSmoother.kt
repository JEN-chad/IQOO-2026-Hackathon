package ai.safescreen.policy

/**
 * Asymmetric Fast-Attack / Exponential-Decay filter for real-time video and continuous stream shielding.
 * - Instant Attack: When a threat is detected (score >= current or score >= threshold), jump immediately
 *   to the high risk score with ZERO frame delay.
 * - Smooth Decay (Hysteresis): When scores drop, decay smoothly with exponential smoothing (alpha) to prevent
 *   flicker during video cuts, motion blur, and scrolling.
 */
class TemporalSmoother(
    private val decayAlpha: Float = 0.5f,
) {
    private var currentSmoothed = 0.0f

    fun push(value: Float, threshold: Float = 0.30f): Float {
        currentSmoothed = if (value >= currentSmoothed || value >= threshold) {
            // Fast attack: jump to maximum threat immediately
            value
        } else {
            // Smooth decay: prevent video/scroll flicker
            decayAlpha * currentSmoothed + (1f - decayAlpha) * value
        }
        return currentSmoothed
    }

    fun reset() {
        currentSmoothed = 0.0f
    }
}
