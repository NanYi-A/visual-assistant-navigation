package cn.visualassistant.app.online3d

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import org.junit.Test
import org.junit.Assert.assertEquals

class HomeControlProtocolTest {
    @Test fun segmentPacketKeepsChildAndParentIdentity(){
        DataInputStream(ByteArrayInputStream(HomeControlProtocol.startSegment(123L,45L))).use{input->
            assertEquals(Online3dProtocol.MAGIC,input.readInt())
            assertEquals(Online3dProtocol.VERSION.toInt(),input.readUnsignedShort())
            assertEquals(HomeControlProtocol.TYPE_START_SEGMENT.toInt(),input.readUnsignedShort())
            assertEquals(123L,input.readLong());assertEquals(45L,input.readLong())
            assertEquals(-1,input.read())
        }
    }
}
