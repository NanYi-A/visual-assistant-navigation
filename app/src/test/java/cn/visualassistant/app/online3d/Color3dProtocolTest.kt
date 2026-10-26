package cn.visualassistant.app.online3d

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class Color3dProtocolTest {
    @Test fun localizationResultDecodes(){
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->out.writeInt(Online3dProtocol.MAGIC);out.writeShort(2);out.writeShort(9);out.writeLong(7);out.writeLong(5);out.writeByte(1);out.writeInt(3);out.writeInt(31);out.writeFloat(.62f);out.writeFloat(.056f);repeat(16){out.writeFloat(if(it%5==0)1f else 0f)}}
        val result=Color3dProtocol.decodeLocalization(bytes.toByteArray());assertTrue(result.localized);assertEquals(5,result.parentModelId);assertEquals(3,result.consistentPairs);assertEquals(31,result.inliers)
    }
    @Test fun frameHasDocumentedHeaderAndRgbPayload(){
        val frame=Color3dFrame(1,2,3,4,5,6,2,1,10f,11f,1f,.5f,
            FloatArray(16){if(it%5==0)1f else 0f},shortArrayOf(100,200),byteArrayOf(9,8),byteArrayOf(1,2,3,4,5,6))
        val payload=Color3dProtocol.encode(frame)
        assertEquals(144+12,payload.size);assertEquals(Color3dProtocol.TYPE_FRAME,RemoteMeshProtocol.messageType(payload))
    }

    @Test fun coloredTriangleDecodesVertexColors(){
        val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->
            out.writeInt(Online3dProtocol.MAGIC);out.writeShort(Online3dProtocol.VERSION.toInt());out.writeShort(Color3dProtocol.TYPE_MESH)
            out.writeLong(7);out.writeLong(8);out.writeLong(9);out.writeFloat(.04f);out.writeInt(1)
            repeat(12){out.writeFloat(it.toFloat())};(1..9).forEach(out::writeByte)
        }
        val mesh=Color3dProtocol.decodeMesh(bytes.toByteArray())
        assertEquals(1,mesh.triangles.size);assertEquals(1,mesh.triangles[0].ar);assertEquals(9,mesh.triangles[0].cb)
    }
}
