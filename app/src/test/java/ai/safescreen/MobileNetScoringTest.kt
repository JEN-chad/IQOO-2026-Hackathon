package ai.safescreen

import ai.safescreen.pipeline.ModelConfig
import ai.safescreen.pipeline.scoreOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MobileNetScoringTest {

    private val cfg = ModelConfig.NSFW_MOBILENET

    @Test
    fun test100PercentNeutral() {
        // Class indices: 0: drawings, 1: hentai, 2: neutral, 3: porn, 4: sexy
        // Neutral logit is huge (+10.0), others are negative (-10.0)
        val logits = floatArrayOf(-10f, -10f, 10f, -10f, -10f)
        val score = scoreOf(logits, cfg)
        assertEquals(0.0f, score, 0.001f, "100% neutral must yield NSFW score ≈ 0.0")
    }

    @Test
    fun test100PercentHentai() {
        // Hentai logit is huge (+10.0)
        val logits = floatArrayOf(-10f, 10f, -10f, -10f, -10f)
        val score = scoreOf(logits, cfg)
        assertEquals(1.0f, score, 0.001f, "100% hentai must yield NSFW score ≈ 1.0")
    }

    @Test
    fun test100PercentPorn() {
        // Porn logit is huge (+10.0)
        val logits = floatArrayOf(-10f, -10f, -10f, 10f, -10f)
        val score = scoreOf(logits, cfg)
        assertEquals(1.0f, score, 0.001f, "100% porn must yield NSFW score ≈ 1.0")
    }

    @Test
    fun test100PercentSexy() {
        // Sexy logit is huge (+10.0)
        val logits = floatArrayOf(-10f, -10f, -10f, -10f, 10f)
        val score = scoreOf(logits, cfg)
        assertEquals(1.0f, score, 0.001f, "100% sexy must yield NSFW score ≈ 1.0")
    }

    @Test
    fun test100PercentDrawings() {
        // Drawings logit is huge (+10.0)
        val logits = floatArrayOf(10f, -10f, -10f, -10f, -10f)
        val score = scoreOf(logits, cfg)
        assertEquals(0.0f, score, 0.001f, "100% drawings must yield NSFW score ≈ 0.0")
    }

    @Test
    fun testMixedNeutralAndSexy() {
        // Neutral = 0.0, Sexy = 0.0, others = -100.0 -> 50% neutral, 50% sexy
        val logits = floatArrayOf(-100f, -100f, 0f, -100f, 0f)
        val score = scoreOf(logits, cfg)
        assertEquals(0.5f, score, 0.001f, "50/50 neutral/sexy must yield NSFW score ≈ 0.5")
    }

    @Test
    fun testMixedNeutralAndPorn() {
        // Neutral = 1.0, Porn = 0.0, others = -100.0 -> P(porn) = 1 / (1 + e) ≈ 0.2689
        val logits = floatArrayOf(-100f, -100f, 1f, 0f, -100f)
        val score = scoreOf(logits, cfg)
        val expected = kotlin.math.exp(0f) / (kotlin.math.exp(1f) + kotlin.math.exp(0f))
        assertEquals(expected, score, 0.001f, "Score must mathematically reflect porn probability")
    }
}
