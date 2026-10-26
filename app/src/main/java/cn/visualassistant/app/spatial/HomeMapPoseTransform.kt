package cn.visualassistant.app.spatial

import kotlin.math.hypot

/**
 * Rigid transform returned by the computer relocalizer.
 *
 * The Python backend serializes its NumPy 4x4 matrix in row-major order.  ARCore's live pose is
 * expressed in the new session frame; multiplying it by this matrix produces coordinates in the
 * persistent parent/home-map frame.  Keeping this conversion in one small, tested type prevents a
 * very plausible but dangerous error: planning with a current pose from one coordinate system and
 * destinations from another.
 */
data class HomeMapPoseTransform(
    val modelId: String,
    val parentFromSession: FloatArray,
    val localizedAtElapsedMs: Long,
    val consistentPairs: Int,
    val inliers: Int,
    val overlap: Float,
    val medianErrorMeters: Float,
) {
    init {
        require(parentFromSession.size == 16)
        require(parentFromSession.all { it.isFinite() })
    }

    fun point(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        parentFromSession[0] * x + parentFromSession[1] * y + parentFromSession[2] * z + parentFromSession[3],
        parentFromSession[4] * x + parentFromSession[5] * y + parentFromSession[6] * z + parentFromSession[7],
        parentFromSession[8] * x + parentFromSession[9] * y + parentFromSession[10] * z + parentFromSession[11],
    )

    fun direction(x: Float, y: Float, z: Float): FloatArray {
        val tx = parentFromSession[0] * x + parentFromSession[1] * y + parentFromSession[2] * z
        val ty = parentFromSession[4] * x + parentFromSession[5] * y + parentFromSession[6] * z
        val tz = parentFromSession[8] * x + parentFromSession[9] * y + parentFromSession[10] * z
        val length = kotlin.math.sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
        return floatArrayOf(tx / length, ty / length, tz / length)
    }

    fun horizontalPose(x: Float, y: Float, z: Float, forwardX: Float, forwardY: Float, forwardZ: Float): MapPose {
        val p = point(x, y, z)
        val f = direction(forwardX, forwardY, forwardZ)
        require(hypot(f[0], f[2]) > 0.05f)
        return MapPose(p[0], p[1], p[2], f[0], f[2])
    }
}

data class MapPose(val x: Float, val y: Float, val z: Float, val forwardX: Float, val forwardZ: Float)
