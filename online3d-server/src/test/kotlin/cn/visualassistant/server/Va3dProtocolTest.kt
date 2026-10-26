package cn.visualassistant.server

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.*
import org.junit.Test

class Va3dProtocolTest {
    private fun packet(depth: Int = 1000, timestamp: Long = 4, persistent: Boolean = true): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(Va3dProtocol.MAGIC); output.writeShort(2); output.writeShort(1)
            output.writeLong(1); output.writeLong(2); output.writeLong(3)
            output.writeLong(timestamp); output.writeLong(5)
            output.writeInt(if(persistent) 1 else 0)
            output.writeInt(1); output.writeInt(1)
            output.writeFloat(10f); output.writeFloat(10f); output.writeFloat(0f); output.writeFloat(0f)
            val identity = floatArrayOf(1f,0f,0f,0f, 0f,1f,0f,0f, 0f,0f,1f,0f, 0f,0f,0f,1f)
            identity.forEach(output::writeFloat)
            output.writeShort(depth); output.writeByte(255)
        }
        return bytes.toByteArray()
    }

    @Test fun decodesV2Packet() {
        val frame = Va3dProtocol.decode(packet())
        assertEquals(1, frame.width)
        assertEquals(1000, frame.depthMillimeters[0].toInt() and 0xffff)
        assertTrue(frame.persistentMap)
    }

    @Test fun ddaMarksFreeRayAndOccupiedSurface() {
        val frame = Va3dProtocol.decode(packet())
        val map = SparseVoxelMap(voxelSize=.1f)
        assertTrue(map.integrate(frame,1))
        // One frame records free evidence, but reliability requires repeated observations.
        assertTrue(map.snapshot().values.any { it.score < 0 })
        assertEquals(0, map.freeCount())
        assertEquals(1, map.occupiedCount())
        assertTrue(map.evidence(VoxelKey(0,0,-10))!!.score > 0)
    }

    @Test fun duplicateDepthTimestampIsRejected() {
        val frame = Va3dProtocol.decode(packet())
        val map = SparseVoxelMap()
        assertTrue(map.integrate(frame,1))
        assertFalse(map.integrate(frame,1))
        assertEquals(1, map.revision)
    }

    @Test fun decoderRejectsDimensionMismatch() {
        assertFailsWith<IllegalArgumentException> { Va3dProtocol.decode(packet().copyOf(packet().size-1)) }
    }

    @Test fun segmentPacketKeepsChildAndParentIdentity(){
        val bytes=java.io.ByteArrayOutputStream();java.io.DataOutputStream(bytes).use{out->
            out.writeInt(Va3dProtocol.MAGIC);out.writeShort(Va3dProtocol.VERSION);out.writeShort(Va3dProtocol.MESSAGE_START_SEGMENT);out.writeLong(123L);out.writeLong(45L)
        }
        assertEquals(123L to 45L,Va3dProtocol.segmentSessions(bytes.toByteArray()))
    }
}
