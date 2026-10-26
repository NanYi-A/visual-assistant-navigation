package cn.visualassistant.app.online3d

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import org.json.JSONObject

data class Color3dFrame(
    val sessionId:Long,val frameId:Long,val frameTimestampNs:Long,val depthTimestampNs:Long,
    val imageTimestampNs:Long,val sentAtNs:Long,val width:Int,val height:Int,
    val fx:Float,val fy:Float,val cx:Float,val cy:Float,val cameraToWorld:FloatArray,
    val depthMillimeters:ShortArray,val confidence:ByteArray,val rgb:ByteArray,
)

data class RemoteColoredTriangle(
    val a:RemotePoint3,val b:RemotePoint3,val c:RemotePoint3,val normal:RemotePoint3,
    val ar:Int,val ag:Int,val ab:Int,val br:Int,val bg:Int,val bb:Int,val cr:Int,val cg:Int,val cb:Int,
)
data class RemoteColoredMesh(val sessionId:Long,val revision:Long,val sourceFrameId:Long,val voxelSize:Float,val triangles:List<RemoteColoredTriangle>)
data class RemoteLocalizationResult(val sessionId:Long,val parentModelId:Long,val localized:Boolean,val consistentPairs:Int,val inliers:Int,val overlap:Float,val medianErrorMeters:Float,val parentFromSession:FloatArray)
data class RemoteModelStatus(val sessionId:Long,val status:String,val available:Boolean,val navigationReady:Boolean,val optimizedPoseCoverage:Float,val triangleCount:Int,val message:String)

object Color3dProtocol {
    const val TYPE_FRAME=5
    const val TYPE_MESH=6
    const val TYPE_LOCALIZATION=9
    const val TYPE_MODEL_REQUEST=10
    const val TYPE_MODEL_STATUS=11
    const val TYPE_OPTIMIZED_MESH=12
    private const val HEADER=144
    private const val MESH_HEADER=40
    private const val TRIANGLE=57

    fun encode(frame:Color3dFrame):ByteArray {
        val pixels=frame.width*frame.height
        require(frame.width>0&&frame.height>0&&frame.cameraToWorld.size==16)
        require(frame.depthMillimeters.size==pixels&&frame.confidence.size==pixels&&frame.rgb.size==pixels*3)
        return ByteArrayOutputStream(HEADER+pixels*6).also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(Online3dProtocol.MAGIC);out.writeShort(Online3dProtocol.VERSION.toInt());out.writeShort(TYPE_FRAME)
            out.writeLong(frame.sessionId);out.writeLong(frame.frameId);out.writeLong(frame.frameTimestampNs)
            out.writeLong(frame.depthTimestampNs);out.writeLong(frame.imageTimestampNs);out.writeLong(frame.sentAtNs)
            out.writeInt(frame.width);out.writeInt(frame.height)
            out.writeFloat(frame.fx);out.writeFloat(frame.fy);out.writeFloat(frame.cx);out.writeFloat(frame.cy)
            frame.cameraToWorld.forEach(out::writeFloat)
            frame.depthMillimeters.forEach{out.writeShort(it.toInt() and 0xffff)}
            out.write(frame.confidence);out.write(frame.rgb)
        }}.toByteArray().also{require(it.size<=Online3dProtocol.MAX_PACKET_BYTES)}
    }

    fun decodeMesh(payload:ByteArray):RemoteColoredMesh {
        require(payload.size in MESH_HEADER..Online3dProtocol.MAX_PACKET_BYTES)
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt()==Online3dProtocol.MAGIC);require(input.readUnsignedShort()==Online3dProtocol.VERSION.toInt());require(input.readUnsignedShort() in setOf(TYPE_MESH,TYPE_OPTIMIZED_MESH))
            val session=input.readLong();val revision=input.readLong();val source=input.readLong();val voxel=input.readFloat();val count=input.readInt()
            require(voxel>0&&count>=0&&MESH_HEADER.toLong()+count.toLong()*TRIANGLE==payload.size.toLong())
            fun point()=RemotePoint3(input.readFloat(),input.readFloat(),input.readFloat()).also{require(it.x.isFinite()&&it.y.isFinite()&&it.z.isFinite())}
            fun color()=input.readUnsignedByte()
            return RemoteColoredMesh(session,revision,source,voxel,List(count){
                val a=point();val b=point();val c=point();val n=point()
                RemoteColoredTriangle(a,b,c,n,color(),color(),color(),color(),color(),color(),color(),color(),color())
            })
        }
    }
    fun messageType(payload:ByteArray):Int=if(payload.size>=8)java.nio.ByteBuffer.wrap(payload,6,2).order(java.nio.ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff else -1
    fun requestOptimized(sessionId:Long):ByteArray=ByteArrayOutputStream(16).also{bytes->DataOutputStream(bytes).use{out->out.writeInt(Online3dProtocol.MAGIC);out.writeShort(Online3dProtocol.VERSION.toInt());out.writeShort(TYPE_MODEL_REQUEST);out.writeLong(sessionId)}}.toByteArray()
    fun decodeModelStatus(payload:ByteArray):RemoteModelStatus=DataInputStream(ByteArrayInputStream(payload)).use{input->
        require(input.readInt()==Online3dProtocol.MAGIC);require(input.readUnsignedShort()==Online3dProtocol.VERSION.toInt());require(input.readUnsignedShort()==TYPE_MODEL_STATUS)
        val session=input.readLong();val size=input.readInt();require(size in 2..65536);val data=ByteArray(size);input.readFully(data);val json=JSONObject(String(data,Charsets.UTF_8))
        RemoteModelStatus(session,json.optString("status"),json.optBoolean("available"),json.optBoolean("navigationReady"),json.optDouble("optimizedPoseCoverage").toFloat(),json.optInt("triangleCount"),json.optString("message"))
    }
    fun decodeLocalization(payload:ByteArray):RemoteLocalizationResult=DataInputStream(ByteArrayInputStream(payload)).use{input->
        require(input.readInt()==Online3dProtocol.MAGIC);require(input.readUnsignedShort()==Online3dProtocol.VERSION.toInt());require(input.readUnsignedShort()==TYPE_LOCALIZATION)
        RemoteLocalizationResult(input.readLong(),input.readLong(),input.readUnsignedByte()==1,input.readInt(),input.readInt(),input.readFloat(),input.readFloat(),FloatArray(16){input.readFloat()})
    }
}
