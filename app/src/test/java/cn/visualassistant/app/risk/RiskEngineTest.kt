package cn.visualassistant.app.risk

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import cn.visualassistant.app.core.RiskLevel
import org.junit.Assert.*
import org.junit.Test

class RiskEngineTest {
    private fun detection(box: NormalizedBox, confidence: Float = 0.9f) =
        Detection(0, "行人", confidence, box)

    @Test fun `requires two stable frames`() {
        val engine = RiskEngine()
        val item = detection(NormalizedBox(.35f, .3f, .65f, .95f))
        assertEquals(RiskLevel.SAFE, engine.assess(listOf(item)).level)
        assertEquals(RiskLevel.DANGER, engine.assess(listOf(item)).level)
    }

    @Test fun `central near object is danger`() {
        val engine = RiskEngine(stableFrames = 1)
        assertEquals(RiskLevel.DANGER, engine.assess(listOf(detection(NormalizedBox(.3f, .2f, .7f, .92f)))).level)
    }

    @Test fun `edge object is downgraded`() {
        val engine = RiskEngine(stableFrames = 1)
        assertEquals(RiskLevel.NOTICE, engine.assess(listOf(detection(NormalizedBox(.02f, .4f, .24f, .90f)))).level)
    }

    @Test fun `low confidence is ignored`() {
        val engine = RiskEngine(stableFrames = 1)
        assertEquals(RiskLevel.SAFE, engine.assess(listOf(detection(NormalizedBox(.3f, .2f, .7f, .95f), .2f))).level)
    }

    @Test fun `two people in one frame do not fake temporal stability`() {
        val engine = RiskEngine(stableFrames = 2)
        val left = detection(NormalizedBox(.05f, .3f, .25f, .9f))
        val center = detection(NormalizedBox(.4f, .2f, .7f, .95f))
        assertEquals(RiskLevel.SAFE, engine.assess(listOf(left, center)).level)
    }

    @Test fun `cooldown suppresses repeats but escalation interrupts`() {
        val engine = RiskEngine(stableFrames = 1, normalCooldownMs = 4_000)
        val notice = engine.assess(listOf(detection(NormalizedBox(.02f, .5f, .25f, .80f))))
        assertTrue(engine.shouldAlert(notice, 1_000))
        assertFalse(engine.shouldAlert(notice, 2_000))
        val danger = engine.assess(listOf(detection(NormalizedBox(.3f, .2f, .7f, .95f))))
        assertTrue(engine.shouldAlert(danger, 2_100))
    }
}
