package cn.visualassistant.app.online3d

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

data class ConfirmedPlacePacket(val id:String,val name:String,val createdAt:Long,val x:Float,val y:Float,val z:Float,val forwardX:Float,val forwardY:Float,val forwardZ:Float)

object HomeControlProtocol{
    const val TYPE_FINISH:Short=7
    const val TYPE_START_SEGMENT:Short=8
    fun startSegment(sessionId:Long,parentSessionId:Long):ByteArray{
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->
            out.writeInt(Online3dProtocol.MAGIC);out.writeShort(Online3dProtocol.VERSION.toInt());out.writeShort(TYPE_START_SEGMENT.toInt());out.writeLong(sessionId);out.writeLong(parentSessionId)
        };return bytes.toByteArray()
    }
    fun finish(sessionId:Long,places:List<ConfirmedPlacePacket>):ByteArray{
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->
            out.writeInt(Online3dProtocol.MAGIC);out.writeShort(Online3dProtocol.VERSION.toInt());out.writeShort(TYPE_FINISH.toInt());out.writeLong(sessionId);out.writeInt(places.size)
            places.forEach{p->out.writeUTF(p.id);out.writeUTF(p.name);out.writeLong(p.createdAt);out.writeFloat(p.x);out.writeFloat(p.y);out.writeFloat(p.z);out.writeFloat(p.forwardX);out.writeFloat(p.forwardY);out.writeFloat(p.forwardZ)}
        };return bytes.toByteArray()
    }
}
