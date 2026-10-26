package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test

class SpatialVoicePromptsTest {
    private fun state(phase: ScanPhase, now: Long=0) = SpatialSnapshot(now, phase=phase,
        usable=phase==ScanPhase.READY, marks=if(phase==ScanPhase.READY)
            listOf(SpatialMark(Point3(0f,0f,-2f),null,0)) else emptyList(),
        objects=if(phase==ScanPhase.READY) listOf(SpatialObject(56,"椅子",.9f,
            Point3(0f,0f,-2f),0,10,.03f,fusionConfidence=FusionConfidence.HIGH)) else emptyList())

    @Test fun waitingPromptsRepeatAtBoundedIntervals() {
        val prompts=SpatialVoicePrompts()
        assertTrue(prompts.next(state(ScanPhase.LOCATING),0,false)!!.contains("正在建立定位"))
        for(now in 500L..14500L step 500) assertNull(prompts.next(state(ScanPhase.LOCATING,now),now,false))
        assertNotNull(prompts.next(state(ScanPhase.LOCATING,15000),15000,false))
    }
    @Test fun initialNoDepthGetsSpokenWithoutPriorSuccessfulScan() {
        val prompts=SpatialVoicePrompts()
        assertTrue(prompts.next(state(ScanPhase.WAITING_DEPTH),0,false)!!.contains("等待深度"))
    }
    @Test fun userQuestionSuppressesSpeechButLatestStatusWins() {
        val prompts=SpatialVoicePrompts()
        assertNull(prompts.next(state(ScanPhase.WAITING_DEPTH),0,true))
        assertNull(prompts.next(state(ScanPhase.READY,500),500,true))
        val text=prompts.next(state(ScanPhase.READY,1500),1500,false)!!
        assertTrue(text.contains("椅子")); assertFalse(text.contains("等待深度"))
    }
    @Test fun lossAndRecoveryAreSpokenAndShortFlickerIsDebounced() {
        val prompts=SpatialVoicePrompts()
        assertNotNull(prompts.next(state(ScanPhase.READY),0,false))
        assertNull(prompts.next(state(ScanPhase.LOST,500),500,false))
        assertNull(prompts.next(state(ScanPhase.READY,700),700,false))
        assertNull(prompts.next(state(ScanPhase.LOST,900),900,false))
        assertTrue(prompts.next(state(ScanPhase.LOST,1900),1900,false)!!.contains("定位丢失"))
        assertNull(prompts.next(state(ScanPhase.READY,2000),2000,false))
        assertTrue(prompts.next(state(ScanPhase.READY,3000),3000,false)!!.contains("已可用"))
    }
    @Test fun readySummariesRepeatButNeverUseStalePositions() {
        val prompts=SpatialVoicePrompts()
        assertNotNull(prompts.next(state(ScanPhase.READY),0,false))
        assertNull(prompts.next(state(ScanPhase.READY),20000,false))
        assertTrue(prompts.next(state(ScanPhase.READY,20500),20500,false)!!.contains("空间更新"))
        prompts.reset()
        assertNotNull(prompts.next(state(ScanPhase.LOCATING,21000),21000,false))
    }
}
