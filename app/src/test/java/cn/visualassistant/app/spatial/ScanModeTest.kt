package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test

class ScanModeTest {
    @Test fun unknownOrMissingModeIsOutdoor() {
        assertEquals(ScanMode.OUTDOOR, ScanMode.fromWireName(null))
        assertEquals(ScanMode.OUTDOOR, ScanMode.fromWireName("bad"))
    }

    @Test fun homeIsPersistentAndKeepsMoreLocalEvidence() {
        assertEquals(ScanMode.HOME, ScanMode.fromWireName("home"))
        assertTrue(ScanMode.HOME.persistent)
        assertFalse(ScanMode.OUTDOOR.persistent)
        assertTrue(ScanMode.HOME.maxLocalKeyframes > ScanMode.OUTDOOR.maxLocalKeyframes)
    }
}
