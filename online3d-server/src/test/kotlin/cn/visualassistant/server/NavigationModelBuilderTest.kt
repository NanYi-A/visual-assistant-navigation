package cn.visualassistant.server

import kotlin.test.*
import org.junit.Test

class NavigationModelBuilderTest{
    private fun occupied()=VoxelEvidence(score=9,occupiedObservations=3,confidenceSum=720,viewMask=3)
    private fun free()=VoxelEvidence(score=-8,freeObservations=4)
    @Test fun acceptsOnlyFullyObservedBodyClearance(){
        val map=SparseVoxelMap(.1f);val cells=hashMapOf<VoxelKey,VoxelEvidence>();cells[VoxelKey(0,0,0)]=occupied()
        val radius=2;val heights=listOf(2,7,11,16,18);for(y in heights)for(x in -radius..radius)for(z in -radius..radius)if(x*x+z*z<=radius*radius)cells[VoxelKey(x,y,z)]=free()
        map.restore(cells,12,1);assertEquals(1,NavigationModelBuilder.build(map).nodes.size)
        assertEquals(.15f,NavigationModelBuilder.build(map).bodyRadiusMeters)
        cells.remove(VoxelKey(0,18,0));map.restore(cells,13,1);assertTrue(NavigationModelBuilder.build(map).nodes.isEmpty())
    }
}
