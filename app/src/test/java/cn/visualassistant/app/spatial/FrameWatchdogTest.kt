package cn.visualassistant.app.spatial

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameWatchdogTest {
    private val watchdog = FrameWatchdog(warnAfterMs = 2500, stopAfterMs = 5000)

    @Test
    fun `fresh frames produce no action`() {
        assertEquals(FrameWatchdog.Action.NONE, watchdog.evaluate(10_000, 9_900))
        assertEquals(FrameWatchdog.Action.NONE, watchdog.evaluate(10_500, 9_900))
    }

    @Test
    fun `short stall warns once then stays quiet`() {
        assertEquals(FrameWatchdog.Action.WARN, watchdog.evaluate(10_000, 7_000))
        assertEquals(FrameWatchdog.Action.NONE, watchdog.evaluate(10_500, 7_000))
        assertEquals(FrameWatchdog.Action.NONE, watchdog.evaluate(11_000, 7_000))
    }

    @Test
    fun `sustained stall stops`() {
        assertEquals(FrameWatchdog.Action.STOP, watchdog.evaluate(10_000, 4_000))
    }

    @Test
    fun `recovery clears the warning so the next stall warns again`() {
        assertEquals(FrameWatchdog.Action.WARN, watchdog.evaluate(10_000, 7_000))
        assertEquals(FrameWatchdog.Action.NONE, watchdog.evaluate(11_000, 10_900))
        assertEquals(FrameWatchdog.Action.WARN, watchdog.evaluate(14_000, 11_200))
    }

    @Test
    fun `missing frame clock counts as stalled`() {
        // A zero clock means no frame was ever received, i.e. stalled beyond
        // the stop threshold. markFrameClock() prevents this on fresh scans.
        assertEquals(FrameWatchdog.Action.STOP, watchdog.evaluate(1_000, 0))
    }

    @Test
    fun `reset clears the warning state`() {
        assertEquals(FrameWatchdog.Action.WARN, watchdog.evaluate(10_000, 7_000))
        watchdog.reset()
        assertEquals(FrameWatchdog.Action.WARN, watchdog.evaluate(10_500, 7_000))
    }
}
