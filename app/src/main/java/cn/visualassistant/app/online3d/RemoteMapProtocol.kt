package cn.visualassistant.app.online3d

import java.io.ByteArrayInputStream
import java.io.DataInputStream

data class RemoteMapPacket(val snapshot:Boolean,val voxelSize:Float,val delta:RemoteMapDelta,val cameraPosition:FloatArray,val cameraForward:FloatArray)

object RemoteMapProtocol {
    private const val MAGIC=0x56413344;private const val VERSION=2;private const val DELTA=2;private const val SNAPSHOT=3;private const val MAX_BYTES=4*1024*1024
    fun decode(payload:ByteArray):RemoteMapPacket {
        require(payload.size in 76..MAX_BYTES)
        DataInputStream(ByteArrayInputStream(payload)).use{input->
            require(input.readInt()==MAGIC);require(input.readUnsignedShort()==VERSION);val kind=input.readUnsignedShort();require(kind==DELTA||kind==SNAPSHOT)
            val session=input.readLong();val base=input.readLong();val revision=input.readLong();val source=input.readLong();val voxelSize=input.readFloat();require(voxelSize>0)
            val changedCount=input.readInt();val removedCount=input.readInt();require(changedCount>=0&&removedCount>=0);require(76L+changedCount*25L+removedCount*12L==payload.size.toLong())
            val cameraPosition=FloatArray(3){input.readFloat()};val cameraForward=FloatArray(3){input.readFloat()};require(cameraPosition.all(Float::isFinite)&&cameraForward.all(Float::isFinite))
            val changed=ArrayList<RemoteVoxel>(changedCount);repeat(changedCount){val x=input.readInt();val y=input.readInt();val z=input.readInt();val state=RemoteVoxelState.entries[input.readUnsignedByte()];val confidence=input.readUnsignedByte();val observations=input.readUnsignedShort();val updated=input.readLong();val surfaceMask=input.readUnsignedByte();require(surfaceMask and 0xc0==0);changed+=RemoteVoxel(x,y,z,state,confidence,observations,updated,surfaceMask)}
            val removed=ArrayList<VoxelKey>(removedCount);repeat(removedCount){removed+=VoxelKey(input.readInt(),input.readInt(),input.readInt())}
            return RemoteMapPacket(kind==SNAPSHOT,voxelSize,RemoteMapDelta(session,base,revision,source,changed,removed),cameraPosition,cameraForward)
        }
    }
}
