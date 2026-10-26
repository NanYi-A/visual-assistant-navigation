package cn.visualassistant.app.online3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteVoxelMapTest {
    private val voxel = RemoteVoxel(1, 2, 3, RemoteVoxelState.TENTATIVE, 120, 1, 10)

    @Test fun appliesContinuousRevisionsAndRemovals() {
        val map = RemoteVoxelMap(); map.beginSession(5)
        assertEquals(DeltaResult.Applied, map.apply(RemoteMapDelta(5, 0, 1, 7, listOf(voxel))))
        assertEquals(1, map.values().size)
        assertEquals(DeltaResult.Applied, map.apply(RemoteMapDelta(5, 1, 2, 8, emptyList(), listOf(VoxelKey(1,2,3)))))
        assertTrue(map.values().isEmpty())
    }

    @Test fun gapRequiresSnapshotAndDoesNotMutateMap() {
        val map = RemoteVoxelMap(); map.beginSession(5)
        assertEquals(DeltaResult.SnapshotRequired, map.apply(RemoteMapDelta(5, 1, 2, 7, listOf(voxel))))
        assertTrue(map.values().isEmpty())
        assertEquals(0, map.revision)
    }

    @Test fun rejectsWrongSessionAndStaleData() {
        val map = RemoteVoxelMap(); map.beginSession(5)
        assertEquals(DeltaResult.WrongSession, map.apply(RemoteMapDelta(6,0,1,1,listOf(voxel))))
        map.apply(RemoteMapDelta(5,0,1,1,listOf(voxel)))
        assertEquals(DeltaResult.Stale, map.apply(RemoteMapDelta(5,0,1,1,listOf(voxel))))
    }

    @Test fun displayCacheKeepsNearestCellsWithinLimit() {
        val map=RemoteVoxelMap();map.beginSession(5)
        val cells=(0 until 6).map{RemoteVoxel(it,0,0,RemoteVoxelState.TENTATIVE,180,1,1)}
        assertEquals(DeltaResult.Applied,map.apply(RemoteMapDelta(5,0,1,1,cells)))
        val shown=map.displayValues(floatArrayOf(0f,0f,0f),1f,3)
        assertEquals(3,shown.size);assertEquals(setOf(0,1,2),shown.map{it.x}.toSet())
        assertEquals(6,map.values().size)
    }
}
