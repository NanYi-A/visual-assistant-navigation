package cn.visualassistant.server

import kotlin.test.*
import org.junit.Test

class VoxelEvidenceTest {
    @Test fun repeatedRaysInOneFrameCountOnce(){val m=SparseVoxelMap(.1f);repeat(5){m.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-1f),220,1,1)};val e=m.evidence(m.key(Vec3(0f,0f,-1f)))!!;assertEquals(1,e.occupiedObservations);assertEquals(3,e.score)}
    @Test fun twoDifferentViewsProduceReliableOccupied(){val m=SparseVoxelMap(.1f);val s=Vec3(0f,0f,-1f);m.integrateRay(Vec3(0f,0f,0f),s,220,1,1);m.integrateRay(Vec3(.5f,0f,-.5f),s,210,2,2);val e=m.evidence(m.key(s))!!;assertEquals(2,e.viewCount);assertEquals(VoxelState.RELIABLE_OCCUPIED,e.state());assertEquals(215.0,e.meanConfidence,.001);assertEquals(25.0,e.confidenceVariance,.001)}
    @Test fun contraryFreeEvidenceCanMarkConflict(){val m=SparseVoxelMap(.1f);val s=Vec3(0f,0f,-1f);m.integrateRay(Vec3(0f,0f,0f),s,220,1,1);m.integrateRay(Vec3(.5f,0f,-.5f),s,220,2,2);m.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-2f),220,3,3);m.integrateRay(Vec3(0f,.2f,0f),Vec3(0f,-.2f,-2f),220,4,4);assertEquals(VoxelState.CONFLICT,m.evidence(m.key(s))!!.state())}
    @Test fun outdoorMaintenancePrunesAndExpires(){val m=SparseVoxelMap(.1f,mapRadius=1f,outdoorLifetimeNs=100,decayAfterNs=10);m.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-.5f),220,1,1);m.integrateRay(Vec3(0f,0f,0f),Vec3(2f,0f,0f),220,2,1);assertTrue(m.maintain(Vec3(0f,0f,0f),5,false).removedOutsideRadius>0);assertTrue(m.maintain(Vec3(0f,0f,0f),200,false).removedExpired>0);assertTrue(m.snapshot().isEmpty())}
    @Test fun persistentMapDoesNotExpire(){val m=SparseVoxelMap(.1f,mapRadius=10f,outdoorLifetimeNs=100,decayAfterNs=10);m.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-.5f),220,1,1);m.maintain(Vec3(0f,0f,0f),1000,true);assertTrue(m.snapshot().isNotEmpty())}
}
