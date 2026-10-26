package cn.visualassistant.app.risk

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import org.junit.Assert.*
import org.junit.Test

class AvoidanceGuideTest {
    private fun box(left: Float, right: Float, bottom: Float = .9f) =
        Detection(0, "椅子", .9f, NormalizedBox(left, .2f, right, bottom))

    @Test fun asymmetricObstaclesSuggestOppositeSideForInspection() {
        assertTrue(AvoidanceGuide.describe(listOf(box(.7f, 1f))).contains("可优先观察左侧"))
        assertTrue(AvoidanceGuide.describe(listOf(box(0f, .3f))).contains("可优先观察右侧"))
    }

    @Test fun wideBoxBlocksBothSidesEvenWithCenterInMiddle() {
        assertTrue(AvoidanceGuide.describe(listOf(box(.05f, .95f))).contains("暂不能确定"))
    }

    @Test fun balancedObstaclesAndEmptyImageDoNotInventRoute() {
        assertFalse(AvoidanceGuide.describe(listOf(box(0f, .3f), box(.7f, 1f))).contains("可优先观察"))
        assertTrue(AvoidanceGuide.describe(emptyList()).contains("暂不能判断"))
        assertFalse(AvoidanceGuide.describe(listOf(box(.4f, .6f))).contains("可优先观察"))
    }

    @Test fun newUnconfirmedObstacleVetoesSuggestedSide() {
        val right = box(.7f, 1f)
        assertFalse(AvoidanceGuide.describe(listOf(right), listOf(right, box(0f, .3f))).contains("可优先观察"))
    }

    @Test fun farOrUnconfirmedObjectsDoNotGenerateDirection() {
        assertFalse(AvoidanceGuide.describe(listOf(box(.7f, 1f, .4f))).contains("可优先观察"))
        assertFalse(AvoidanceGuide.describe(emptyList(), listOf(box(.7f, 1f))).contains("可优先观察"))
    }

    @Test fun overlapsAreNotCountedTwiceAndSmallBlockersAccumulate() {
        assertFalse(AvoidanceGuide.describe(listOf(box(.8f, .9f), box(.8f, .9f))).contains("可优先观察"))
        val right = box(.7f, 1f)
        assertFalse(AvoidanceGuide.describe(listOf(right, box(0f, .05f), box(.1f, .15f))).contains("可优先观察"))
    }

    @Test fun automaticAlertsIncludeSameGuidanceAndResetStability() {
        val engine = RiskEngine()
        val right = listOf(box(.7f, 1f))
        engine.assess(right)
        val alert = engine.assess(right)
        assertTrue(alert.message.endsWith(AvoidanceGuide.describe(right)))
        assertTrue(engine.shouldAlert(alert, 0))
        assertFalse(engine.shouldAlert(alert, 4000))
        assertTrue(engine.shouldAlert(alert, 30000))
        engine.reset()
        assertFalse(engine.assess(right).message.contains("可优先观察"))
    }
}
