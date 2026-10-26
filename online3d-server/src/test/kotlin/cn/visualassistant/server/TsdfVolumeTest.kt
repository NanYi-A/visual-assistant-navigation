package cn.visualassistant.server

import kotlin.test.*
import org.junit.Test

class TsdfVolumeTest {
    @Test fun extractsContinuousPlaneInsteadOfVoxelBoxes(){
        val volume=TsdfVolume(.1f)
        for(x in 0..3)for(y in 0..3){volume.setSample(VoxelKey(x,y,0),-.5f);volume.setSample(VoxelKey(x,y,1),.5f)}
        val mesh=volume.extract(1,2)
        assertTrue(mesh.triangles.isNotEmpty());assertTrue(mesh.triangles.all{t->listOf(t.a,t.b,t.c).all{kotlin.math.abs(it.z-.05f)<.0001f}})
        assertTrue(mesh.triangles.all{kotlin.math.abs(it.normal.length()-1f)<.001f})
    }
    @Test fun emptyOrOneSidedFieldHasNoSurface(){val volume=TsdfVolume(.1f);for(x in 0..1)for(y in 0..1)for(z in 0..1)volume.setSample(VoxelKey(x,y,z),.5f);assertTrue(volume.extract(1,1).triangles.isEmpty())}
}
