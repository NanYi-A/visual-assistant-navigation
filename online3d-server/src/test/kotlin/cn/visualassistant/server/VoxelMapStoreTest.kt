package cn.visualassistant.server

import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class VoxelMapStoreTest {
    private fun populated():SparseVoxelMap=SparseVoxelMap(.1f).also { map ->
        map.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-1f),220,1,10)
        map.integrateRay(Vec3(.5f,0f,-.5f),Vec3(0f,0f,-1f),210,2,20)
        map.revision=7;map.lastDepthTimestampNs=20
    }

    @Test fun roundTripPreservesEvidenceAndVersion(){
        val dir=Files.createTempDirectory("vamap");val file=dir.resolve("home.vamap");val source=populated();VoxelMapStore(file).save(source)
        val target=SparseVoxelMap(.1f);val result=VoxelMapStore(file).loadInto(target)
        assertFalse(result.recoveredFromBackup);assertEquals(source.revision,target.revision);assertEquals(source.lastDepthTimestampNs,target.lastDepthTimestampNs);assertEquals(source.snapshot(),target.snapshot())
    }

    @Test fun corruptPrimaryRecoversPreviousBackup(){
        val dir=Files.createTempDirectory("vamap");val file=dir.resolve("home.vamap");val store=VoxelMapStore(file);val first=populated();store.save(first)
        first.integrateRay(Vec3(1f,0f,0f),Vec3(1f,0f,-1f),220,3,30);store.save(first)
        Files.write(file,byteArrayOf(1,2,3));val target=SparseVoxelMap(.1f);val result=store.loadInto(target)
        assertTrue(result.recoveredFromBackup);assertEquals(7,target.revision);assertTrue(target.snapshot().isNotEmpty())
    }

    @Test fun corruptMapWithoutBackupIsRejected(){
        val dir=Files.createTempDirectory("vamap");val file=dir.resolve("home.vamap");Files.write(file,byteArrayOf(1,2,3))
        assertFailsWith<java.io.IOException>{VoxelMapStore(file).loadInto(SparseVoxelMap(.1f))}
    }
}
