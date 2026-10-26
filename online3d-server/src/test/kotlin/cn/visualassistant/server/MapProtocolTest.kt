package cn.visualassistant.server

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.*
import org.junit.Test

class MapProtocolTest {
    @Test fun encodesStableSnapshotLayout(){
        val update=MapUpdate(true,7,0,9,11,.08f,listOf(MapVoxel(VoxelKey(1,2,3),VoxelState.CONFLICT,201,4,99,37)),listOf(VoxelKey(4,5,6)),floatArrayOf(1f,2f,3f),floatArrayOf(0f,0f,-1f))
        val bytes=MapProtocol.encode(update);assertEquals(113,bytes.size)
        DataInputStream(ByteArrayInputStream(bytes)).use{input->assertEquals(Va3dProtocol.MAGIC,input.readInt());assertEquals(2,input.readUnsignedShort());assertEquals(3,input.readUnsignedShort());assertEquals(7,input.readLong());assertEquals(0,input.readLong());assertEquals(9,input.readLong());assertEquals(11,input.readLong());assertEquals(.08f,input.readFloat());assertEquals(1,input.readInt());assertEquals(1,input.readInt());assertContentEquals(floatArrayOf(1f,2f,3f),FloatArray(3){input.readFloat()});assertContentEquals(floatArrayOf(0f,0f,-1f),FloatArray(3){input.readFloat()});assertEquals(1,input.readInt());assertEquals(2,input.readInt());assertEquals(3,input.readInt());assertEquals(VoxelState.CONFLICT.ordinal,input.readUnsignedByte());assertEquals(201,input.readUnsignedByte());assertEquals(4,input.readUnsignedShort());assertEquals(99,input.readLong());assertEquals(37,input.readUnsignedByte())}
    }
    @Test fun snapshotReflectsVoxelEvidence(){val map=SparseVoxelMap(.1f);val s=Vec3(0f,0f,-1f);map.integrateRay(Vec3(0f,0f,0f),s,220,1,1_000_000);map.integrateRay(Vec3(.5f,0f,-.5f),s,210,2,2_000_000);map.revision=4;val update=MapProtocol.snapshot(8,2,map);assertTrue(update.snapshot);assertEquals(4,update.revision);assertTrue(update.changed.any{it.state==VoxelState.RELIABLE_OCCUPIED})}
    @Test fun trackerStartsWithSnapshotThenSendsRemovalDelta(){val map=SparseVoxelMap(.1f,mapRadius=.5f);map.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-.4f),220,1,1);map.revision=1;val tracker=MapDeltaTracker();val first=tracker.next(7,1,map);assertTrue(first.snapshot);assertEquals(0,first.baseRevision);assertTrue(first.changed.isNotEmpty());map.maintain(Vec3(10f,0f,0f),2,false);val second=tracker.next(7,2,map);assertFalse(second.snapshot);assertEquals(first.revision,second.baseRevision);assertTrue(second.removed.isNotEmpty())}
    @Test fun newSessionForcesSnapshot(){val map=SparseVoxelMap(.1f);map.integrateRay(Vec3(0f,0f,0f),Vec3(0f,0f,-.4f),220,1,1);map.revision=1;val tracker=MapDeltaTracker();tracker.next(7,1,map);assertTrue(tracker.next(8,2,map).snapshot)}
    @Test fun adjacentOccupiedVoxelsHideTheirSharedFace(){
        val a=VoxelKey(0,0,0);val b=VoxelKey(1,0,0);val evidence=VoxelEvidence(score=6,occupiedObservations=2)
        val cells=mapOf(a to evidence,b to evidence.copy())
        val maskA=MapProtocol.surfaceMask(a,cells.getValue(a),cells);val maskB=MapProtocol.surfaceMask(b,cells.getValue(b),cells)
        assertEquals(0,maskA and (1 shl 1));assertEquals(0,maskB and (1 shl 0));assertEquals(5,Integer.bitCount(maskA));assertEquals(5,Integer.bitCount(maskB))
    }
}
