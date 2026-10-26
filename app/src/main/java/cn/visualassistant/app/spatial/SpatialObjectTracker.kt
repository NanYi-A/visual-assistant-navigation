package cn.visualassistant.app.spatial

import kotlin.math.hypot

enum class FusionConfidence { LOW, MEDIUM, HIGH }

/** Short-lived object identity inside one ARCore coordinate system. */
class SpatialObjectTracker(
    private val associationMeters: Float = .6f,
    private val expiryMs: Long = 2_000,
) {
    private data class Track(
        val id: Long,
        val classId: Int,
        val label: String,
        var world: Point3,
        var firstSeen: Long,
        var lastSeen: Long,
        var lastObservationAt: Long,
        var lastDistance: Float,
        var approachMps: Float = 0f,
    )
    private val tracks = mutableListOf<Track>()
    private var nextId = 1L

    fun update(objects: List<SpatialObject>, now: Long): List<SpatialObject> {
        tracks.removeAll { now - it.lastSeen > expiryMs }
        val available = tracks.toMutableSet()
        return objects.filter { it.ageMs <= 1500 }.sortedBy { it.ageMs }.map { item ->
            val observedAt = now - item.ageMs
            val match = available.filter { it.classId == item.classId }
                .minByOrNull { (it.world - item.world).length() }
                ?.takeIf { (it.world - item.world).length() <= associationMeters }
            val distance = hypot(item.relative.x, item.relative.z)
            val track = match ?: Track(nextId++, item.classId, item.label, item.world,
                observedAt, observedAt, observedAt, distance).also { tracks += it }
            available.remove(track)
            if (observedAt > track.lastObservationAt) {
                val dt = (observedAt - track.lastObservationAt) / 1000f
                val measured = if (dt > 0f) (track.lastDistance - distance) / dt else 0f
                track.approachMps = track.approachMps * .65f + measured.coerceIn(-3f, 3f) * .35f
                track.lastDistance = distance
                track.lastObservationAt = observedAt
                track.world = item.world
            }
            track.lastSeen = maxOf(track.lastSeen, observedAt)
            val stableMs = (observedAt - track.firstSeen).coerceAtLeast(0)
            val quality = if (item.confidence >= .7f && item.depthSupport >= 8 &&
                item.depthSpread <= .12f && stableMs >= 600) FusionConfidence.HIGH else FusionConfidence.MEDIUM
            item.copy(trackId=track.id, stableMs=stableMs, approachMps=track.approachMps, fusionConfidence=quality)
        }
    }

    fun clear() { tracks.clear(); nextId = 1L }
}

data class SpatialRiskAssessment(
    val level: cn.visualassistant.app.core.RiskLevel,
    val message: String,
    val target: SpatialObject? = null,
)

/** Risk policy for the ARCore page. It never infers distance from a 2D box. */
object SpatialRiskEngine {
    fun assess(snapshot: SpatialSnapshot, now: Long): SpatialRiskAssessment {
        if (!snapshot.usable || now-snapshot.time > 1500)
            return SpatialRiskAssessment(cn.visualassistant.app.core.RiskLevel.SAFE, "")
        val candidates = snapshot.objects.filter { it.ageMs <= 1500 && it.relative.z in -2.2f..-.1f }
        val assessed = candidates.map { it to levelFor(it,snapshot) }.filter { it.second!=cn.visualassistant.app.core.RiskLevel.SAFE }
        val selected = assessed.maxWithOrNull(compareBy<Pair<SpatialObject,cn.visualassistant.app.core.RiskLevel>> { it.second.ordinal }
            .thenBy { score(it.first,snapshot) })
        val target = selected?.first
        if (target != null) {
            val distance = hypot(target.relative.x,target.relative.z)
            val level=selected.second
            if(level != cn.visualassistant.app.core.RiskLevel.SAFE) {
                val side=side(target.relative)
                val meters=String.format(java.util.Locale.CHINA,"%.1f",distance)
                val noun=if(target.fusionConfidence==FusionConfidence.HIGH) "有${target.label}" else "可能有${target.label}"
                val prefix=if(level==cn.visualassistant.app.core.RiskLevel.DANGER) "危险，请停下，" else if(level==cn.visualassistant.app.core.RiskLevel.WARNING) "注意，" else "提示，"
                return SpatialRiskAssessment(level,"$prefix$side 约${meters}米$noun。${snapshot.guidance.text}",target)
            }
        }
        val surface=snapshot.marks.filter { it.ageMs<=1000 && kotlin.math.abs(it.relative.x)<.3f &&
            it.relative.z in (-1.2f)..(-.25f) && it.relative.y in (-.8f)..(.8f) }
            .minByOrNull { hypot(it.relative.x,it.relative.z) }
            ?: return SpatialRiskAssessment(cn.visualassistant.app.core.RiskLevel.SAFE,"")
        val distance=hypot(surface.relative.x,surface.relative.z)
        val meters=String.format(java.util.Locale.CHINA,"%.1f",distance)
        return SpatialRiskAssessment(cn.visualassistant.app.core.RiskLevel.DANGER,
            "危险，请停下，${side(surface.relative)}约${meters}米有物体表面。${snapshot.guidance.text}")
    }

    private fun score(item: SpatialObject,snapshot: SpatialSnapshot): Float {
        val distance=hypot(item.relative.x,item.relative.z).coerceAtLeast(.1f)
        val central=(1f-kotlin.math.abs(item.relative.x)/.8f).coerceIn(0f,1f)
        return 1f/distance+central+item.approachMps.coerceAtLeast(0f)+if(occupiedNear(item,snapshot.grid)) .5f else 0f
    }
    private fun levelFor(item: SpatialObject,snapshot: SpatialSnapshot): cn.visualassistant.app.core.RiskLevel {
        val distance=hypot(item.relative.x,item.relative.z)
        val central=kotlin.math.abs(item.relative.x)<=.45f
        val routeRelated=central || occupiedNear(item,snapshot.grid)
        var level=when {
            central && distance<=.7f -> cn.visualassistant.app.core.RiskLevel.DANGER
            routeRelated && distance<=1.2f -> cn.visualassistant.app.core.RiskLevel.WARNING
            routeRelated && distance<=2f -> cn.visualassistant.app.core.RiskLevel.NOTICE
            else -> cn.visualassistant.app.core.RiskLevel.SAFE
        }
        if(item.approachMps>=.45f && routeRelated && level!=cn.visualassistant.app.core.RiskLevel.DANGER)
            level=cn.visualassistant.app.core.RiskLevel.entries[level.ordinal+1]
        return level
    }
    private fun occupiedNear(item: SpatialObject,grid: List<TraversabilityCell>) = grid.any {
        it.state==TraversabilityState.OCCUPIED && !it.inflated &&
            hypot(it.relativeX-item.relative.x,it.relativeZ-item.relative.z)<=.35f
    }
    private fun side(point: Point3)=when {
        point.x<-.25f -> "左前方"
        point.x>.25f -> "右前方"
        else -> "前方"
    }
}
