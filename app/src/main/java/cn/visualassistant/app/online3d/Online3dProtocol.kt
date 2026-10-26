package cn.visualassistant.app.online3d

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A new ARCore raw-depth sample. All values share the same capture coordinate frame. */
data class Online3dFrame(
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

/** Versioned, network-byte-order payload inside a four-byte TCP length prefix. */
object Online3dProtocol {
    const val MAGIC = 0x56413344 // VA3D
    const val VERSION: Short = 2
    const val MESSAGE_FRAME: Short = 1
    const val MAX_PACKET_BYTES = 4 * 1024 * 1024

    fun encode(frame: Online3dFrame): ByteArray {
        require(frame.width > 0 && frame.height > 0)
        val pixels = frame.width * frame.height
        require(frame.cameraToWorld.size == 16)
        require(frame.depthMillimeters.size == pixels)
        require(frame.confidence.size == pixels)
        val bytes = ByteArrayOutputStream(128 + pixels * 3)
        DataOutputStream(bytes).use { out ->
            out.writeInt(MAGIC)
            out.writeShort(VERSION.toInt())
            out.writeShort(MESSAGE_FRAME.toInt())
            out.writeLong(frame.sessionId)
            out.writeLong(frame.frameId)
            out.writeLong(frame.frameTimestampNs)
            out.writeLong(frame.depthTimestampNs)
            out.writeLong(frame.sentAtNs)
            out.writeInt(if (frame.persistentMap) 1 else 0)
            out.writeInt(frame.width)
            out.writeInt(frame.height)
            out.writeFloat(frame.fx); out.writeFloat(frame.fy)
            out.writeFloat(frame.cx); out.writeFloat(frame.cy)
            frame.cameraToWorld.forEach(out::writeFloat)
            // DataOutputStream writes big-endian shorts, matching the packet header.
            frame.depthMillimeters.forEach { out.writeShort(it.toInt() and 0xffff) }
            out.write(frame.confidence)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_PACKET_BYTES) }
    }

    fun lengthPrefix(payloadSize: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(payloadSize).array()
}
