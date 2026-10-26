package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.hypot

class TraversabilityGridTest {
    private fun camera(x: Float = 0f) = CameraTransform(floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,0f,0f,1f))

    private fun floorSamples(): List<WorldSample> = (0 until 18).map { i ->
        val x = (i % 6 - 3) * .2f
        val z = -1.2f - (i / 6) * .25f
        WorldSample(Point3(x,-1.4f,z),(i % 3 + 1).toLong(),100,
            cameraOrigin=Point3(0f,0f,0f))
    }

    @Test fun noReliableFloorNeverCreatesFreeCells() {
        val result=TraversabilityGrid.build(floorSamples().take(8),camera())
        assertNull(result.floorHeight)
        assertTrue(result.cells.isEmpty())
    }

    @Test fun confirmedFloorProducesObservedFreeCellsButNotUnknownCells() {
        val result=TraversabilityGrid.build(floorSamples(),camera())
        assertEquals(-1.4f,result.floorHeight!!,.001f)
        assertTrue(result.cells.any { it.state==TraversabilityState.FREE })
        assertFalse(result.cells.any { kotlin.math.abs(it.relativeX)>2f || it.relativeZ>0f })
    }

    @Test fun slantedDepthNoiseAroundFloorDoesNotBecomeObstacleField() {
        val noisy=floorSamples().mapIndexed { i, sample ->
            sample.copy(point=sample.point.copy(y=-1.4f+listOf(-.12f,0f,.12f)[i%3]))
        }
        val result=TraversabilityGrid.build(noisy,camera())
        assertNotNull(result.floorHeight)
        assertTrue(result.cells.any { it.state==TraversabilityState.FREE })
        assertFalse(result.cells.any { it.state==TraversabilityState.OCCUPIED })
    }

    @Test fun obstacleOverridesFreeAndInflatesBodyClearance() {
        val obstacle=WorldSample(Point3(0f,-1.0f,-1.5f),4,120,cameraOrigin=Point3(0f,0f,0f))
        val result=TraversabilityGrid.build(floorSamples()+obstacle,camera())
        val occupied=result.cells.filter { it.state==TraversabilityState.OCCUPIED }
        assertTrue(occupied.any { !it.inflated && hypot(it.relativeX,it.relativeZ+1.5f)<.12f })
        assertTrue(occupied.any { it.inflated && hypot(it.relativeX,it.relativeZ+1.5f) in .12f..25f })
        assertFalse(result.cells.any { it.state==TraversabilityState.FREE && hypot(it.relativeX,it.relativeZ+1.5f)<.16f })
    }

    @Test fun movingCameraChangesOnlyRelativeGridPosition() {
        val original=TraversabilityGrid.build(floorSamples(),camera()).cells
            .filter { it.state==TraversabilityState.FREE }.minBy { hypot(it.relativeX,it.relativeZ) }
        val moved=TraversabilityGrid.build(floorSamples(),camera(.5f)).cells
            .filter { it.state==TraversabilityState.FREE }.minBy { hypot(it.relativeX+.5f,it.relativeZ) }
        assertEquals(original.relativeX-.5f,moved.relativeX,.11f)
    }
}
