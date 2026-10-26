package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test

class DepthStreamGateTest {
    @Test fun cachedDepthDoesNotKeepStreamAliveOrCreateNewEvidence() {
        val gate=DepthStreamGate()
        assertTrue(gate.observe(100,30,0))
        assertFalse(gate.observe(100,30,500)); assertTrue(gate.available(500))
        assertFalse(gate.observe(100,30,1600)); assertFalse(gate.available(1600))
        assertTrue(gate.observe(200,30,6500))
    }
    @Test fun alternatingOlderTimestampsNeverReenterTheMap() {
        val gate=DepthStreamGate()
        assertTrue(gate.observe(300,30,0))
        assertFalse(gate.observe(200,30,400))
        assertFalse(gate.observe(250,30,800))
        assertFalse(gate.observe(300,30,1200))
        assertFalse(gate.observe(200,30,1600))
        assertFalse(gate.available(1600))
        assertTrue(gate.observe(301,30,1700))
    }
    @Test fun emptyDepthDoesNotCreateMapOrKeepStreamAlive() {
        val gate=DepthStreamGate()
        assertFalse(gate.observe(100,0,0)); assertFalse(gate.available(0))
        assertTrue(gate.observe(200,30,500))
        assertFalse(gate.observe(300,0,2100)); assertFalse(gate.available(2100))
    }
    @Test fun sessionResetForgetsOldTimestampsAndDepthAvailability() {
        val gate=DepthStreamGate()
        gate.observe(100,30,0)
        gate.reset()
        assertFalse(gate.available(100))
        assertTrue(gate.observe(100,30,200))
    }
}
