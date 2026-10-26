package cn.visualassistant.app.spatial

import org.junit.Assert.*
import org.junit.Test

class LocalNavigatorTest {
    private fun camera(x: Float=0f)=CameraTransform(floatArrayOf(
        1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, x,0f,0f,1f))
    private fun free(x: Float,z: Float)=TraversabilityCell(x,z,TraversabilityState.FREE,3,
        worldX=x,worldZ=z)
    /** Cell centred inside grid index (ix,iz), so it never lands on a cell border. */
    private fun cell(ix: Int,iz: Int,state: TraversabilityState=TraversabilityState.FREE)=
        TraversabilityCell(ix*TraversabilityGrid.CELL_SIZE,iz*TraversabilityGrid.CELL_SIZE,state,3,
            inflated=false,worldX=ix*TraversabilityGrid.CELL_SIZE+TraversabilityGrid.CELL_SIZE/2,
            worldZ=iz*TraversabilityGrid.CELL_SIZE+TraversabilityGrid.CELL_SIZE/2)
    /** Blocked cell plus a floor plus one open side, with staleness and inflation applied. */
    private fun mapWithSideOpen(openFrom: Int,openTo: Int,obstacleIz: Int): List<TraversabilityCell> {
        val corridor=(0..24).flatMap { zi -> (openFrom..openTo).map { xi -> cell(xi,-zi) } }
        return SessionGridMemory().update(corridor+cell(0,obstacleIz,TraversabilityState.OCCUPIED),camera())
    }
    /** A corridor open on both sides with one blocker in the middle: both detours cost the same. */
    private fun symmetricDetourMap(): List<TraversabilityCell> {
        val corridor=(0..24).flatMap { zi -> (-8..8).map { xi -> cell(xi,-zi) } }
        return SessionGridMemory().update(corridor+cell(0,-6,TraversabilityState.OCCUPIED),camera())
    }

    @Test fun sessionMemoryKeepsOldCellsAndReprojectsAfterPhoneMoves() {
        val memory=SessionGridMemory()
        val first=memory.update(listOf(free(0f,-1f)),camera()).single { it.state==TraversabilityState.FREE }
        val moved=memory.update(emptyList(),camera(.5f)).single { it.state==TraversabilityState.FREE }
        assertEquals(first.relativeX-.5f,moved.relativeX,.11f)
        memory.clear()
        assertTrue(memory.update(emptyList(),camera()).isEmpty())
    }

    @Test fun unknownFootAreaNeverProducesAdvanceInstruction() {
        val advice=LocalNavigator.advise((5..15).map { free(0f,-it/10f) })
        assertFalse(advice.canAdvance)
        assertTrue(advice.text.contains("脚下"))
    }

    @Test fun continuousObservedCellsProduceForwardInstruction() {
        val corridor=(0..16).flatMap { zi -> (-2..2).map { xi -> free(xi/10f,-zi/10f) } }
        val advice=LocalNavigator.advise(corridor)
        assertTrue(advice.canAdvance)
        assertTrue(advice.text.contains("向前"))
    }

    @Test fun closeMeasuredObstacleStopsGuidance() {
        val corridor=(0..16).flatMap { zi -> (-2..2).map { xi -> free(xi/10f,-zi/10f) } }.toMutableList()
        corridor+=TraversabilityCell(0f,-.5f,TraversabilityState.OCCUPIED,2,inflated=false,worldX=0f,worldZ=-.5f)
        val advice=LocalNavigator.advise(corridor)
        assertFalse(advice.canAdvance)
        assertTrue(advice.text.contains("停下"))
    }

    @Test fun ordinaryDoorwayRemainsConnectedBetweenTwoJambs() {
        val memory=SessionGridMemory()
        val observed=(0..16).flatMap { zi -> (-4..4).map { xi -> free(xi/10f,-zi/10f) } }.toMutableList()
        observed+=TraversabilityCell(-.45f,-1f,TraversabilityState.OCCUPIED,3,worldX=-.45f,worldZ=-1f)
        observed+=TraversabilityCell(.45f,-1f,TraversabilityState.OCCUPIED,3,worldX=.45f,worldZ=-1f)
        val map=memory.update(observed,camera())
        assertTrue(map.any { it.state==TraversabilityState.FREE && kotlin.math.abs(it.relativeX)<.11f &&
            it.relativeZ in -1.1f..-.9f })
        assertTrue(LocalNavigator.advise(map).canAdvance)
    }

    @Test fun repeatedFreeObservationsClearStaleObstacleAndItsInflation() {
        val memory=SessionGridMemory()
        val obstacle=TraversabilityCell(0f,-1f,TraversabilityState.OCCUPIED,3,
            minHeight=.4f,maxHeight=.8f,worldX=0f,worldZ=-1f)
        assertTrue(memory.update(listOf(obstacle),camera()).any { it.state==TraversabilityState.OCCUPIED })
        repeat(5) {
            assertTrue(memory.update(listOf(free(0f,-1f)),camera()).any { it.state==TraversabilityState.OCCUPIED })
        }
        val corrected=memory.update(listOf(free(0f,-1f)),camera())
        assertTrue(corrected.any { it.state==TraversabilityState.FREE })
        assertFalse(corrected.any { it.state==TraversabilityState.OCCUPIED })
    }

    @Test fun detourUsesTheRightWhenOnlyTheRightSideIsOpen() {        val advice=LocalNavigator.advise(mapWithSideOpen(openFrom=0,openTo=8,obstacleIz=-7))
        assertTrue(advice.text.contains("停下"))
        assertTrue(advice.text.contains("右前方"))
        assertFalse(advice.text.contains("左前方"))
    }

    @Test fun detourUsesTheLeftWhenOnlyTheLeftSideIsOpen() {
        val advice=LocalNavigator.advise(mapWithSideOpen(openFrom=-8,openTo=0,obstacleIz=-7))
        assertTrue(advice.text.contains("停下"))
        assertTrue(advice.text.contains("左前方"))
        assertFalse(advice.text.contains("右前方"))
    }

    @Test fun lookaheadExtendsPastAHazardThatIsFurtherAhead() {
        // Nearly a metre away: the instruction must already name the side, not wait
        // until the user has walked up to the blocked cell.
        val advice=LocalNavigator.advise(mapWithSideOpen(openFrom=0,openTo=8,obstacleIz=-11))
        assertTrue(advice.canAdvance)
        assertTrue(advice.text.contains("右前方"))
    }

    @Test fun twoCellsTouchingOnlyAtACornerDoNotFormACorridor() {
        // Everything leads to one diagonal gap; squeezing through it is not walking.
        val ahead=(0..3).map { cell(0,-it) } +(4..16).map { cell(1,-it) }
        val blocked=LocalNavigator.advise(ahead)
        assertFalse(blocked.canAdvance)
        assertTrue(blocked.text.contains("不连续"))
        // Filling one orthogonal neighbour makes the same route genuinely walkable.
        val opened=LocalNavigator.advise(ahead+cell(1,-3))
        assertTrue(opened.canAdvance)
    }

    @Test fun detourSideIsRememberedAndClearedAfterStraightAhead() {
        LocalNavigator.reset()
        assertEquals(0,LocalNavigator.preferredDetourSide())
        val advice=LocalNavigator.advise(symmetricDetourMap())
        assertTrue(advice.text.contains("左前方") || advice.text.contains("右前方"))
        val remembered=LocalNavigator.preferredDetourSide()
        assertTrue("a detour must record which side it chose",remembered!=0)
        // A straight corridor gives no side, so the memory has to fade again.
        val clear=(0..16).flatMap { zi -> (-2..2).map { xi -> cell(xi,-zi) } }
        repeat(5) { LocalNavigator.advise(clear) }
        assertEquals("a stale side must not bias a later obstacle",0,LocalNavigator.preferredDetourSide())
    }

    @Test fun symmetricDetourKeepsOneSideAcrossFrames() {
        LocalNavigator.reset()
        val map=symmetricDetourMap()
        val first=LocalNavigator.advise(map)
        repeat(9) {
            val next=LocalNavigator.advise(map)
            assertEquals("the detour side must not flip between frames",first.text,next.text)
        }
    }

    @Test fun geometryOverridesTheRememberedSideWhenOnlyOneSideIsOpen() {
        LocalNavigator.reset()
        // First a detour to the right, so the memory points right.
        val right=LocalNavigator.advise(mapWithSideOpen(openFrom=0,openTo=8,obstacleIz=-7))
        assertTrue(right.text.contains("右前方"))
        assertEquals(1,LocalNavigator.preferredDetourSide())
        // Now only the left side is walkable. Geometry has to win: a remembered side must
        // never make the planner walk towards something it knows is blocked.
        val left=LocalNavigator.advise(mapWithSideOpen(openFrom=-8,openTo=0,obstacleIz=-7))
        assertTrue(left.text.contains("左前方"))
        assertFalse(left.text.contains("右前方"))
    }

    @Test fun mappedSideRouteProducesDetourThenForwardRecovery() {
        val memory=SessionGridMemory()
        val corridor=(0..18).flatMap { zi -> (-6..6).map { xi -> free(xi/10f,-zi/10f) } }.toMutableList()
        val obstacle=TraversabilityCell(0f,-.7f,TraversabilityState.OCCUPIED,3,
            minHeight=.4f,maxHeight=1f,worldX=0f,worldZ=-.7f)
        val detour=LocalNavigator.advise(memory.update(corridor+obstacle,camera()))
        assertTrue(detour.text.contains("停下"))
        assertTrue(detour.text.contains("绕行"))
        assertTrue(detour.text.contains("左前方") || detour.text.contains("右前方"))

        val clearMemory=SessionGridMemory()
        val recovered=LocalNavigator.advise(clearMemory.update(corridor,camera()))
        assertTrue(recovered.canAdvance)
        assertTrue(recovered.text.contains("恢复"))
        assertTrue(recovered.text.contains("向前"))
    }
}
