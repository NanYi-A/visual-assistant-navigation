package cn.visualassistant.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

object MeshProtocol {
    const val MESSAGE_MESH=4;const val HEADER_BYTES=40;const val TRIANGLE_BYTES=48
    fun encode(mesh:MeshSnapshot):ByteArray {
        val expected=HEADER_BYTES+mesh.triangles.size*TRIANGLE_BYTES;require(expected<=MapProtocol.MAX_UPDATE_BYTES)
        val bytes=ByteArrayOutputStream(expected);DataOutputStream(bytes).use{out->
            out.writeInt(Va3dProtocol.MAGIC);out.writeShort(Va3dProtocol.VERSION);out.writeShort(MESSAGE_MESH)
            out.writeLong(mesh.sessionId);out.writeLong(mesh.revision);out.writeLong(mesh.sourceFrameId);out.writeFloat(mesh.voxelSize);out.writeInt(mesh.triangles.size)
            mesh.triangles.forEach{t->listOf(t.a,t.b,t.c,t.normal).forEach{v->out.writeFloat(v.x);out.writeFloat(v.y);out.writeFloat(v.z)}}
        };return bytes.toByteArray().also{check(it.size==expected)}
    }
}
