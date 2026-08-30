package ai.safescreen.policy

import ai.safescreen.pipeline.NsfwResult

enum class Severity { NONE, MEDIUM, HIGH }
enum class Action { SHOW, BLUR_REVEAL, BLOCK }

data class Decision(
    val severity: Severity,
    val action: Action,
    val reason: String,
    val nsfw: Float,
)

/** Mutable so the on-screen Settings panel can tune thresholds live during a demo. */
class Thresholds(
    var nsfwBlur: Float = 0.50f,
    var nsfwBlock: Float = 0.85f,
    var skinWeight: Float = 0.7f, // skin-ratio is added to the ML NSFW score (nudity backstop): ml + w*skin
    var mlNsfwFloor: Float = 0.12f, // skin only corroborates when the ML model has >= this much signal
    var skinMin: Float = 0.15f, // min skin ratio to TRUST a moderate ML score (nudity is skin-heavy)
    var mlOnlyBlur: Float = 0.85f, // ML alone (no skin) must be this confident to act — guards UI false-positives
)

class PolicyEngine(val thresholds: Thresholds = Thresholds()) {
    fun decide(nsfw: NsfwResult): Decision {
        val n = nsfw.score
        return when {
            n >= thresholds.nsfwBlock -> Decision(Severity.HIGH, Action.BLOCK, "Explicit content blocked", n)
            n >= thresholds.nsfwBlur -> Decision(Severity.MEDIUM, Action.BLUR_REVEAL, "Possibly explicit — blurred", n)
            else -> Decision(Severity.NONE, Action.SHOW, "No risk detected", n)
        }
    }
}
