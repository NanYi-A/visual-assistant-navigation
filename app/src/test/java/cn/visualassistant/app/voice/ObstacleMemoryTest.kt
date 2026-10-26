package cn.visualassistant.app.voice

import cn.visualassistant.app.core.*
import cn.visualassistant.app.risk.RecoveryTracker
import org.junit.Assert.*
import org.junit.Test

class ObstacleMemoryTest {
    private fun chair(left: Float = .4f) = Detection(56, "椅子", .9f,
        NormalizedBox(left, .2f, left + .15f, .85f))
    private fun confirmed(items: List<Detection> = listOf(chair())) = ObstacleMemory().apply {
        update(items, 0)
        update(items, 200)
    }

    @Test fun needsTwoFramesAndRejectsLowConfidence() {
        val memory = ObstacleMemory()
        memory.update(listOf(chair()), 0)
        assertTrue(memory.answer("刚才的椅子在哪", 100)!!.contains("没有可用"))
        memory.update(listOf(chair().copy(confidence = .1f)), 200)
        assertFalse(memory.unresolved(400))
    }

    @Test fun disappearanceRetainsLastSeenLocationAndWarnsOnce() {
        val memory = confirmed()
        memory.update(emptyList(), 400)
        assertTrue(memory.unresolved(400))
        assertNull(memory.reminder(400))
        memory.update(emptyList(), 800)
        assertTrue(memory.reminder(800)!!.contains("尚未确认避开"))
        assertNull(memory.reminder(1000))
        val answer = memory.answer("刚才的椅子在哪", 2200)!!
        assertTrue(answer.contains("约2秒前"))
        assertTrue(answer.contains("画面前方"))
        assertTrue(answer.contains("不代表现在的位置"))
    }

    @Test fun shortOcclusionCanReassociateWithoutRepeatingObject() {
        val memory = confirmed()
        memory.update(emptyList(), 400)
        memory.update(listOf(chair(.42f)), 600)
        assertFalse(memory.unresolved(600))
        assertEquals(1, Regex("椅子最后").findAll(memory.answer("刚才有什么", 600)!!).count())
    }

    @Test fun oneDetectionCannotRefreshTwoRememberedObjects() {
        val memory = confirmed(listOf(chair(.1f), chair(.7f)))
        memory.update(listOf(chair(.1f)), 400)
        assertTrue(memory.unresolved(400))
        assertTrue(memory.caution(1000)!!.contains("右侧"))
    }

    @Test fun expiredRecordIsNotProofObstacleCleared() {
        val memory = confirmed()
        memory.update(emptyList(), 16000)
        assertTrue(memory.unresolved(16000))
        assertTrue(memory.caution(16000)!!.contains("过期"))
        assertFalse(memory.answer("刚才的椅子在哪", 16000)!!.contains("画面前方"))
    }

    @Test fun resetClearsHistoryAndUnknownState() {
        val memory = confirmed()
        memory.update(emptyList(), 16000)
        memory.clear()
        assertFalse(memory.unresolved(16200))
        assertNull(memory.caution(16200))
        assertTrue(memory.answer("刚才的椅子在哪", 16200)!!.contains("没有可用"))
    }

    @Test fun unknownObjectDoesNotReturnAnotherObjectsPosition() {
        val memory = confirmed()
        assertTrue(memory.answer("刚才的汽车在哪", 400)!!.contains("没有可用"))
        assertTrue(memory.answer("往前几米会碰到椅子", 400)!!.contains("不能可靠测距"))
        assertNull(memory.answer("前面有什么", 400))
    }

    @Test fun disappearanceAndExpiryCannotTriggerRecovery() {
        val memory = confirmed()
        val tracker = RecoveryTracker()
        tracker.update(listOf(chair()), RiskAssessment(RiskLevel.DANGER), 200)
        for (now in 400L..20000L step 200) {
            memory.update(emptyList(), now)
            assertNull(tracker.update(emptyList(), RiskAssessment(RiskLevel.SAFE), now,
                rememberedObstacle = memory.unresolved(now)))
        }
    }

    @Test fun currentSceneAnswerUsesMemoryCautionInsteadOfOppositeSideAdvice() {
        val scene = SceneDescriber()
        scene.update(listOf(chair(.7f)), 100)
        scene.update(listOf(chair(.7f)), 300)
        val caution = "刚才左侧的椅子尚未确认避开。"
        val answer = scene.answer("怎么走", 400, caution)
        assertTrue(answer.endsWith(caution))
        assertFalse(answer.contains("可优先观察"))
    }

    @Test fun retentionIsBoundedAndLongGapsDoNotConfirmSingleFrame() {
        val memory = ObstacleMemory()
        memory.update(listOf(chair()), 0)
        memory.update(listOf(chair()), 2000)
        assertTrue(memory.answer("刚才有什么", 2000)!!.contains("没有可用"))
        for (i in 1..100) {
            val item = chair().copy(classId = i, label = "目标$i")
            memory.update(listOf(item), 2000L + i * 400)
            memory.update(listOf(item), 2200L + i * 400)
        }
        assertTrue(memory.answer("刚才目标1在哪", 42200)!!.contains("没有可用"))
    }
}
