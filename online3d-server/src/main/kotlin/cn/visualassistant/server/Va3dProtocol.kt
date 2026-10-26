package cn.visualassistant.server

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Va3dFrame(
    val sessionId: Long,
    val frameId: Long,
    val frameTimestampNs: Long,
    val depthTimestampNs: Long,
    val sentAtNs: Long,
    val persistentMap: Boolean,
    val width: Int,
    val height: Int,
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val cameraToWorld: FloatArray,
    val depthMillimeters: ShortArray,
    val confidence: ByteArray,
)

object Va3dProtocol {
    const val MAGIC = 0x56413344
    const val VERSION = 2
    const val MESSAGE_FRAME = 1
    const val MESSAGE_FINISH_HOME = 7
    const val MESSAGE_START_SEGMENT = 8
    const val HEADER_BYTES = 140
    const val MAX_PACKET_BYTES = 4 * 1024 * 1024
    fun messageType(payload:ByteArray):Int=if(payload.size>=8)ByteBuffer.wrap(payload,6,2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff else -1
    fun finishSession(payload:ByteArray):Long{DataInputStream(ByteArrayInputStream(payload)).use{input->require(input.readInt()==MAGIC);require(input.readUnsignedShort()==VERSION);require(input.readUnsignedShort()==MESSAGE_FINISH_HOME);return input.readLong()}}
    fun segmentSessions(payload:ByteArray):Pair<Long,Long>{DataInputStream(ByteArrayInputStream(payload)).use{input->require(input.readInt()==MAGIC);require(input.readUnsignedShort()==VERSION);require(input.readUnsignedShort()==MESSAGE_START_SEGMENT);return input.readLong() to input.readLong()}}

    fun decode(payload: ByteArray): Va3dFrame {
        require(payload.size in HEADER_BYTES..MAX_PACKET_BYTES) { "invalid payload size ${payload.size}" }
        DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "invalid VA3D magic" }
            require(input.readUnsignedShort() == VERSION) { "unsupported VA3D version" }
            require(input.readUnsignedShort() == MESSAGE_FRAME) { "unsupported VA3D message" }
            val sessionId = input.readLong()
            val frameId = input.readLong()
            val frameTimestampNs = input.readLong()
            val depthTimestampNs = input.readLong()
            val sentAtNs = input.readLong()
            val flags = input.readInt()
            val width = input.readInt()
            val height = input.readInt()
            require(width in 1..4096 && height in 1..4096)
            val pixels = Math.multiplyExact(width, height)
            require(payload.size == HEADER_BYTES + pixels * 3) { "dimensions do not match payload" }
            val fx = input.readFloat(); val fy = input.readFloat()
            val cx = input.readFloat(); val cy = input.readFloat()
            require(fx.isFinite() && fy.isFinite() && fx > 0f && fy > 0f)
            val matrix = FloatArray(16) { input.readFloat() }
            require(matrix.all(Float::isFinite))
            val depth = ShortArray(pixels) { input.readUnsignedShort().toShort() }
            val confidence = ByteArray(pixels)
            input.readFully(confidence)
            return Va3dFrame(sessionId, frameId, frameTimestampNs, depthTimestampNs, sentAtNs,
                flags and 1 != 0, width, height, fx, fy, cx, cy, matrix, depth, confidence)
        }
    }

    /** Reads the on-wire framing used by both the TCP socket and the Python recorder. */
    fun readLengthPrefixed(source: ByteArray): List<Va3dFrame> {
        val input = ByteArrayInputStream(source)
        val prefix = ByteArray(4)
        val frames = ArrayList<Va3dFrame>()
        while (true) {
            val read = input.read(prefix)
            if (read < 0) break
            require(read == 4) { "truncated packet length" }
            val size = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN).int
            require(size in HEADER_BYTES..MAX_PACKET_BYTES) { "invalid packet size $size" }
            val payload = ByteArray(size)
            require(input.read(payload) == size) { "truncated packet payload" }
            frames += decode(payload)
        }
        return frames
    }
}
