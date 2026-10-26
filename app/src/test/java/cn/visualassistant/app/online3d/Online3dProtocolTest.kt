package cn.visualassistant.app.online3d

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Online3dProtocolTest {
    @Test fun packetHasStableHeaderAndExpectedSize() {
        val frame = Online3dFrame(7, 9, 11, 13, 15, true, 2, 2,
            10f, 20f, 1f, 2f, FloatArray(16) { it.toFloat() },
            shortArrayOf(1, 2, 6553, 32767), byteArrayOf(4, 5, 6, 7))
        val bytes = Online3dProtocol.encode(frame)
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(Online3dProtocol.MAGIC, input.int)
        assertEquals(Online3dProtocol.VERSION, input.short)
        assertEquals(Online3dProtocol.MESSAGE_FRAME, input.short)
        assertEquals(7L, input.long)
        assertEquals(9L, input.long)
        assertEquals(140 + 12, bytes.size)
    }
}
