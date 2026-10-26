package cn.visualassistant.app.online3d

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Assert.*
import org.junit.Test

class RemoteMeshProtocolTest {
    @Test fun decodesOneTriangle(){val bytes=ByteArrayOutputStream();DataOutputStream(bytes).use{out->out.writeInt(0x56413344);out.writeShort(2);out.writeShort(4);out.writeLong(7);out.writeLong(9);out.writeLong(11);out.writeFloat(.08f);out.writeInt(1);(1..12).forEach{out.writeFloat(it.toFloat())}};val mesh=RemoteMeshProtocol.decode(bytes.toByteArray());assertEquals(9,mesh.revision);assertEquals(1,mesh.triangles.size);assertEquals(1f,mesh.triangles[0].a.x);assertEquals(12f,mesh.triangles[0].normal.z)}
}
