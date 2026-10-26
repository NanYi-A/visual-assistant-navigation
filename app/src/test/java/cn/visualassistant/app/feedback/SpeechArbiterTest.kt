package cn.visualassistant.app.feedback

import org.junit.Assert.*
import org.junit.Test

class SpeechArbiterTest {
    private fun ticket(id: String, key: String=id, text: String=id,
        priority: SpeechPriority=SpeechPriority.STATUS, at: Long=0, ttl: Long=10_000)=
        SpeechTicket(id,key,text,priority,at,at+ttl)

    @Test fun onlyEmergencyPreemptsCurrentSpeech() {
        val q=SpeechArbiter()
        assertNotNull(q.offer(ticket("answer",priority=SpeechPriority.ANSWER),0).start)
        assertFalse(q.offer(ticket("nav",priority=SpeechPriority.NAVIGATION),1).stopCurrent)
        val emergency=q.offer(ticket("stop",priority=SpeechPriority.EMERGENCY),2)
        assertTrue(emergency.stopCurrent)
        assertEquals("stop",emergency.start!!.id)
    }

    @Test fun sameKeyKeepsOnlyLatestPendingInstruction() {
        val q=SpeechArbiter()
        q.offer(ticket("active"),0)
        q.offer(ticket("left","navigation",at=1),1)
        val replacement=q.offer(ticket("right","navigation",at=2),2)
        assertEquals(listOf("left"),replacement.discarded.map { it.id })
        assertEquals("right",q.complete("active",3).start!!.id)
    }

    @Test fun expiredAndRecentlyCompletedSpeechIsDropped() {
        val q=SpeechArbiter()
        q.offer(ticket("one",text="重复"),0)
        q.offer(ticket("old",at=0,ttl=1),0)
        q.complete("one",100)
        assertTrue(q.offer(ticket("again",text="重复",at=101),101).discarded.isNotEmpty())
    }

    @Test fun queuedReplacementStillCountsAsBusyUntilItIsSpoken() {
        val q=SpeechArbiter()
        q.offer(ticket("status"),0)
        q.offer(ticket("left","navigation",at=1),1)
        // The page-level flag used to be cleared by the replaced ticket's callback;
        // the scheduler must keep reporting busy because "right" is still queued.
        q.offer(ticket("right","navigation",at=2),2).discarded.forEach { }
        assertTrue(q.isBusy())
        assertNotNull(q.complete("status",3).start)
        assertTrue(q.isBusy())
        q.complete("right",4)
        assertFalse(q.isBusy())
    }

    @Test fun userDialogCancellationKeepsEmergencyOnly() {
        val q=SpeechArbiter()
        q.offer(ticket("status"),0)
        q.offer(ticket("nav",priority=SpeechPriority.NAVIGATION),1)
        val result=q.cancelNonEmergency()
        assertTrue(result.stopCurrent)
        assertEquals(setOf("status","nav"),result.discarded.map { it.id }.toSet())
    }
}
