package cn.visualassistant.app.spatial

import kotlin.math.*
import java.util.Locale
import java.util.PriorityQueue

data class Point3(val x: Float, val y: Float, val z: Float) {
    operator fun minus(other: Point3) = Point3(x - other.x, y - other.y, z - other.z)
    fun length() = sqrt(x * x + y * y + z * z)
}

/** Column-major rigid camera-to-world transform, meters, OpenGL camera looks along -Z. */
class CameraTransform(private val m: FloatArray) {
    init { require(m.size == 16 && m.all { it.isFinite() }) }
    val origin get() = Point3(m[12], m[13], m[14])

    /**
     * Horizontal facing direction in world coordinates.
     *
     * Null when the phone points at the ceiling or floor: the projected heading then swings wildly
     * for a tiny tilt, and a turn instruction computed from it would be confidently wrong. A caller
     * that cannot get a heading here must say so rather than fall back to a default direction.
     */
    val forward: Point3? get() {
        val forwardLength = sqrt(m[8]*m[8] + m[10]*m[10])
        if (forwardLength < .4f) return null
        return Point3(-m[8]/forwardLength, 0f, -m[10]/forwardLength)
    }

    /** Full 3-D facing direction; used for place anchors where we want the saved pose to match the camera. */
    val fullForward: Point3? get() {
        val len = sqrt(m[8]*m[8] + m[9]*m[9] + m[10]*m[10])
        if (len < .1f) return null
        return Point3(-m[8]/len, -m[9]/len, -m[10]/len)
    }

    fun world(p: Point3) = Point3(m[0]*p.x + m[4]*p.y + m[8]*p.z + m[12],
        m[1]*p.x + m[5]*p.y + m[9]*p.z + m[13], m[2]*p.x + m[6]*p.y + m[10]*p.z + m[14])
    fun relative(p: Point3): Point3? {
        val forwardLength = sqrt(m[8]*m[8] + m[10]*m[10])
        if (forwardLength < .4f) return null // Phone pointed at ceiling/floor; no stable horizontal heading.
        val d = p - origin
        val fx = -m[8]/forwardLength
        val fz = -m[10]/forwardLength
        return Point3(-fz*d.x + fx*d.z, d.y, -(fx*d.x + fz*d.z))
    }
}

data class WorldSample(val point: Point3, val frame: Long, val time: Long, val label: String? = null,
    val cameraOrigin: Point3? = null, val classId: Int? = null, val confidence: Float? = null,
    val depthSupport: Int = 0, val depthSpread: Float? = null)
data class SpatialMark(val relative: Point3, val label: String?, val ageMs: Long)
data class SpatialObject(val classId: Int, val label: String, val confidence: Float,
    val relative: Point3, val ageMs: Long, val depthSupport: Int, val depthSpread: Float,
    val world: Point3 = relative, val trackId: Long = 0, val stableMs: Long = 0,
    val approachMps: Float = 0f, val fusionConfidence: FusionConfidence = FusionConfidence.MEDIUM)
enum class TraversabilityState { FREE, OCCUPIED }
data class TraversabilityCell(val relativeX: Float, val relativeZ: Float, val state: TraversabilityState,
    val evidence: Int, val minHeight: Float? = null, val maxHeight: Float? = null,
    val inflated: Boolean = false, val worldX: Float? = null, val worldZ: Float? = null)
data class LocalGuidance(val text: String = "请先扫描脚下到前方的地面", val canAdvance: Boolean = false)
enum class ScanPhase { IDLE, LOCATING, WAITING_DEPTH, ACCUMULATING, READY, LOST, LEVEL_PHONE, ERROR }
data class SpatialSnapshot(val time: Long = 0, val marks: List<SpatialMark> = emptyList(),
    val status: String = "尚未开始空间扫描", val usable: Boolean = false,
    val phase: ScanPhase = if (usable) ScanPhase.READY else ScanPhase.IDLE,
    val grid: List<TraversabilityCell> = emptyList(), val floorHeight: Float? = null,
    val objects: List<SpatialObject> = emptyList(),
    val guidance: LocalGuidance = LocalGuidance()) {
    fun describe(question: String, now: Long, maxDirections: Int = 4): String {
        if (!usable || now - time > 1500) return "${if (usable) "空间数据已过期" else status}，暂不能提供方向和距离。"
        val navigation = listOf("怎么走", "绕", "能走", "可以走", "往哪").any { it in question }
        if (navigation) return guidance.text
        val named = objects.filter { question.contains(it.label) }
        val asksNamed = listOf("在哪", "哪里", "多远").any { it in question } &&
            listOf("障碍", "物体", "东西", "前方", "前面").none { it in question }
        if (named.isNotEmpty()) {
            val parts = named.sortedBy { it.relative.length() }.take(maxDirections).map { item ->
                val distance = String.format(Locale.CHINA, "%.1f", (item.relative.length() * 2).roundToInt()/2f)
                "${sector(item.relative)} 约${distance}米${objectNoun(item)}${heightText(item.relative)}${freshness(item.ageMs)}"
            }
            return parts.joinToString("；") + "。未扫描区域仍未知，距离仅为估计。"
        }
        if (asksNamed) return "地图里还没有找到这个物体的可靠位置，请重新扫描。"
        val selectedObjects = when {
            "左" in question -> objects.filter { it.relative.x < -.3f }
            "右" in question -> objects.filter { it.relative.x > .3f }
            "前" in question -> objects.filter { it.relative.z < 0 && abs(it.relative.x) < -it.relative.z }
            else -> objects
        }
        if(selectedObjects.isNotEmpty()) {
            val parts=selectedObjects.sortedBy { it.relative.length() }.take(maxDirections).map { item ->
                val distance=String.format(Locale.CHINA,"%.1f",(item.relative.length()*2).roundToInt()/2f)
                "${sector(item.relative)} 约${distance}米${objectNoun(item)}${heightText(item.relative)}${freshness(item.ageMs)}"
            }
            return parts.joinToString("；")+"。未扫描区域仍未知，距离仅为估计。"
        }
        val selected = when {
            "左" in question -> marks.filter { it.relative.x < -.3f }
            "右" in question -> marks.filter { it.relative.x > .3f }
            "前" in question -> marks.filter { it.relative.z < 0 && abs(it.relative.x) < -it.relative.z }
            else -> marks
        }
        val groups = selected.groupBy { sector(it.relative) }
        val parts = groups.entries.sortedBy { (_, values) -> values.minOf { it.relative.length() } }
            .take(maxDirections).map { (side, values) ->
                val nearest = values.minBy { it.relative.length() }
                val distance = String.format(Locale.CHINA, "%.1f", (nearest.relative.length() * 2).roundToInt()/2f)
                "$side 约${distance}米有物体表面${heightText(nearest.relative)}${freshness(nearest.ageMs)}"
            }
        val content = if (parts.isEmpty()) "这个方向还没有可靠的空间记录。" else parts.joinToString("；") + "。"
        return content + "未扫描区域仍未知，距离仅为估计。"
    }

    companion object {
        private fun heightText(relative: Point3) = when {
            relative.y > .4f -> "，高于手机"
            relative.y < -.4f -> "，低于手机"
            else -> "，接近手机高度"
        }
        private fun freshness(ageMs: Long) = if (ageMs > 1200) "，来自之前的扫描" else ""
        private fun objectNoun(item: SpatialObject) =
            if(item.fusionConfidence==FusionConfidence.HIGH) "有${item.label}" else "可能有${item.label}"
        fun sector(p: Point3): String {
            val angle = atan2(p.x, -p.z) * 180 / PI
            val index = ((angle / 45).roundToInt() + 8) % 8
            return listOf("前方", "右前方", "右侧", "右后方", "后方", "左后方", "左侧", "左前方")[index]
        }
    }
}

/** Only observed occupied surfaces. Absence of cells never means free space. */
object SpatialMap {
    private data class Cell(val x: Int, val y: Int, val z: Int, val label: String?)
    fun build(samples: List<WorldSample>, camera: CameraTransform, now: Long): SpatialSnapshot {
        if (camera.relative(camera.world(Point3(0f, 0f, -1f))) == null)
            return SpatialSnapshot(now, status = "请将手机朝向前方", phase = ScanPhase.LEVEL_PHONE)
        val recent = samples.filter { now - it.time in 0..8000 && it.point.length().isFinite() &&
            (it.point - camera.origin).length() in .25f..6f }
        val cells = recent.groupBy {
            Cell(floor(it.point.x/.2f).toInt(), floor(it.point.y/.2f).toInt(), floor(it.point.z/.2f).toInt(), it.label)
        }
        val marks = cells.values.filter { it.map { s -> s.frame }.distinct().size >= 2 }.mapNotNull { values ->
            val latest = values.maxBy { it.time }
            camera.relative(latest.point)?.let { SpatialMark(it, latest.label, now - latest.time) }
        }.sortedBy { it.relative.length() }.take(2500)
        val objects = cells.values.filter { values ->
            values.first().label != null && values.map { it.frame }.distinct().size >= 2
        }.mapNotNull { values ->
            val latest = values.maxBy { it.time }
            val relative = camera.relative(latest.point) ?: return@mapNotNull null
            SpatialObject(latest.classId ?: return@mapNotNull null, latest.label ?: return@mapNotNull null,
                values.mapNotNull { it.confidence }.maxOrNull() ?: return@mapNotNull null,
                relative, now-latest.time, values.maxOf { it.depthSupport },
                values.mapNotNull { it.depthSpread }.maxOrNull() ?: 0f, latest.point)
        }.sortedBy { it.relative.length() }.take(100)
        val grid = TraversabilityGrid.build(cells.values.filter { values ->
            values.map { it.frame }.distinct().size >= 2
        }.map { values -> values.maxBy { it.time } }, camera)
        val status = when {
            marks.isEmpty() -> "正在确认空间表面，请原地缓慢平移手机"
            grid.floorHeight == null -> "已看到空间表面，正在确认地面，请缓慢扫向脚前方"
            else -> "候选可走 ${grid.cells.count { it.state == TraversabilityState.FREE }} 格，障碍 ${grid.cells.count { it.state == TraversabilityState.OCCUPIED }} 格；灰色区域仍未知"
        }
        return SpatialSnapshot(now, marks, status, marks.isNotEmpty(),
            if (marks.isEmpty()) ScanPhase.ACCUMULATING else ScanPhase.READY,
            grid.cells, grid.floorHeight, objects)
    }
}

/** Lightweight local 2.5D grid. Unknown cells are intentionally omitted. */
object TraversabilityGrid {
    const val CELL_SIZE = .1f
    private const val RADIUS_METERS = 6f
    private const val MIN_FLOOR_SUPPORT = 12
    private const val MIN_FLOOR_FRAMES = 3
    // Raw Depth becomes visibly noisy when the floor is viewed at a shallow angle.
    // A narrow 7 cm band turned that noise into thousands of false obstacles.
    private const val FREE_BAND_BELOW = .18f
    private const val FLOOR_SURFACE_ABOVE = .18f
    private const val RAY_GROUND_BAND_ABOVE = .28f
    // Low kerbs still need a dedicated downward-looking detector. This preview only
    // labels height differences that are separable from phone depth noise.
    private const val MIN_OBSTACLE_HEIGHT = .22f
    private const val MAX_OBSTACLE_HEIGHT = 2f
    // Experimental narrow-door setting. At 10 cm resolution this becomes two cells
    // (~20 cm). The UI must keep calling this a candidate path, not guaranteed clearance.
    private const val INFLATION_METERS = .15f
    private const val FREE_THRESHOLD = 2
    private const val OCCUPIED_THRESHOLD = 2

    data class Result(val cells: List<TraversabilityCell>, val floorHeight: Float?)
    private data class Index(val x: Int, val z: Int)
    private data class Evidence(var free: Int = 0, var occupied: Int = 0,
        var minHeight: Float = Float.POSITIVE_INFINITY, var maxHeight: Float = Float.NEGATIVE_INFINITY)

    fun build(samples: List<WorldSample>, camera: CameraTransform): Result {
        val floor = estimateFloor(samples, camera.origin) ?: return Result(emptyList(), null)
        val evidence = HashMap<Index, Evidence>()
        samples.forEach { sample ->
            if ((sample.point - camera.origin).length() > RADIUS_METERS) return@forEach
            val height = sample.point.y - floor
            val endpoint = index(sample.point)
            when {
                height in -FREE_BAND_BELOW..FLOOR_SURFACE_ABOVE -> {
                    evidence.getOrPut(endpoint) { Evidence() }.free += 2
                    sample.cameraOrigin?.let { origin -> carveGroundRay(origin, sample.point, floor, evidence) }
                }
                height in MIN_OBSTACLE_HEIGHT..MAX_OBSTACLE_HEIGHT -> {
                    evidence.getOrPut(endpoint) { Evidence() }.apply {
                        occupied += 2
                        minHeight = min(minHeight, height)
                        maxHeight = max(maxHeight, height)
                    }
                }
            }
        }
        val occupied = evidence.filterValues { it.occupied >= OCCUPIED_THRESHOLD }.keys
        val inflated = occupied.toMutableSet()
        val inflationCells = ceil(INFLATION_METERS / CELL_SIZE).toInt()
        occupied.forEach { center ->
            for (dx in -inflationCells..inflationCells) for (dz in -inflationCells..inflationCells)
                if (dx * dx + dz * dz <= inflationCells * inflationCells) inflated += Index(center.x + dx, center.z + dz)
        }
        val indexes = evidence.keys + inflated
        val cells = indexes.mapNotNull { idx ->
            val world = Point3((idx.x + .5f) * CELL_SIZE, camera.origin.y, (idx.z + .5f) * CELL_SIZE)
            val relative = camera.relative(world) ?: return@mapNotNull null
            if (hypot(relative.x, relative.z) > RADIUS_METERS) return@mapNotNull null
            val value = evidence[idx]
            when {
                idx in inflated -> TraversabilityCell(relative.x, relative.z, TraversabilityState.OCCUPIED,
                    value?.occupied ?: OCCUPIED_THRESHOLD,
                    value?.minHeight?.takeIf { it.isFinite() }, value?.maxHeight?.takeIf { it.isFinite() }, idx !in occupied,
                    world.x, world.z)
                value != null && value.free >= FREE_THRESHOLD -> TraversabilityCell(relative.x, relative.z,
                    TraversabilityState.FREE, value.free, worldX=world.x, worldZ=world.z)
                else -> null
            }
        }.sortedWith(compareBy<TraversabilityCell> { it.state }.thenBy { hypot(it.relativeX, it.relativeZ) })
        return Result(cells, floor)
    }

    private fun estimateFloor(samples: List<WorldSample>, camera: Point3): Float? {
        val candidates = samples.filter { sample ->
            val below = camera.y - sample.point.y
            below in .45f..2.2f && hypot(sample.point.x - camera.x, sample.point.z - camera.z) <= RADIUS_METERS
        }
        if (candidates.size < MIN_FLOOR_SUPPORT) return null
        // Densest 36 cm horizontal band, implemented as a sorted sliding window.
        // Unlike a fixed histogram this does not split one noisy floor at a bin edge.
        val sorted = candidates.sortedBy { it.point.y }
        var left = 0
        var bestStart = 0
        var bestEnd = -1
        for (right in sorted.indices) {
            while (sorted[right].point.y - sorted[left].point.y > FREE_BAND_BELOW + FLOOR_SURFACE_ABOVE) left++
            if (right - left > bestEnd - bestStart) { bestStart = left; bestEnd = right }
        }
        if (bestEnd < bestStart) return null
        val best = sorted.subList(bestStart, bestEnd + 1)
        if (best.size < MIN_FLOOR_SUPPORT || best.map { it.frame }.distinct().size < MIN_FLOOR_FRAMES) return null
        return best.map { it.point.y }.sorted()[best.size / 2]
    }

    private fun carveGroundRay(origin: Point3, endpoint: Point3, floor: Float, evidence: MutableMap<Index, Evidence>) {
        val distance = hypot(endpoint.x - origin.x, endpoint.z - origin.z)
        val steps = max(1, ceil(distance / CELL_SIZE).toInt())
        for (step in 1 until steps) {
            val t = step.toFloat() / steps
            val y = origin.y + (endpoint.y - origin.y) * t
            if (y - floor !in -FREE_BAND_BELOW..RAY_GROUND_BAND_ABOVE) continue
            val point = Point3(origin.x + (endpoint.x - origin.x) * t, y, origin.z + (endpoint.z - origin.z) * t)
            evidence.getOrPut(index(point)) { Evidence() }.free++
        }
    }

    private fun index(point: Point3) = Index(floor(point.x / CELL_SIZE).toInt(), floor(point.z / CELL_SIZE).toInt())
}

/** Keeps confirmed world cells for the lifetime of one ARCore coordinate system. */
class SessionGridMemory {
    private data class Index(val x: Int, val z: Int)
    private data class Stored(var state: TraversabilityState, var evidence: Int,
        var minHeight: Float?, var maxHeight: Float?, var freeConfirmations: Int = 0)
    private val cells = HashMap<Index, Stored>()

    @Synchronized
    fun update(observed: List<TraversabilityCell>, camera: CameraTransform): List<TraversabilityCell> {
        observed.filter { !it.inflated && it.worldX != null && it.worldZ != null }.forEach { cell ->
            val idx=index(cell.worldX!!,cell.worldZ!!)
            val old=cells[idx]
            if(old==null) {
                cells[idx]=Stored(cell.state,cell.evidence,cell.minHeight,cell.maxHeight)
            } else if(cell.state==TraversabilityState.OCCUPIED) {
                old.state=TraversabilityState.OCCUPIED
                old.evidence=max(old.evidence,cell.evidence)
                old.minHeight=cell.minHeight ?: old.minHeight
                old.maxHeight=cell.maxHeight ?: old.maxHeight
                old.freeConfirmations=0
            } else if(old.state==TraversabilityState.OCCUPIED) {
                old.freeConfirmations++
                if(old.freeConfirmations>=FREE_CONFIRMATIONS_TO_CLEAR) {
                    old.state=TraversabilityState.FREE
                    old.evidence=cell.evidence
                    old.minHeight=null; old.maxHeight=null; old.freeConfirmations=0
                }
            } else {
                old.evidence=max(old.evidence,cell.evidence)
            }
        }
        val occupied=cells.filterValues { it.state==TraversabilityState.OCCUPIED }.keys
        val inflation=ceil(.15f/TraversabilityGrid.CELL_SIZE).toInt()
        val blocked=occupied.toMutableSet()
        occupied.forEach { center -> for(dx in -inflation..inflation) for(dz in -inflation..inflation)
            if(dx*dx+dz*dz<=inflation*inflation) blocked+=Index(center.x+dx,center.z+dz) }
        return (cells.keys+blocked).mapNotNull { idx ->
            val worldX=(idx.x+.5f)*TraversabilityGrid.CELL_SIZE
            val worldZ=(idx.z+.5f)*TraversabilityGrid.CELL_SIZE
            val relative=camera.relative(Point3(worldX,camera.origin.y,worldZ)) ?: return@mapNotNull null
            if(hypot(relative.x,relative.z)>6f) return@mapNotNull null
            val stored=cells[idx]
            if(idx in blocked) TraversabilityCell(relative.x,relative.z,TraversabilityState.OCCUPIED,
                stored?.evidence ?: 2,stored?.minHeight,stored?.maxHeight,idx !in occupied,worldX,worldZ)
            else if(stored?.state==TraversabilityState.FREE) TraversabilityCell(relative.x,relative.z,
                TraversabilityState.FREE,stored.evidence,worldX=worldX,worldZ=worldZ)
            else null
        }
    }
    /**
     * Every confirmed cell this session holds, in world coordinates.
     *
     * Unlike [update] this is not clipped to the 6 m window around the camera, because a home map
     * has to keep everything the user walked past. Inflation is deliberately excluded: the stored
     * map keeps what was measured, and the live guidance layer adds its own margin when it reads
     * it back. Inflated cells stored as if measured would never clear again.
     */
    @Synchronized
    fun allCells(): List<TraversabilityCell> = cells.map { (idx, stored) ->
        TraversabilityCell(
            relativeX = 0f, relativeZ = 0f,
            state = stored.state, evidence = stored.evidence,
            minHeight = stored.minHeight, maxHeight = stored.maxHeight,
            inflated = false,
            worldX = (idx.x + .5f) * TraversabilityGrid.CELL_SIZE,
            worldZ = (idx.z + .5f) * TraversabilityGrid.CELL_SIZE,
        )
    }

    /** Confirmed cell count, used to report whether a session produced anything worth storing. */
    @Synchronized
    fun size(): Int = cells.size

    @Synchronized
    fun clear() = cells.clear()
    private fun index(x: Float,z: Float)=Index(floor(x/TraversabilityGrid.CELL_SIZE).toInt(),floor(z/TraversabilityGrid.CELL_SIZE).toInt())
    companion object { private const val FREE_CONFIRMATIONS_TO_CLEAR=6 }
}

object LocalNavigator {
    private data class Index(val x: Int,val z: Int)
    /** `lateral` keeps equal-cost paths close to the straight-ahead corridor. */
    private data class Step(val at: Index,val cost: Float,val lateral: Float,val order: Long)

    /**
     * Which side the last detour advice pointed to. Without it the planner can answer "left" and
     * then "right" for the very same obstacle on consecutive frames, which reads as the phone
     * contradicting itself.
     *
     * The preference is applied as a small per-step cost on the *opposite* side, and only while
     * a hazard is in front: a detour is exactly the situation where both sides cost about the
     * same, so the tie has to be broken somewhere. It is deliberately not applied on a clear
     * corridor, and the penalty is far smaller than one diagonal step, so a clearly better side
     * still wins and geometry is never overruled by inertia.
     */
    @Volatile private var preferredSide = 0
    @Volatile private var straightStreak = 0
    @Volatile private var hazardPresent = false

    /** Forget the remembered detour side, e.g. when the ARCore session restarts. */
    @Synchronized fun reset() { preferredSide = 0; straightStreak = 0 }

    /** The side the last detour advice chose (-1 left, +1 right, 0 none). */
    @Synchronized fun preferredDetourSide(): Int = preferredSide
    fun advise(cells: List<TraversabilityCell>): LocalGuidance {
        val hazard=cells.filter { it.state==TraversabilityState.OCCUPIED && !it.inflated &&
                abs(it.relativeX)<HAZARD_LATERAL && it.relativeZ in GOAL_FORWARD..HAZARD_NEAR }
            .minByOrNull { hypot(it.relativeX,it.relativeZ) }
        hazardPresent = hazard != null
        val free=cells.filter { it.state==TraversabilityState.FREE && it.worldX!=null && it.worldZ!=null }
        val start=free.filter { hypot(it.relativeX,it.relativeZ)<=FOOT_RADIUS }.minByOrNull { hypot(it.relativeX,it.relativeZ) }
            ?: return LocalGuidance(if(hazard!=null) "请先停下，脚下地图未知，请向下扫描脚前方和左右两侧"
                else "脚下地图未知，请停下，将手机稍微向下扫描脚前方",false)
        val byIndex=free.associateBy { index(it.worldX!!,it.worldZ!!) }
        val startIndex=index(start.worldX!!,start.worldZ!!)
        val best=HashMap<Index,Float>()
        val parent=HashMap<Index,Index>()
        val queue=PriorityQueue<Step>(compareBy<Step>{it.cost}.thenBy{it.lateral}.thenBy{it.order})
        var order=0L
        queue+=Step(startIndex,0f,abs(start.relativeX),order++)
        best[startIndex]=0f
        var reached: Index?=null
        while(queue.isNotEmpty()) {
            val step=queue.poll()
            if(step.cost>(best[step.at] ?: Float.MAX_VALUE)+EPSILON) continue
            val cell=byIndex[step.at] ?: continue
            if(cell.relativeZ<=GOAL_FORWARD) { reached=step.at; break }
            // Diagonal steps cost more than straight ones (LOCAL_NAVIGATION_PLAN), so a
            // clear corridor stays straight instead of drifting sideways one cell per step.
            for((dx,dz) in NEIGHBORS) {
                val next=Index(step.at.x+dx,step.at.z+dz)
                val nextCell=byIndex[next] ?: continue
                // Never squeeze diagonally between two cells that are not known free.
                if(dx!=0 && dz!=0 && !byIndex.containsKey(Index(step.at.x+dx,step.at.z)) &&
                    !byIndex.containsKey(Index(step.at.x,step.at.z+dz))) continue
                val candidate=step.cost+if(dx!=0 && dz!=0) DIAGONAL_COST else STRAIGHT_COST
                    +hysteresisCost(dx)
                if(candidate>=(best[next] ?: Float.MAX_VALUE)) continue
                best[next]=candidate; parent[next]=step.at
                queue+=Step(next,candidate,abs(nextCell.relativeX),order++)
            }
        }
        if(reached==null) return LocalGuidance(if(hazard!=null) "请先停下，当前没有确认到连续绕行区域，请扫描左右两侧"
            else "脚下到前方的地图不连续，请停下，将手机稍微向下补扫",false)
        val path=mutableListOf<Index>(); var cursor: Index?=reached
        while(cursor!=null) { path+=cursor; cursor=parent[cursor] }
        path.reverse()
        val pathCells=path.mapNotNull { byIndex[it] }
        // With a hazard the waypoint must sit past it. Looking a fixed distance ahead
        // would report "direction unclear" while there is still a metre to walk.
        val lookahead=if(hazard==null) LOOKAHEAD_METERS
            else max(LOOKAHEAD_METERS,hypot(hazard.relativeX,hazard.relativeZ)+HAZARD_MARGIN)
        val waypoint=lookaheadWaypoint(pathCells,lookahead)
        val text=when {
            hazard!=null && waypoint.relativeX<-LATERAL_TOLERANCE -> "请先停下，随后小步向左前方绕行，并继续扫描"
            hazard!=null && waypoint.relativeX>LATERAL_TOLERANCE -> "请先停下，随后小步向右前方绕行，并继续扫描"
            hazard!=null -> "请先停下，当前绕行方向仍不明确，请扫描左右两侧"
            waypoint.relativeX<-LATERAL_TOLERANCE -> "连续可走区域偏左，请小步向左前方移动，并继续扫描"
            waypoint.relativeX>LATERAL_TOLERANCE -> "连续可走区域偏右，请小步向右前方移动，并继续扫描"
            else -> "前方恢复连续候选可走区域，请继续小步向前并继续扫描"
        }
        rememberAdvice(waypoint)
        return LocalGuidance(text,hazard==null || abs(waypoint.relativeX)>LATERAL_TOLERANCE)
    }

    /**
     * Record which side was advised, and forget it once the way ahead has been straight for a
     * while: a stale preference would bias a completely different obstacle later on.
     */
    @Synchronized private fun rememberAdvice(waypoint: TraversabilityCell) {
        val side = when {
            waypoint.relativeX < -LATERAL_TOLERANCE -> -1
            waypoint.relativeX > LATERAL_TOLERANCE -> 1
            else -> 0
        }
        if (side == 0) {
            straightStreak++
            if (straightStreak >= HYSTERESIS_CLEAR_FRAMES) {
                preferredSide = 0
                straightStreak = 0
            }
        } else {
            preferredSide = side
            straightStreak = 0
        }
    }

    /**
     * Extra cost for a step that moves towards the side we did not advise last time.
     * Only active while a hazard is in front and only for lateral steps, so a straight corridor
     * and a genuinely one-sided detour are both unaffected.
     */
    private fun hysteresisCost(dx: Int): Float {
        if (hazardPresent == false || preferredSide == 0 || dx == 0) return 0f
        val stepSide = if (dx > 0) 1 else -1
        return if (stepSide != preferredSide) HYSTERESIS_STEP_PENALTY else 0f
    }

    /** The cell roughly `distance` ahead along the path, not the far end of it. */
    private fun lookaheadWaypoint(path: List<TraversabilityCell>, distance: Float): TraversabilityCell {
        var travelled=0f
        for(i in 1 until path.size) {
            travelled+=hypot(path[i].relativeX-path[i-1].relativeX,path[i].relativeZ-path[i-1].relativeZ)
            if(travelled>=distance) return path[i]
        }
        return path.last()
    }
    private fun index(x: Float,z: Float)=Index(floor(x/TraversabilityGrid.CELL_SIZE).toInt(),floor(z/TraversabilityGrid.CELL_SIZE).toInt())
    // Straight first, then diagonals, so equal-cost expansion explores forward before sideways.
    private val NEIGHBORS=listOf(0 to -1,-1 to -1,1 to -1,-1 to 0,1 to 0,0 to 1,-1 to 1,1 to 1)
    private const val STRAIGHT_COST=1f
    private const val DIAGONAL_COST=1.4f
    private const val LOOKAHEAD_METERS=.6f
    private const val HAZARD_MARGIN=.4f
    private const val LATERAL_TOLERANCE=.2f
    private const val FOOT_RADIUS=.4f
    private const val GOAL_FORWARD=-1.2f
    private const val HAZARD_NEAR=-.1f
    private const val HAZARD_LATERAL=.3f
    private const val EPSILON=1e-4f
    /** Small enough that geometry still decides, large enough to break an exact tie. */
    private const val HYSTERESIS_STEP_PENALTY=.02f
    /** How many straight-ahead advices in a row clear the remembered side. */
    private const val HYSTERESIS_CLEAR_FRAMES=5
}

object DepthGeometry {
    fun cameraPoint(u: Float, v: Float, depthMeters: Float, fx: Float, fy: Float, cx: Float, cy: Float): Point3? {
        if (!listOf(u, v, depthMeters, fx, fy, cx, cy).all { it.isFinite() } || fx <= 0 || fy <= 0 || depthMeters !in .25f..6f) return null
        return Point3((u-cx)*depthMeters/fx, -(v-cy)*depthMeters/fy, -depthMeters)
    }
}
