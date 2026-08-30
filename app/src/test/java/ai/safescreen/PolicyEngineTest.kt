package ai.safescreen

import ai.safescreen.pipeline.NsfwResult
import ai.safescreen.policy.Action
import ai.safescreen.policy.PolicyEngine
import ai.safescreen.policy.ProtectionLevel
import ai.safescreen.policy.Severity
import ai.safescreen.policy.TemporalSmoother
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolicyEngineTest {

    @Test
    fun testDefaultLevelIsPrivate() {
        val policy = PolicyEngine()
        assertEquals(ProtectionLevel.PRIVATE, policy.currentLevel)
        assertEquals(0.30f, policy.thresholds.nsfwBlur, 0.001f)
        assertEquals(0.70f, policy.thresholds.nsfwBlock, 0.001f)
    }

    @Test
    fun testSafeLevelBehavior() {
        val policy = PolicyEngine(initialLevel = ProtectionLevel.SAFE)
        assertEquals(ProtectionLevel.SAFE, policy.currentLevel)

        // Moderate score (0.35) should be ignored under SAFE (blur threshold is 0.50)
        val moderateResult = NsfwResult(score = 0.35f, latencyMs = 15, backend = "CPU/XNNPACK")
        val moderateDecision = policy.decide(moderateResult)
        assertEquals(Action.SHOW, moderateDecision.action)
        assertEquals(Severity.NONE, moderateDecision.severity)

        // High score (0.60) should trigger blur
        val highResult = NsfwResult(score = 0.60f, latencyMs = 15, backend = "CPU/XNNPACK")
        val highDecision = policy.decide(highResult)
        assertEquals(Action.BLUR_REVEAL, highDecision.action)
        assertEquals(Severity.MEDIUM, highDecision.severity)
    }

    @Test
    fun testPrivateLevelBehavior() {
        val policy = PolicyEngine(initialLevel = ProtectionLevel.PRIVATE)

        // Moderate score (0.35) should trigger blur under PRIVATE (blur threshold is 0.30)
        val moderateResult = NsfwResult(score = 0.35f, latencyMs = 15, backend = "CPU/XNNPACK")
        val moderateDecision = policy.decide(moderateResult)
        assertEquals(Action.BLUR_REVEAL, moderateDecision.action)
        assertEquals(Severity.MEDIUM, moderateDecision.severity)

        // Clean score (0.05) should remain SHOW
        val cleanResult = NsfwResult(score = 0.05f, latencyMs = 15, backend = "CPU/XNNPACK")
        val cleanDecision = policy.decide(cleanResult)
        assertEquals(Action.SHOW, cleanDecision.action)
    }

    @Test
    fun testMaximumLevelBehavior() {
        val policy = PolicyEngine(initialLevel = ProtectionLevel.MAXIMUM)

        // Borderline score (0.20) should trigger blur under MAXIMUM (blur threshold is 0.18)
        val borderlineResult = NsfwResult(score = 0.20f, latencyMs = 15, backend = "CPU/XNNPACK")
        val borderlineDecision = policy.decide(borderlineResult)
        assertEquals(Action.BLUR_REVEAL, borderlineDecision.action)

        // High-risk score (0.50) should be BLOCKED under MAXIMUM (block threshold is 0.45)
        val highResult = NsfwResult(score = 0.50f, latencyMs = 15, backend = "CPU/XNNPACK")
        val highDecision = policy.decide(highResult)
        assertEquals(Action.BLOCK, highDecision.action)
        assertEquals(Severity.HIGH, highDecision.severity)
    }

    @Test
    fun testDynamicLevelSwitching() {
        val policy = PolicyEngine(initialLevel = ProtectionLevel.SAFE)
        val score = NsfwResult(score = 0.25f, latencyMs = 15, backend = "CPU/XNNPACK")

        // Under SAFE: SHOW
        assertEquals(Action.SHOW, policy.decide(score).action)

        // Switch to MAXIMUM: BLUR_REVEAL
        policy.setLevel(ProtectionLevel.MAXIMUM)
        assertEquals(ProtectionLevel.MAXIMUM, policy.currentLevel)
        assertEquals(Action.BLUR_REVEAL, policy.decide(score).action)
    }

    @Test
    fun testTemporalSmoother() {
        val smoother = TemporalSmoother(decayAlpha = 0.5f)

        // Initial zero
        assertEquals(0.0f, smoother.push(0.0f), 0.001f)

        // Instant Fast Attack on threat (zero frame lag)
        val attack = smoother.push(0.9f, threshold = 0.30f)
        assertEquals(0.9f, attack, 0.001f)

        // Smooth Exponential Decay on safe frame (prevents video flicker)
        val decay1 = smoother.push(0.1f, threshold = 0.30f)
        assertEquals(0.5f, decay1, 0.001f) // 0.5 * 0.9 + 0.5 * 0.1 = 0.5

        val decay2 = smoother.push(0.1f, threshold = 0.30f)
        assertEquals(0.3f, decay2, 0.001f) // 0.5 * 0.5 + 0.5 * 0.1 = 0.3

        smoother.reset()
        assertEquals(0.8f, smoother.push(0.8f, threshold = 0.30f), 0.001f)
    }
}
