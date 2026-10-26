package cn.visualassistant.server

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Replays frames captured from a real phone and compares the Kotlin decoder
 * against reference values produced by tools/online_3d/protocol.py.
 *
 * The reference numbers come from the first three frames of
 * tools/online_3d/recordings/capture_20260929_163552_10_111_77_180.va3d
 * (REDMI K80, session 28120983266511, 160x90 raw depth).
 */
class ReplayConsistencyTest {
    private fun golden(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/golden_3frames.va3d")).use { it.readBytes() }

    /** Mirrors protocol.py iter_camera_points(stride=1): first pixel with enough confidence. */
    private fun firstCameraPoint(frame: Va3dFrame): Vec3? {
        for (y in 0 until frame.height) for (x in 0 until frame.width) {
            val index = y * frame.width + x
            if ((frame.confidence[index].toInt() and 255) < 180) continue
            val distance = (frame.depthMillimeters[index].toInt() and 0xffff) / 1000f
            if (distance <= 0f) continue
            return Vec3(
                (x - frame.cx) * distance / frame.fx,
                -(y - frame.cy) * distance / frame.fy,
                -distance,
            )
        }
        return null
    }

    @Test fun readsEveryLengthPrefixedFrame() {
        val frames = Va3dProtocol.readLengthPrefixed(golden())
        assertEquals(3, frames.size)
        assertEquals(listOf(1L, 2L, 3L), frames.map { it.frameId })
        assertEquals(28120983266511L, frames.first().sessionId)
        assertTrue(frames.all { it.width == 160 && it.height == 90 })
        assertFalse(frames.first().persistentMap, "outdoor session must not request a persistent map")
    }

    @Test fun depthTimestampsIncreaseWithinOneSession() {
        val frames = Va3dProtocol.readLengthPrefixed(golden())
        assertEquals(41832847479048L, frames[0].depthTimestampNs)
        assertEquals(41833214145715L, frames[1].depthTimestampNs)
        assertEquals(41833348128207L, frames[2].depthTimestampNs)
        assertTrue(frames.zipWithNext().all { (a, b) -> b.depthTimestampNs > a.depthTimestampNs })
    }

    @Test fun intrinsicsMatchThePythonReference() {
        val frame = Va3dProtocol.readLengthPrefixed(golden()).first()
        assertEquals(116.26381f, frame.fx, 1e-3f)
        assertEquals(116.26689f, frame.fy, 1e-3f)
        assertEquals(81.046356f, frame.cx, 1e-3f)
        assertEquals(44.733334f, frame.cy, 1e-3f)
        val origin = Vec3(frame.cameraToWorld[12], frame.cameraToWorld[13], frame.cameraToWorld[14])
        assertEquals(-0.03272686f, origin.x, 1e-4f)
        assertEquals(-0.01157182f, origin.y, 1e-4f)
        assertEquals(-0.02394138f, origin.z, 1e-4f)
    }

    @Test fun worldPointMatchesThePythonReference() {
        val frames = Va3dProtocol.readLengthPrefixed(golden())
        val expected = arrayOf(
            floatArrayOf(-0.024972934f, -0.24060397f, -0.43735070f),
            floatArrayOf(0.05297483f, -0.17382511f, -0.25127407f),
            floatArrayOf(0.04798715f, -0.06130258f, -0.32075508f),
        )
        frames.forEachIndexed { index, frame ->
            val camera = checkNotNull(firstCameraPoint(frame))
            val world = SparseVoxelMap.transform(frame.cameraToWorld, camera)
            // 0.1 mm tolerance: enough to catch endianness, transpose or sign errors.
            assertEquals(expected[index][0], world.x, 1e-4f, "frame ${index + 1} x")
            assertEquals(expected[index][1], world.y, 1e-4f, "frame ${index + 1} y")
            assertEquals(expected[index][2], world.z, 1e-4f, "frame ${index + 1} z")
        }
    }
}
