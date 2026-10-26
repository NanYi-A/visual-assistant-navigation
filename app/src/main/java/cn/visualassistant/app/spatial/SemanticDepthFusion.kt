package cn.visualassistant.app.spatial

import cn.visualassistant.app.core.Detection
import kotlin.math.abs
import kotlin.math.max

data class DepthImageSample(val point: Point3, val u: Float, val v: Float)

data class CameraObjectObservation(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val point: Point3,
    val depthSupport: Int,
    val depthSpread: Float,
)

/** Pure detector/depth association. Coordinates are normalized before rotation. */
object SemanticDepthFusion {
    fun locate(
        detections: List<Detection>,
        samples: List<DepthImageSample>,
        imageRotation: Int,
    ): List<CameraObjectObservation> = detections.filter { it.confidence >= .55f }.mapNotNull { item ->
        val b = item.box
        val candidates = samples.filter { sample ->
            val (x, y) = detectorCoordinates(sample, imageRotation)
            x in (b.left + b.width * .2f)..(b.right - b.width * .2f) &&
                y in (b.top + b.height * .2f)..(b.bottom - b.height * .2f)
        }.map { it.point }.sortedBy { -it.z }
        if (candidates.size < MIN_SUPPORT) return@mapNotNull null
        val mid = candidates[candidates.size / 2]
        val spread = abs(candidates[candidates.size * 3 / 4].z - candidates[candidates.size / 4].z)
        if (spread > max(MAX_ABSOLUTE_SPREAD, -mid.z * MAX_RELATIVE_SPREAD)) null
        else CameraObjectObservation(item.classId, item.label, item.confidence, mid, candidates.size, spread)
    }

    internal fun detectorCoordinates(sample: DepthImageSample, rotation: Int): Pair<Float, Float> =
        when ((rotation % 360 + 360) % 360) {
            90 -> 1f - sample.v to sample.u
            180 -> 1f - sample.u to 1f - sample.v
            270 -> sample.v to 1f - sample.u
            else -> sample.u to sample.v
        }

    private const val MIN_SUPPORT = 5
    private const val MAX_ABSOLUTE_SPREAD = .25f
    private const val MAX_RELATIVE_SPREAD = .15f
}

/** Chooses a name only when that confirmed object is spatially close to the hazard surface. */
object FusedHazardSelector {
    fun objectNear(surface: SpatialMark, objects: List<SpatialObject>, maxSeparation: Float = .45f): SpatialObject? =
        objects.filter { it.ageMs <= 1500 }
            .minByOrNull { (it.relative - surface.relative).length() }
            ?.takeIf { (it.relative - surface.relative).length() <= maxSeparation }
}
