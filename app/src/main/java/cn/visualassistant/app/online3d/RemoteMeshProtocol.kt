package cn.visualassistant.app.online3d

import java.io.ByteArrayInputStream
import java.io.DataInputStream

data class RemotePoint3(val x:Float,val y:Float,val z:Float)
data class RemoteTriangle(val a:RemotePoint3,val b:RemotePoint3,val c:RemotePoint3,val normal:RemotePoint3)
data class RemoteMesh(val sessionId:Long,val revision:Long,val sourceFrameId:Long,val voxelSize:Float,val triangles:List<RemoteTriangle>)

object RemoteMeshProtocol {
    private const val MAGIC=0x56413344;private const val VERSION=2;const val TYPE=4;private const val HEADER=40;private const val TRIANGLE=48
    fun messageType(payload:ByteArray):Int=if(payload.size>=8)((payload[6].toInt() and 255) shl 8) or (payload[7].toInt() and 255) else -1
    fun decode(payload:ByteArray):RemoteMesh {
        require(payload.size in HEADER..Online3dProtocol.MAX_PACKET_BYTES)
        DataInputStream(ByteArrayInputStream(payload)).use{input->require(input.readInt()==MAGIC);require(input.readUnsignedShort()==VERSION);require(input.readUnsignedShort()==TYPE)
            val session=input.readLong();val revision=input.readLong();val source=input.readLong();val size=input.readFloat();val count=input.readInt();require(size>0&&count>=0&&HEADER.toLong()+count.toLong()*TRIANGLE==payload.size.toLong())
            fun point()=RemotePoint3(input.readFloat(),input.readFloat(),input.readFloat()).also{require(it.x.isFinite()&&it.y.isFinite()&&it.z.isFinite())}
            return RemoteMesh(session,revision,source,size,List(count){RemoteTriangle(point(),point(),point(),point())})
        }
    }
}
