package cn.visualassistant.app.online3d

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class RemoteMapProtocolTest {
    @Test fun decodesServerSnapshotAndAppliesIt(){
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->out.writeInt(0x56413344);out.writeShort(2);out.writeShort(3);out.writeLong(7);out.writeLong(0);out.writeLong(9);out.writeLong(11);out.writeFloat(.08f);out.writeInt(1);out.writeInt(1);floatArrayOf(1f,2f,3f,0f,0f,-1f).forEach(out::writeFloat);out.writeInt(1);out.writeInt(2);out.writeInt(3);out.writeByte(RemoteVoxelState.CONFLICT.ordinal);out.writeByte(201);out.writeShort(4);out.writeLong(99);out.writeByte(37);out.writeInt(4);out.writeInt(5);out.writeInt(6)}
        val packet=RemoteMapProtocol.decode(bytes.toByteArray());assertTrue(packet.snapshot);assertEquals(.08f,packet.voxelSize);assertArrayEquals(floatArrayOf(1f,2f,3f),packet.cameraPosition,0f);assertArrayEquals(floatArrayOf(0f,0f,-1f),packet.cameraForward,0f);assertEquals(9,packet.delta.revision);assertEquals(RemoteVoxelState.CONFLICT,packet.delta.changed.single().state);assertEquals(37,packet.delta.changed.single().surfaceMask)
        val map=RemoteVoxelMap();assertEquals(DeltaResult.Applied,map.replaceSnapshot(packet.delta));assertEquals(1,map.values().size)
    }
    @Test fun rejectsTruncatedPacket(){assertThrows(IllegalArgumentException::class.java){RemoteMapProtocol.decode(ByteArray(51))}}
}
