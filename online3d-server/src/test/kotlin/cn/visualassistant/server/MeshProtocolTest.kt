package cn.visualassistant.server

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import kotlin.test.*
import org.junit.Test

class MeshProtocolTest {
    @Test fun encodesTriangleSoupWithNormal(){
        val mesh=MeshSnapshot(7,9,11,.08f,listOf(MeshTriangle(Vec3(1f,2f,3f),Vec3(4f,5f,6f),Vec3(7f,8f,9f),Vec3(0f,1f,0f))))
        val bytes=MeshProtocol.encode(mesh);assertEquals(88,bytes.size)
        DataInputStream(ByteArrayInputStream(bytes)).use{input->assertEquals(Va3dProtocol.MAGIC,input.readInt());assertEquals(2,input.readUnsignedShort());assertEquals(4,input.readUnsignedShort());assertEquals(7,input.readLong());assertEquals(9,input.readLong());assertEquals(11,input.readLong());assertEquals(.08f,input.readFloat());assertEquals(1,input.readInt());assertEquals(1f,input.readFloat())}
    }
}
