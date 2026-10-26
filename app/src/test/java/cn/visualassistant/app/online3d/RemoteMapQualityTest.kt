package cn.visualassistant.app.online3d

import org.junit.Assert.*
import org.junit.Test

class RemoteMapQualityTest {
    private fun voxel(x:Int,z:Int,state:RemoteVoxelState)=RemoteVoxel(x,0,z,state,200,3,1)

    @Test fun reportsReliabilityWithoutCallingUnknownSpaceCovered(){
        val report=RemoteMapQualityAnalyzer.analyze(listOf(voxel(0,0,RemoteVoxelState.RELIABLE_FREE),voxel(1,0,RemoteVoxelState.RELIABLE_OCCUPIED),voxel(2,0,RemoteVoxelState.TENTATIVE),voxel(3,0,RemoteVoxelState.CONFLICT)),.1f,null)
        assertEquals(50,report.reliabilityPercent);assertEquals(1,report.tentative);assertEquals(1,report.conflict);assertNull(report.rescanHint)
    }

    @Test fun prioritizesConflictAndGivesRelativeHint(){
        val pose=RemotePose3(0f,0f,0f,0f,0f,-1f)
        val report=RemoteMapQualityAnalyzer.analyze(listOf(voxel(0,-10,RemoteVoxelState.CONFLICT),voxel(0,-2,RemoteVoxelState.TENTATIVE)),.1f,pose)
        assertTrue(report.rescanHint!!.contains("前方"));assertTrue(report.rescanHint!!.contains("冲突区域"))
    }
}
