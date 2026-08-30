package ai.safescreen.explain

import ai.safescreen.policy.Decision
import ai.safescreen.policy.Severity

data class Explanation(val title: String, val body: String, val source: String)

/**
 * Turns a Decision into human-readable safety guidance.
 *
 * Default is an instant template. The P3 "halo" upgrade swaps in a GemmaExplainer (on-device
 * LLM via ExecuTorch) behind this SAME interface — no caller changes required. Keeping this seam
 * is why the LLM is optional upside rather than a critical-path risk.
 */
interface Explainer {
    suspend fun explain(decision: Decision): Explanation
}

class TemplateExplainer : Explainer {
    override suspend fun explain(decision: Decision): Explanation = when (decision.severity) {
        Severity.HIGH -> Explanation(
            "Content blocked",
            "This image was very likely explicit, so SafeScreen hid it before it reached you. " +
                "You don't need to view it. If someone sent this, consider blocking them and keeping evidence.",
            "template",
        )
        Severity.MEDIUM -> Explanation(
            "Blurred for your safety",
            "This image may contain explicit content. It stays blurred until you choose to reveal it — " +
                "you're in control of whether to look.",
            "template",
        )
        Severity.NONE -> Explanation(
            "Looks fine",
            "No explicit content signals were detected in this image.",
            "template",
        )
    }
}
