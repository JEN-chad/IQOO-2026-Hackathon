package ai.safescreen.policy

/**
 * User-selectable protection levels for the SafeScreen AI privacy shield.
 * Controls detection sensitivity and intervention thresholds on the on-device AI pipeline.
 */
enum class ProtectionLevel(
    val title: String,
    val subtitle: String,
    val description: String,
    val blurThreshold: Float,
    val blockThreshold: Float,
    val skinMin: Float,
    val mlOnlyBlur: Float,
) {
    /**
     * Standard Protection: Targets only high-confidence explicit or graphic visual content.
     * Minimizes false positives during general phone usage.
     */
    SAFE(
        title = "SAFE",
        subtitle = "Standard Protection",
        description = "Shields high-confidence explicit and graphic content. Ideal for general daily use.",
        blurThreshold = 0.50f,
        blockThreshold = 0.85f,
        skinMin = 0.15f,
        mlOnlyBlur = 0.85f,
    ),

    /**
     * Private Protection (Recommended Default): Balanced on-device visual shield.
     * Protects medium-to-high risk visual content in public or shared spaces.
     */
    PRIVATE(
        title = "PRIVATE",
        subtitle = "Balanced Shield",
        description = "Shields medium and high-risk sensitive content. Recommended for commuting & shared spaces.",
        blurThreshold = 0.30f,
        blockThreshold = 0.70f,
        skinMin = 0.10f,
        mlOnlyBlur = 0.70f,
    ),

    /**
     * Maximum Privacy: Strictest visual protection.
     * Shields lower-confidence sensitive imagery and borderline frames for maximum confidentiality.
     */
    MAXIMUM(
        title = "MAXIMUM",
        subtitle = "Strict Privacy",
        description = "Aggressive shielding for crowded environments. Shields all borderline visual content.",
        blurThreshold = 0.18f,
        blockThreshold = 0.45f,
        skinMin = 0.05f,
        mlOnlyBlur = 0.50f,
    );

    fun applyTo(thresholds: Thresholds) {
        thresholds.nsfwBlur = blurThreshold
        thresholds.nsfwBlock = blockThreshold
        thresholds.skinMin = skinMin
        thresholds.mlOnlyBlur = mlOnlyBlur
    }
}
