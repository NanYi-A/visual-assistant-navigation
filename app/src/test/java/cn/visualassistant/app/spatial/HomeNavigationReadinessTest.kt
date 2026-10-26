package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeNavigationReadinessTest {
    @Test
    fun emptyMapCannotBeUsedForNavigation() {
        val result = HomeNavigationReadinessGate.evaluate(false, true)
        assertFalse(result.ready)
        assertEquals("家庭地图尚未生成", result.message)
    }

    @Test
    fun savedScanWithoutValidatedGraphCannotProduceWalkingRoute() {
        val result = HomeNavigationReadinessGate.evaluate(true, false)
        assertFalse(result.ready)
        assertTrue(result.message.contains("不能提供行走路线"))
    }

    @Test
    fun validatedGraphWithMapCanBeUsed() {
        val result = HomeNavigationReadinessGate.evaluate(true, true, verifiedGraphConsumedByPlanner = true)
        assertTrue(result.ready)
        assertEquals("导航图已通过后端验证", result.message)
    }

    @Test
    fun backendReadinessAloneIsInsufficientWhilePhoneStillUsesLegacyGridPlanner() {
        val result = HomeNavigationReadinessGate.evaluate(
            true, backendValidatedNavigationGraph = true, verifiedGraphConsumedByPlanner = false)
        assertFalse(result.ready)
        assertTrue(result.message.contains("接入路线规划"))
    }

    @Test
    fun supervisedExperimentCanUseCandidateRouteOnlyWithLiveLocalizedPose() {
        val result = HomeNavigationReadinessGate.evaluate(
            true,
            backendValidatedNavigationGraph = false,
            verifiedGraphConsumedByPlanner = false,
            supervisedTestMode = true,
            liveLocalizedPose = true
        )
        assertTrue(result.ready)
        assertTrue(result.message.contains("陪同实验"))
    }

    @Test
    fun supervisedExperimentStillRequiresLiveLocalizedPose() {
        val result = HomeNavigationReadinessGate.evaluate(
            true,
            backendValidatedNavigationGraph = false,
            verifiedGraphConsumedByPlanner = false,
            supervisedTestMode = true,
            liveLocalizedPose = false
        )
        assertFalse(result.ready)
    }
}
