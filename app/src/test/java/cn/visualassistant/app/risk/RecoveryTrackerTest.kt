package cn.visualassistant.app.risk

import cn.visualassistant.app.core.*
import org.junit.Assert.*
import org.junit.Test

class RecoveryTrackerTest {
    private val obstacle = Detection(0, "行人", .9f, NormalizedBox(.3f, .2f, .7f, .9f))
    private val danger = RiskAssessment(RiskLevel.DANGER)
    private val clear = RiskAssessment(RiskLevel.SAFE)
    private fun armed() = RecoveryTracker().apply { update(listOf(obstacle), danger, 0) }

    @Test fun reportsOnceAfterTwoSecondsAndNeverOnStartup() {
        val startup = RecoveryTracker()
        val tracker = armed()
        for (time in 200L..2000L step 200) {
            assertNull(startup.update(emptyList(), clear, time))
            assertNull(tracker.update(emptyList(), clear, time))
        }
        val event = tracker.update(emptyList(), clear, 2200)
        assertTrue(event!!.isRecovery)
        assertTrue(event.message.contains("确认地面"))
        for (time in 2400L..6000L step 200) assertNull(tracker.update(emptyList(), clear, time))
    }

    @Test fun oneFrameMissAndNewUnconfirmedObstacleRestartCountdown() {
        val tracker = armed()
        for (time in 200L..1800L step 200) assertNull(tracker.update(emptyList(), clear, time))
        assertNull(tracker.update(listOf(obstacle), clear, 2000))
        for (time in 2200L..4000L step 200) assertNull(tracker.update(emptyList(), clear, time))
        assertNotNull(tracker.update(emptyList(), clear, 4200))
    }

    @Test fun interruptedStreamAndPauseDiscardOldObstacleState() {
        for (pause in listOf(false, true)) {
            val tracker = armed()
            tracker.update(emptyList(), clear, 200)
            if (pause) tracker.reset()
            for (time in 2000L..5000L step 200) assertNull(tracker.update(emptyList(), clear, time))
        }
    }

    @Test fun longDurationWithTooFewFramesIsInsufficient() {
        val tracker = armed()
        assertNull(tracker.update(emptyList(), clear, 100))
        assertNull(tracker.update(emptyList(), clear, 1100))
        assertNull(tracker.update(emptyList(), clear, 2100))
        assertNull(tracker.update(emptyList(), clear, 2200))
        assertNotNull(tracker.update(emptyList(), clear, 2300))
    }

    @Test fun waitsForQuestionToFinishAndRechecksCurrentFrame() {
        val tracker = armed()
        for (time in 200L..3000L step 200) assertNull(tracker.update(emptyList(), clear, time, false))
        assertNull(tracker.update(listOf(obstacle), clear, 3200, true))
        for (time in 3400L..5200L step 200) assertNull(tracker.update(emptyList(), clear, time))
        assertNotNull(tracker.update(emptyList(), clear, 5400))
    }

    @Test fun nearbySideObstacleAndWideBoxPreventRecovery() {
        for (box in listOf(NormalizedBox(0f, .4f, .2f, .8f), NormalizedBox(.1f, .4f, .72f, .6f))) {
            val tracker = armed()
            for (time in 200L..4000L step 200) {
                assertNull(tracker.update(listOf(obstacle.copy(box = box)), clear, time))
            }
        }
    }

    @Test fun newObstacleArmsAnotherRecoveryCycle() {
        val tracker = armed()
        for (time in 200L..2000L step 200) tracker.update(emptyList(), clear, time)
        assertNotNull(tracker.update(emptyList(), clear, 2200))
        tracker.update(listOf(obstacle), danger, 2400)
        for (time in 2600L..4400L step 200) assertNull(tracker.update(emptyList(), clear, time))
        assertNotNull(tracker.update(emptyList(), clear, 4600))
    }
}
