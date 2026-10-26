package cn.visualassistant.app.spatial

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Why a destination could not be reached. The three causes need different user actions. */
enum class RouteFailure {
    /** No start cell: the user's feet are on ground nobody has ever confirmed free. */
    NO_START,

    /** The goal area was never observed, so the map cannot say anything about it. */
    GOAL_UNKNOWN,

    /** A wall or obstacle completely separates start from goal. */
    BLOCKED,

    /** A path exists but every route is narrower than a person. */
    TOO_NARROW,

    /** Free cells exist but never connect; the gap is unobserved ground. */
    DISCONNECTED,
}

data class RouteLeg(
    val distanceMeters: Float,
    val turnDegrees: Int,
    val narrow: Boolean,
    val widthMeters: Float,
)

sealed class RouteResult {
    data class Found(
        val worldPoints: List<Pair<Float, Float>>,
        val totalMeters: Float,
        val minWidthMeters: Float,
        val legs: List<RouteLeg>,
        val crossesUnknown: Boolean,
    ) : RouteResult()

    data class Failed(val reason: RouteFailure, val detail: String) : RouteResult()
}

/**
 * Global path planning over a persisted [HomeMap].
 *
 * The planner only produces a *candidate* route on remembered ground. It never certifies safety:
 * the live session grid stays authoritative and may contradict any part of this path at any
 * moment. Cost weights therefore push the route away from unknown and narrow ground rather than
 * pretending those cells are fine.
 */
class HomeRoutePlanner(private val map: HomeMap) {

    /**
     * Plan a route inside the map's own coordinate frame.
     *
     * `startHeadingRad` is where the person is actually facing, expressed in map coordinates using
     * the same convention as [headingOf]. It is nullable on purpose: without it there is nothing
     * tying a map direction to the person's body, so no turn may be spoken — not even "straight
     * ahead", which would be an assumption dressed up as an instruction.
     */
    fun plan(startX: Float, startZ: Float, goalX: Float, goalZ: Float,
             startHeadingRad: Float? = null): RouteResult {
        if (map.cellCount == 0) return RouteResult.Failed(RouteFailure.DISCONNECTED, "家地图还是空的")

        val requestedGoal = Index(map.indexOf(goalX), map.indexOf(goalZ))
        val requestedGoalCell=map.cellAtIndex(requestedGoal.x,requestedGoal.z)
        if(requestedGoalCell?.occupied==true)
            return RouteResult.Failed(RouteFailure.BLOCKED,"目标位置目前记录为障碍，可能是家具挡住了，请到现场确认")
        val goalIndex = bestGoalNear(goalX, goalZ)
        if (goalIndex == null) {
            val goalCell = map.cellAtIndex(requestedGoal.x, requestedGoal.z)
            val reason = if (goalCell == null) RouteFailure.GOAL_UNKNOWN
            else if (goalCell.occupied) RouteFailure.BLOCKED
            else RouteFailure.DISCONNECTED
            return RouteResult.Failed(reason, describeGoal(reason, goalCell))
        }

        val startIndex = bestStartNear(startX, startZ)
            ?: return RouteResult.Failed(RouteFailure.NO_START, "脚下地图未知，请先扫描脚前方和左右两侧")

        val search = search(startIndex, goalIndex)
            ?: return RouteResult.Failed(classifyFailure(startIndex, goalIndex), "没有找到从当前位置到目标的连续可走区域")

        val pathIndices = search
        val totalMeters = polylineLength(pathIndices.map { Pt((it.x + 0.5f) * map.cellSize, (it.z + 0.5f) * map.cellSize) })
        // A semantic anchor can sit beside a bed or wall even though its arrival zone is open.
        // Judge passage width on the travelled corridor, excluding only the first/last 30 cm;
        // doorways in the middle remain fully checked.
        val endpointMarginCells=(.30f/map.cellSize).roundToInt().coerceAtLeast(1)
        val widthPath=if(pathIndices.size>endpointMarginCells*2)
            pathIndices.subList(endpointMarginCells,pathIndices.size-endpointMarginCells) else pathIndices
        val widths = widthPath.map { freeWidthAtIndex(it) }
        val minWidth = widths.minOrNull() ?: 0f
        if (minWidth < MIN_WIDTH_METERS) {
            return RouteResult.Failed(RouteFailure.TOO_NARROW,
                "这条路最窄处只有 ${formatHalfMeter(minWidth)} 米，宽于身位才能通过，请换一个标记点或补扫门框两侧")
        }

        // The raw cell path steps in 10 cm squares, so its corners are grid artefacts rather than
        // real turns. Speaking them as "left 90 degrees" sends the person into a wall they could
        // have walked past. Pull the path straight and round its corners, but only keep a smoothed
        // version that is still known free everywhere.
        val rawPoints = pathIndices.map { Pt((it.x + 0.5f) * map.cellSize, (it.z + 0.5f) * map.cellSize) }
        val points = smoothPath(rawPoints)
        val crossesUnknown = points.any { map.cellAtIndex(map.indexOf(it.x), map.indexOf(it.z)) == null }
        return RouteResult.Found(
            worldPoints = points.map { it.x to it.z },
            totalMeters = polylineLength(points),
            minWidthMeters = minWidth,
            legs = buildLegs(points, startHeadingRad),
            crossesUnknown = crossesUnknown,
        )
    }

    // ---- search ----

    private data class Index(val x: Int, val z: Int)
    private data class Step(val at: Index, val cost: Float, val order: Long)

    private fun search(start: Index, goal: Index): List<Index>? {
        val best = HashMap<Index, Float>()
        val parent = HashMap<Index, Index>()
        val queue = PriorityQueue<Step>(compareBy<Step> { it.cost }.thenBy { it.order })
        var order = 0L
        best[start] = 0f
        queue += Step(start, 0f, order++)
        var found: Index? = null
        while (queue.isNotEmpty()) {
            val step = queue.poll() ?: break
            if (step.cost > (best[step.at] ?: Float.MAX_VALUE) + EPSILON) continue
            if (step.at == goal) { found = step.at; break }
            for ((dx, dz) in NEIGHBORS) {
                val next = Index(step.at.x + dx, step.at.z + dz)
                val cell = map.cellAtIndex(next.x, next.z) ?: continue
                if (cell.occupied) continue
                if (dx != 0 && dz != 0) {
                    // Do not squeeze diagonally between two cells that are not both known free.
                    val a = map.cellAtIndex(step.at.x + dx, step.at.z)
                    val b = map.cellAtIndex(step.at.x, step.at.z + dz)
                    if (a == null || a.occupied || b == null || b.occupied) continue
                }
                val width = freeWidthAtIndex(next)
                // Narrow ground is usable but expensive, so routes prefer the middle of a space.
                val widthPenalty = if (width < COMFORTABLE_WIDTH_METERS) (COMFORTABLE_WIDTH_METERS - width) * NARROW_PENALTY else 0f
                val candidate = step.cost + (if (dx != 0 && dz != 0) DIAGONAL_COST else STRAIGHT_COST) + widthPenalty
                if (candidate >= (best[next] ?: Float.MAX_VALUE)) continue
                best[next] = candidate
                parent[next] = step.at
                queue += Step(next, candidate, order++)
            }
        }
        if (found == null) return null
        val path = ArrayList<Index>()
        var cursor: Index? = found
        while (cursor != null) { path += cursor; cursor = parent[cursor] }
        path.reverse()
        return path
    }

    /** The nearest confirmed-free cell to a point, searched outward. */
    private fun bestStartNear(worldX: Float, worldZ: Float): Index? {
        val centreX = map.indexOf(worldX)
        val centreZ = map.indexOf(worldZ)
        for (ring in 0..MAX_START_RING) {
            var best: Index? = null
            var bestDistance = Float.MAX_VALUE
            for (dx in -ring..ring) for (dz in -ring..ring) {
                if (maxOf(abs(dx), abs(dz)) != ring) continue
                val candidate = Index(centreX + dx, centreZ + dz)
                val cell = map.cellAtIndex(candidate.x, candidate.z) ?: continue
                if (cell.occupied) continue
                val distance = hypot((candidate.x + 0.5f) * map.cellSize - worldX, (candidate.z + 0.5f) * map.cellSize - worldZ)
                if (distance < bestDistance && distance <= MAX_START_DISTANCE_M) { best = candidate; bestDistance = distance }
            }
            if (best != null) return best
        }
        return null
    }

    /** Pick a safe arrival cell near the user's semantic anchor, not blindly its quantised cell. */
    private fun bestGoalNear(worldX:Float,worldZ:Float):Index?{
        val centreX=map.indexOf(worldX);val centreZ=map.indexOf(worldZ)
        var best:Index?=null;var bestScore=Float.NEGATIVE_INFINITY
        for(dx in -GOAL_SEARCH_RING..GOAL_SEARCH_RING)for(dz in -GOAL_SEARCH_RING..GOAL_SEARCH_RING){
            val candidate=Index(centreX+dx,centreZ+dz)
            val cell=map.cellAtIndex(candidate.x,candidate.z)?:continue
            if(cell.occupied)continue
            val distance=hypot((candidate.x+.5f)*map.cellSize-worldX,(candidate.z+.5f)*map.cellSize-worldZ)
            if(distance>MAX_GOAL_DISTANCE_M)continue
            val score=freeWidthAtIndex(candidate)-distance*.35f
            if(score>bestScore){best=candidate;bestScore=score}
        }
        return best
    }

    private fun classifyFailure(start: Index, goal: Index): RouteFailure {
        val reachable = HashSet<Index>()
        val queue = ArrayDeque<Index>(); queue.add(start); reachable.add(start)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for ((dx, dz) in NEIGHBORS) {
                val next = Index(current.x + dx, current.z + dz)
                if (!reachable.add(next)) continue
                val cell = map.cellAtIndex(next.x, next.z) ?: continue
                if (cell.occupied) continue
                queue.add(next)
            }
        }
        // Anything free but not reachable means the two areas are separated by unobserved ground.
        val freeTotal = map.allCells().count { !it.occupied }
        return if (reachable.size < freeTotal) RouteFailure.DISCONNECTED else RouteFailure.BLOCKED
    }

    private fun describeGoal(reason: RouteFailure, cell: HomeMapCell?): String = when (reason) {
        RouteFailure.GOAL_UNKNOWN -> "目标位置还没有被扫描过，请先扫描到那里并标记"
        RouteFailure.BLOCKED -> "目标位置目前记录为障碍，可能是家具挡住了，请到现场确认"
        else -> "目标位置周围没有确认过的可走区域"
    }

    // ---- geometry helpers ----

    /** A point on the floor, in map coordinates. */
    private data class Pt(val x: Float, val z: Float)

    /**
     * Round the corners of the grid path.
     *
     * The number of points is kept the same on purpose: downstream code and existing callers
     * reason about the path as a sequence of cells, and doubling it would change what "how many
     * points lie inside that wall" means. Corner rounding therefore moves each corner towards the
     * chord of its neighbours instead of inserting two new points per corner.
     *
     * The path is deliberately *not* straightened into a chord. A straight pull would replace the
     * opening direction of the route with the direction from the start to the goal, which is a
     * different thing: the person is told how far they are facing away from the route, and that
     * number would silently change. Straight segments produce no spoken turn anyway, so pulling
     * them buys nothing.
     */
    private fun smoothPath(raw: List<Pt>): List<Pt> {
        if (raw.size < 3) return raw
        var best = raw
        var current = raw
        for (iteration in 0 until SMOOTH_ITERATIONS) {
            val next = roundCorners(current)
            if (!pathIsFree(next)) break
            // Smoothing must never talk the user into a detour that is longer than the grid path.
            if (polylineLength(next) > polylineLength(best) * (1f + MAX_LENGTH_GROWTH)) break
            current = next
            best = next
        }
        return best
    }

    /** Moves every interior corner towards the chord of its neighbours, keeping the point count. */
    private fun roundCorners(points: List<Pt>): List<Pt> {
        if (points.size < 3) return points
        val out = ArrayList<Pt>(points.size)
        out += points.first()
        for (i in 1 until points.size - 1) {
            val a = points[i - 1]; val b = points[i]; val c = points[i + 1]
            out += Pt(
                b.x + (a.x + c.x - 2f * b.x) * CORNER_CUT,
                b.z + (a.z + c.z - 2f * b.z) * CORNER_CUT,
            )
        }
        out += points.last()
        return out
    }

    /** Every sampled point of the polyline must sit on a cell that is known free. */
    private fun pathIsFree(points: List<Pt>): Boolean {
        for (i in 1 until points.size) {
            if (!segmentIsFree(points[i - 1], points[i])) return false
        }
        return true
    }

    /**
     * A segment is free when every sample along it lands on a confirmed free cell. Unknown cells
     * count as not free: smoothing must never turn "nobody looked there" into "walkable".
     */
    private fun segmentIsFree(a: Pt, b: Pt): Boolean {
        val distance = hypot(b.x - a.x, b.z - a.z)
        val steps = maxOf(1, (distance / (map.cellSize / 3f)).toInt())
        for (step in 0..steps) {
            val t = step.toFloat() / steps
            val x = a.x + (b.x - a.x) * t
            val z = a.z + (b.z - a.z) * t
            val cell = map.cellAtIndex(map.indexOf(x), map.indexOf(z)) ?: return false
            if (cell.occupied) return false
        }
        return true
    }

    private fun polylineLength(points: List<Pt>): Float {
        var total = 0f
        for (i in 1 until points.size) {
            total += hypot(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
        }
        return total
    }

    private fun freeWidthAtIndex(index: Index): Float {
        // Width is orientation independent: twice the distance to the nearest occupied/unknown
        // cell.  The old implementation only looked left/right on the X axis, so a perfectly wide
        // north-south corridor was reported as a narrow slot.
        var nearest=Float.POSITIVE_INFINITY
        for(dx in -MAX_WIDTH_CELLS..MAX_WIDTH_CELLS)for(dz in -MAX_WIDTH_CELLS..MAX_WIDTH_CELLS){
            if(dx==0&&dz==0)continue
            val cell=map.cellAtIndex(index.x+dx,index.z+dz)
            if(cell==null||cell.occupied){
                val distance=hypot(dx.toFloat(),dz.toFloat())*map.cellSize
                if(distance<nearest)nearest=distance
            }
        }
        if(!nearest.isFinite())return (MAX_WIDTH_CELLS*2+1)*map.cellSize
        return (2f*nearest-map.cellSize).coerceAtLeast(map.cellSize)
    }

    private fun freeRadius(index: Index): Int {
        var radius = 0
        for (dx in -GOAL_SEARCH_RING..GOAL_SEARCH_RING) for (dz in -GOAL_SEARCH_RING..GOAL_SEARCH_RING) {
            val cell = map.cellAtIndex(index.x + dx, index.z + dz)
            if (cell != null && !cell.occupied) radius++
        }
        return radius
    }

    private fun pathLength(path: List<Index>): Float {
        var total = 0f
        for (i in 1 until path.size) {
            total += hypot((path[i].x - path[i - 1].x).toFloat(), (path[i].z - path[i - 1].z).toFloat()) * map.cellSize
        }
        return total
    }

    /**
     * Compress the path into spoken legs.
     *
     * Turns are angles between successive legs, which a rotation preserves, so the choice of frame
     * does not matter for them — but the *first* leg is different. It is measured against where
     * the person is standing and facing, and that can only be known if [startHeadingRad] was
     * supplied. Guessing "straight ahead" from the map's own axes is how a blind user walks into
     * a wall, so a null heading suppresses turns for the whole route rather than just the first.
     *
     * Turns accumulate instead of being spoken one by one. A grid path that weaves by a few
     * degrees per step would otherwise produce "left 20, right 20, left 20" and the person would
     * never know which way to actually go.
     */
    private fun buildLegs(points: List<Pt>, startHeadingRad: Float?): List<RouteLeg> {
        if (points.size < 2) return emptyList()
        // A null heading means no map direction can be tied to the person's body, so nothing may
        // be said about turning. Feeding the first leg's own heading in instead would fake a
        // confident "straight ahead" out of nothing.
        val headingKnown = startHeadingRad != null
        val startHeading = startHeadingRad ?: 0f

        val legs = ArrayList<RouteLeg>()
        var legStart = 0
        var lastLegStart = 0
        // The direction of the leg that is currently being measured. Turns accumulate against
        // it, so a leg's spoken turn is the angle from the previous leg's direction to this one.
        var legHeading = headingOf(points[1].x - points[0].x, points[1].z - points[0].z)
        var openingTurn = turnDegrees(startHeading, legHeading)
        // The turn of the leg that is currently being measured. It is fixed when the previous
        // leg ended, because only then is the direction of this leg known.
        var nextTurn = 0
        var netTurn = 0f
        for (i in 2 until points.size) {
            val stepHeading = headingOf(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
            // Measured against the direction of the leg being built, not against the previous
            // step: that makes this the leg's turn, not an increment to be summed.
            netTurn = turnDegrees(legHeading, stepHeading).toFloat()
            if (abs(netTurn) < TURN_THRESHOLD_DEGREES) continue
            if (i - 1 <= 1) {
                // The turn is right at the start, so there is no "walk straight first" to speak of.
                // Let the opening leg carry it instead of announcing "walk ten centimetres, turn".
                openingTurn = turnDegrees(startHeading, stepHeading)
                netTurn = 0f
                continue
            }
            // A grid path often weaves: it turns one way, then straight back. Two opposite turns
            // of similar size describe one wobble, not two decisions, so they are spoken as one.
            // A large turn is a real corner and stays separate.
            val spoken = if (legs.isEmpty()) openingTurn else nextTurn
            val previous = legs.lastOrNull()
            if (previous != null && previous.turnDegrees != 0 && spoken != 0 &&
                sign(spoken.toFloat()) != sign(previous.turnDegrees.toFloat()) &&
                abs(previous.turnDegrees) <= SAW_TOOTH_DEGREES) {
                legs[legs.lastIndex] = makeLeg(points, lastLegStart, i - 1,
                    previous.turnDegrees + spoken, headingKnown)
            } else {
                legs += makeLeg(points, legStart, i - 1, spoken, headingKnown)
                lastLegStart = legStart
            }
            legStart = i - 1
            legHeading = stepHeading
            nextTurn = netTurn.toInt()
            netTurn = 0f
        }
        // The closing leg gets the same wobble treatment as the others.
        val spoken = if (legs.isEmpty()) openingTurn else nextTurn
        val previous = legs.lastOrNull()
        if (previous != null && previous.turnDegrees != 0 && spoken != 0 &&
            sign(spoken.toFloat()) != sign(previous.turnDegrees.toFloat()) &&
            abs(previous.turnDegrees) <= SAW_TOOTH_DEGREES) {
            legs[legs.lastIndex] = makeLeg(points, lastLegStart, points.size - 1,
                previous.turnDegrees + spoken, headingKnown)
        } else {
            legs += makeLeg(points, legStart, points.size - 1, spoken, headingKnown)
        }
        return legs
    }

    private fun makeLeg(points: List<Pt>, from: Int, to: Int, turn: Int, headingKnown: Boolean): RouteLeg {
        var distance = 0f
        var minWidth = Float.MAX_VALUE
        for (i in from + 1..to) {
            distance += hypot(points[i].x - points[i - 1].x, points[i].z - points[i - 1].z)
            val index = Index(map.indexOf(points[i].x), map.indexOf(points[i].z))
            minWidth = minOf(minWidth, freeWidthAtIndex(index))
        }
        return RouteLeg(distance, if (headingKnown) turn else 0,
            minWidth < COMFORTABLE_WIDTH_METERS,
            if (minWidth == Float.MAX_VALUE) 0f else minWidth)
    }

    private fun headingOf(dx: Float, dz: Float): Float = atan2(dx, -dz)

    private fun turnDegrees(from: Float, to: Float): Int {
        var delta = Math.toDegrees((to - from).toDouble()).toFloat()
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        return delta.roundToInt()
    }

    private fun sign(value: Float): Int = when {
        value > 0f -> 1
        value < 0f -> -1
        else -> 0
    }

    private fun formatHalfMeter(value: Float): String =
        "${(value * 2).roundToInt() / 2f}"

    companion object {
        private val NEIGHBORS = listOf(0 to -1, -1 to -1, 1 to -1, -1 to 0, 1 to 0, 0 to 1, -1 to 1, 1 to 1)
        private const val STRAIGHT_COST = 1f
        private const val DIAGONAL_COST = 1.4f
        private const val EPSILON = 1e-4f
        private const val MAX_START_RING = 6
        private const val MAX_START_DISTANCE_M = 0.8f
        private const val GOAL_SEARCH_RING = 8
        private const val MAX_GOAL_DISTANCE_M = 0.8f
        private const val MAX_WIDTH_CELLS = 8
        /** Anything below this is a squeeze rather than a walkway. */
        const val MIN_WIDTH_METERS = 0.45f
        const val COMFORTABLE_WIDTH_METERS = 0.9f
        private const val NARROW_PENALTY = 1.2f
        private const val TURN_THRESHOLD_DEGREES = 25f
        /** Two opposite turns of at most this size are one wobble, not two decisions. */
        private const val SAW_TOOTH_DEGREES = 35
        /** How many corner-rounding passes are applied to the grid path. */
        private const val SMOOTH_ITERATIONS = 2
        /** How far a corner moves towards the chord of its neighbours. */
        private const val CORNER_CUT = 0.25f
        /** Smoothing may not lengthen the route by more than this fraction. */
        private const val MAX_LENGTH_GROWTH = 0.15f
    }
}
