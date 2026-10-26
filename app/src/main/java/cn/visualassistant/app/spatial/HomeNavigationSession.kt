package cn.visualassistant.app.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the whole lifecycle of a "take me to the bathroom" request: which destination was asked
 * for, where the user says they are standing, what the route is, and what should be spoken next.
 *
 * The phone cannot recognise the room it is standing in, so the user states it. That is a real
 * limitation of a phone-only system and it is surfaced honestly here rather than papered over:
 * a mis-declared start produces a wrong route, which for a blind user is not a small error.
 * Mitigations are therefore deliberate: refuse ambiguous names, refuse weak alignment, and always
 * ask for confirmation the first time a destination is reached in a session.
 */
class HomeNavigationSession {

    data class Request(val placeId: String, val placeName: String)

    sealed class State {
        /** Nothing requested. */
        object Idle : State()

        /** A destination was named but the start has not been confirmed yet. */
        data class AwaitingStart(val request: Request, val question: String, val candidates: List<String>) : State()

        /** Start confirmed, no usable alignment yet. */
        data class NeedsAlignment(val request: Request, val reason: String) : State()

        /** Following a planned route. */
        data class Navigating(
            val request: Request,
            val legs: List<RouteLeg>,
            val totalMeters: Float,
            val legIndex: Int,
            val remainingMeters: Float,
            val headingKnown: Boolean,
            val crossesUnknown: Boolean,
            val widthMeters: Float,
        ) : State()

        /** Within arrival range of the destination. */
        data class Arriving(val request: Request, val distanceMeters: Float, val spokenOnce: Boolean) : State()

        /** Finished, or stopped on purpose. */
        data class Done(val request: Request, val outcome: String) : State()
    }

    var state: State = State.Idle
        private set

    private var alignment: HomeMapAlignment? = null
    /** Where the person was facing in map coordinates, null when the map's yaw is not known. */
    private var startHeadingRad: Float? = null
    private var route: RouteResult.Found? = null
    /** A bounded path length for explicitly supervised candidate-route trials. */
    private var maximumRouteMeters: Float? = null
    private var lastPosition: Pair<Float, Float>? = null
    private var lastPositionAt = 0L
    /** Closest straight-line distance to the destination seen so far, for deviation detection. */
    private var bestRemaining = Float.MAX_VALUE
    private var deviationStreak = 0
    private var lastInstructionKey: String? = null
    private var lastInstructionAt = 0L
    private var arrivalAnnounced = false

    /** The anchor the user declared as their start, and where it was in the live world frame. */
    private var firstAnchor: HomeMapAnchor? = null
    private var firstAnchorWorldPosition: Pair<Float, Float>? = null

    /**
     * Whether turn instructions may be spoken at all.
     *
     * This is stricter than "the map's yaw is known": it also requires knowing which way the person
     * is facing, because a turn is meaningless without a body to turn.
     */
    private val turnsAvailable: Boolean get() = startHeadingRad != null

    val activeRequest: Request? get() = when (val current = state) {
        is State.AwaitingStart -> current.request
        is State.NeedsAlignment -> current.request
        is State.Navigating -> current.request
        is State.Arriving -> current.request
        is State.Done -> current.request
        State.Idle -> null
    }

    fun reset() {
        state = State.Idle; alignment = null; route = null; startHeadingRad = null
        maximumRouteMeters = null
        lastPosition = null; deviationStreak = 0; bestRemaining = Float.MAX_VALUE
        clearPendingSpeech(); arrivalAnnounced = false
        firstAnchor = null; firstAnchorWorldPosition = null
    }

    /**
     * Step 1: the user asked for a destination.
     *
     * Several anchors matching one name is refused: guessing which "door" they meant is exactly
     * the kind of silent wrong turn this project must not produce.
     */
    fun requestDestination(map: HomeMap, rawQuery: String, now: Long): State {
        maximumRouteMeters=null
        if (map.cellCount == 0) {
            state = State.Done(Request("", rawQuery), "家地图还是空的，请先走一遍路线完成建图")
            return state
        }
        val matches = map.findAnchorByName(rawQuery)
        val request = when {
            matches.isEmpty() -> {
                val known = map.anchorList.joinToString("、") { it.name }
                state = State.Done(Request("", rawQuery),
                    "地图里没有标记「$rawQuery」。已标记的地点有：${known.ifEmpty { "暂无" }}")
                return state
            }
            matches.size > 1 -> {
                val names = matches.joinToString("、") { it.name }
                state = State.Done(Request("", rawQuery), "有多个地点叫「$rawQuery」，请说得更具体：$names")
                return state
            }
            else -> Request(matches[0].id, matches[0].name)
        }
        if (map.anchorList.size < 2) {
            state = State.AwaitingStart(request,
                "先说你现在在哪里",
                map.anchorList.map { it.name })
        } else {
            // Kept short on purpose. A long prompt with an example sentence is itself speech that the
            // open microphone can pick up: the user answers "我在" and the recorder hears the prompt
            // plus their reply, which arrives as unusable noise. The place names are spoken by
            // [reportNavigation] only when the user has clearly failed to answer.
            state = State.AwaitingStart(request,
                "你现在在哪里？",
                map.anchorList.map { it.name })
        }
        lastInstructionAt = now
        return state
    }

    /**
     * Starts navigation from a computer-verified pose that is already in the persistent map frame.
     * This is the normal production path after home relocalization.  It deliberately bypasses the
     * old spoken-start fallback: a user saying "I am in the bedroom" is not a position estimate.
     */
    fun startFromLocalizedPose(map: HomeMap, rawQuery: String, mapX: Float, mapZ: Float,
                               forwardX: Float, forwardZ: Float, now: Long,
                               maxRouteMeters: Float? = null): State {
        val requested = requestDestination(map, rawQuery, now)
        val request = (requested as? State.AwaitingStart)?.request ?: return requested
        maximumRouteMeters=maxRouteMeters?.takeIf{it.isFinite()&&it>0f}
        if (!mapX.isFinite() || !mapZ.isFinite() || !forwardX.isFinite() || !forwardZ.isFinite() ||
            hypot(forwardX, forwardZ) < 0.05f) {
            state = State.NeedsAlignment(request, "家庭定位没有返回可靠朝向，请重新观察门口或墙角")
            return state
        }
        // Caller has already converted the AR session pose into map coordinates. Identity here is
        // intentional: update() will continue receiving map-frame poses from the same transform.
        val identity = HomeMapAlignment(0f, 0f, 1f, 0f, headingKnown = true, residualMeters = 0f)
        alignment = identity
        startHeadingRad = atan2(forwardX, -forwardZ)
        firstAnchor = null; firstAnchorWorldPosition = null
        return planFrom(map, request, identity, mapX, mapZ, now, announceStart = true)
    }

    /**
     * The text a caller should speak after running a step, or null when there is nothing to say.
     *
     * Steps that only changed state carry their own question or reason; steps that planned a route
     * carry the instruction `speak` produced. Anything left over is cleared by
     * [clearPendingSpeech], so a refusal can never be followed by a stale "walk forward two metres".
     */
    fun speakable(): String? = when (val current = state) {
        State.Idle -> null
        is State.AwaitingStart -> current.question
        is State.NeedsAlignment -> current.reason
        is State.Done -> current.outcome
        is State.Navigating -> lastInstruction
        is State.Arriving -> lastInstruction
    }

    private fun clearPendingSpeech() { lastInstructionKey = null; lastInstruction = null }

    /**
     * Step 2 refused: the user named a place but no verified pose exists.
     *
     * ARCore loses tracking constantly indoors, so [startFromLocalizedPose] is sometimes
     * unavailable. It used to be tempting to treat the named room as the position and plan from
     * its saved anchor — and that temptation was acted on once, producing a route that started at
     * a point the person was not standing at, with no heading and therefore no turn ever spoken.
     * For a blind user that is the worst possible failure: confidently wrong, and undetectable.
     *
     * A room label is a semantic place, not evidence of where the body is. So this only ever
     * refuses, and names the one action that can fix it.
     */
    fun confirmStartAtAnchor(map: HomeMap, rawPlace: String, now: Long): State {
        val request = activeRequest ?: run {
            state = State.Idle; return state
        }
        clearPendingSpeech()
        route = null
        state = State.NeedsAlignment(request,
            "还没有可靠的实时家庭定位，不能仅凭口述房间开始导航。请先完成家庭自动定位；定位成功后再导航")
        return state
    }

    /**
     * Step 2: the user declared where they are.
     *
     * A single anchor yields translation only, and the state records that the heading is unknown
     * so guidance degrades to distance-only instead of inventing a left or right turn.
     */
    fun confirmStart(map: HomeMap, rawPlace: String, currentX: Float, currentZ: Float, now: Long,
                     forwardX: Float? = null, forwardZ: Float? = null): State {
        val request = activeRequest ?: run {
            state = State.Idle; return state
        }
        clearPendingSpeech()
        val matches = map.findAnchorByName(rawPlace)
        val anchor = when {
            matches.isEmpty() -> {
                state = State.NeedsAlignment(request, "地图里没有标记「$rawPlace」，请换一个已标记的地点")
                return state
            }
            matches.size > 1 -> {
                state = State.NeedsAlignment(request,
                    "有多个地点匹配「$rawPlace」，请说得更具体：${matches.joinToString("、") { it.name }}")
                return state
            }
            else -> matches[0]
        }
        val found = HomeMapRelocalizer.alignBySingleAnchor(map, anchor, currentX, currentZ)
            ?: run {
                state = State.NeedsAlignment(request, "无法用「${anchor.name}」确定位置，请重新确认起点")
                return state
            }
        alignment = found
        startHeadingRad = mapHeadingFor(found, forwardX, forwardZ)
        lastPosition = currentX to currentZ
        lastPositionAt = now
        // Map coordinates, like every other distance in this session: comparing a world position
        // against a stored anchor would report a distance that is off by the whole translation.
        bestRemaining = hypot(found.worldToMapX(currentX, currentZ) - anchor.x,
            found.worldToMapZ(currentX, currentZ) - anchor.z)
        deviationStreak = 0
        firstAnchor = anchor
        firstAnchorWorldPosition = currentX to currentZ
        return planFrom(map, request, found, currentX, currentZ, now, announceStart = true)
    }

    /**
     * Where the person faces, expressed in map coordinates.
     *
     * A single anchor fixes translation but not yaw, and without yaw a body-relative direction
     * cannot be moved into map space. Returning null there is the whole point: the planner then
     * suppresses turns rather than inventing them.
     */
    private fun mapHeadingFor(alignment: HomeMapAlignment, forwardX: Float?, forwardZ: Float?): Float? {
        if (!alignment.headingKnown || forwardX == null || forwardZ == null) return null
        if (forwardX == 0f && forwardZ == 0f) return null
        val mapX = alignment.directionToMapX(forwardX, forwardZ)
        val mapZ = alignment.directionToMapZ(forwardX, forwardZ)
        return atan2(mapX, -mapZ)
    }

    /**
     * Step 2b: the user reports having walked to a second known anchor.
     *
     * Two (map point, world point) pairs are exactly what a 2D rigid transform needs, so this is
     * the one supported way to upgrade an unknown heading into a known one. The pairs must be far
     * enough apart to define a direction; collinear or too-close anchors are refused instead of
     * yielding a confident wrong turn.
     */
    fun confirmSecondAnchor(map: HomeMap, rawPlace: String, currentX: Float, currentZ: Float, now: Long,
                            forwardX: Float? = null, forwardZ: Float? = null): State {
        val request = activeRequest ?: run { state = State.Idle; return state }
        clearPendingSpeech()
        val first = firstAnchor
        val firstHere = firstAnchorWorldPosition
        if (first == null || firstHere == null) {
            state = State.NeedsAlignment(request, "需要先确认第一个起点，才能用第二个标记点确定方向")
            return state
        }
        val second = map.findAnchorByName(rawPlace).singleOrNull()
        if (second == null) {
            state = State.NeedsAlignment(request, "没有找到唯一匹配的第二个标记点「$rawPlace」")
            return state
        }
        if (second.id == first.id) {
            state = State.NeedsAlignment(request, "第二个标记点需要和起点不同，才能确定方向")
            return state
        }
        val solved = HomeMapRelocalizer.alignByTwoAnchors(map, first, firstHere, second, currentX to currentZ)
        if (solved == null) {
            state = State.NeedsAlignment(request,
                "这两个标记点太近或接近一条直线，无法确定方向。请换一个距离更远的标记点")
            return state
        }
        alignment = solved
        startHeadingRad = mapHeadingFor(solved, forwardX, forwardZ)
        // The rigid fit maps both anchors exactly, so the destination's world position is now known.
        return planFrom(map, request, solved, currentX, currentZ, now, announceStart = true)
    }

    /** Plan (or replan) from the current position and build the first spoken instruction. */
    fun planFrom(map: HomeMap, request: Request, alignment: HomeMapAlignment,
                 currentX: Float, currentZ: Float, now: Long, announceStart: Boolean = false): State {
        clearPendingSpeech()
        // The map keeps its own frame so several walks can be merged into it; the live world
        // position is what has to move. Planning across two frames produces a route that looks
        // plausible and lands metres off, which a blind user cannot detect until they hit something.
        val startX = alignment.worldToMapX(currentX, currentZ)
        val startZ = alignment.worldToMapZ(currentX, currentZ)
        val anchor = map.anchor(request.placeId) ?: run {
            state = State.Done(request, "目标标记已不存在，请重新选择目的地"); return state
        }
        val planner = HomeRoutePlanner(map)
        val result = planner.plan(startX, startZ, anchor.x, anchor.z, startHeadingRad)
        when (result) {
            is RouteResult.Failed -> {
                route = null
                state = State.Done(request, result.detail)
            }
            is RouteResult.Found -> {
                val maxMeters=maximumRouteMeters
                if(maxMeters!=null&&result.totalMeters>maxMeters){
                    route=null
                    state=State.Done(request,"这条候选路线超过陪同短距离测试上限 ${formatHalfMeter(maxMeters)} 米，请选更近的标记点")
                    return state
                }
                route = result
                arrivalAnnounced = false
                val remaining = result.totalMeters
                state = State.Navigating(request, result.legs, result.totalMeters, 0, remaining,
                    turnsAvailable, result.crossesUnknown, result.minWidthMeters)
                lastPosition = currentX to currentZ
                lastPositionAt = now
                bestRemaining = result.totalMeters
                deviationStreak = 0
                if (announceStart) {
                    val prefix = if (turnsAvailable) "" else "目前只能确认距离，方向未确认。"
                    val unknownNote = if (result.crossesUnknown) "这段路有未确认的区域，请放慢。" else ""
                    speak(prefix + describeLeg(result.legs, 0, turnsAvailable) + unknownNote,
                        key = "nav-start", now = now)
                }
            }
        }
        return state
    }

    /**
     * Step 4: feed the live position in. Returns text to speak, or null when nothing should be said.
     *
     * This is also where stale-map conflicts surface. If the live grid sees an obstacle on a cell
     * the stored map calls free, the person is told furniture may have moved — the map is not
     * silently trusted over the sensors.
     */
    fun update(map: HomeMap, currentX: Float, currentZ: Float, now: Long,
               liveOccupied: List<Pair<Float, Float>> = emptyList()): String? {
        val current = state as? State.Navigating ?: return null
        val request = current.request
        val anchor = map.anchor(request.placeId) ?: run {
            state = State.Done(request, "目标标记已不存在"); return null
        }
        val alignment = alignment ?: return null
        // Everything below works in map coordinates, matching the frame the route was planned in.
        val hereX = alignment.worldToMapX(currentX, currentZ)
        val hereZ = alignment.worldToMapZ(currentX, currentZ)
        val distanceToGoal = hypot(hereX - anchor.x, hereZ - anchor.z)

        val planned = route
        // A live point that sits on ground the map has recorded as occupied for days is the same
        // wall seen again, not a new obstacle. Treating it as new withdrew the route every time
        // the person walked past a wall the map already knew.
        val freshObstacles = newObstacles(map, liveOccupied)
        val nearBodyObstacle = freshObstacles.any { (ox, oz) -> hypot(ox - hereX, oz - hereZ) <= NEAR_BODY_STOP_RADIUS_M }
        val routeUnsafe = planned != null && routeBlockedAhead(planned.worldPoints, hereX, hereZ, freshObstacles)
        if (nearBodyObstacle || routeUnsafe) {
            route = null
            state = State.Done(request, "当前位置或前方路线不再可靠，导航已停止，请停下确认")
            return speak(if (nearBodyObstacle) "身边近处检测到障碍，请立即停下确认；当前导航已停止"
                else "当前位置偏离路线，或前方路线出现障碍，请停下重新定位；当前导航已停止",
                key = "nav-route-blocked", now = now)
        }

        if (distanceToGoal <= ARRIVAL_RADIUS_M) {
            if (!arrivalAnnounced) {
                arrivalAnnounced = true
                state = State.Arriving(request, distanceToGoal, spokenOnce = true)
                // Never claim the destination is reached: the anchor is a marked position, and
                // confirming it is the only person who can tell a door from a wall.
                return speak("已到达「${request.placeName}」标记点附近，请确认这里是不是你要去的地方",
                    key = "nav-arrived", now = now)
            }
            // Keep prompting: falling silent here reads as "the phone stopped working" rather
            // than "you have arrived", so the prompt repeats until the user acts.
            return null
        }

        val previous = lastPosition
        val moved = previous?.let { hypot(currentX - it.first, currentZ - it.second) } ?: 0f
        if (moved < STUCK_DISTANCE_M && now - lastPositionAt >= STUCK_TIMEOUT_MS) {
            val spoken = speak("似乎一直没有前进，可能被挡住了，请确认前方或换个方向",
                key = "nav-stuck", now = now)
            lastPositionAt = now
            return spoken
        }
        if (moved >= STUCK_DISTANCE_M) lastPositionAt = now

        // Deviation is measured against the closest approach so far, not against the last reading.
        // Comparing consecutive readings only catches a single large step, so a person drifting
        // away in small steps would never be warned; accumulating against the best distance seen
        // catches slow drift while still tolerating normal wobble.
        if (distanceToGoal > bestRemaining + DEVIATION_DISTANCE_M) {
            deviationStreak++
            if (deviationStreak >= DEVIATION_STREAK) {
                val spoken = speak("好像走偏了，离目标反而更远了，请停下确认方向",
                    key = "nav-deviated", now = now)
                deviationStreak = 0
                lastPosition = currentX to currentZ
                bestRemaining = distanceToGoal
                return spoken
            }
        } else {
            deviationStreak = 0
            if (distanceToGoal < bestRemaining) bestRemaining = distanceToGoal
        }

        val remaining = distanceToGoal
        val legIndex = legIndexFor(current.legs, remaining, current.totalMeters, hereX, hereZ)
        val narrowed = state as State.Navigating
        state = narrowed.copy(legIndex = legIndex, remainingMeters = remaining)
        lastPosition = currentX to currentZ
        // `bestRemaining` is deliberately not refreshed here: it records the closest approach, and
        // overwriting it every update would make a slow drift away from the goal invisible.

        if (now - lastInstructionAt < INSTRUCTION_INTERVAL_MS) return null
        val spoken = if (remaining <= LEG_SAY_DISTANCE_M) {
            "前方 ${formatHalfMeter(remaining)} 米到达「${request.placeName}」标记点"
        } else {
            val text = describeLeg(current.legs, legIndex, turnsAvailable)
            val widthNote = if (current.widthMeters < HomeRoutePlanner.COMFORTABLE_WIDTH_METERS) "，这段比较窄" else ""
            text + widthNote
        }
        return speak(spoken, key = "nav-leg-$legIndex", now = now)
    }

    /**
     * Drop the live occupied points that only re-observe ground the map already calls occupied.
     *
     * The map is the record of the house as it was seen before; the live grid is what the camera
     * sees right now. A point that lands on a known wall is that wall, and only a point on ground
     * the map has never recorded as blocked can be something new in the way.
     */
    private fun newObstacles(map: HomeMap, occupied: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
        if (occupied.isEmpty()) return occupied
        val known = map.allCells().filter { it.occupied }
        if (known.isEmpty()) return occupied
        val radius = (STATIC_WALL_FILTER_M / map.cellSize).toInt().coerceAtLeast(1)
        return occupied.filter { (ox, oz) ->
            val cx = map.indexOf(ox)
            val cz = map.indexOf(oz)
            known.none { cell ->
                kotlin.math.abs(cell.indexX - cx) <= radius && kotlin.math.abs(cell.indexZ - cz) <= radius
            }
        }
    }

    /** Mark the current position as the origin for a fresh plan, e.g. after the user is nudged. */
    fun replan(map: HomeMap, now: Long): State {
        val request = activeRequest ?: run { state = State.Idle; return state }
        val alignment = alignment ?: run { state = State.NeedsAlignment(request, "位置尚未确认"); return state }
        val current = lastPosition ?: run { state = State.NeedsAlignment(request, "位置尚未确认"); return state }
        val previous = route
        // Try the new route before touching the session. If the corridor is unchanged, the
        // current instruction still stands and repeating it would only make the phone talk over
        // itself; planFrom() clears the pending speech, so it must not run in that case.
        val anchor = map.anchor(request.placeId)
        if (previous != null && anchor != null) {
            val startX = alignment.worldToMapX(current.first, current.second)
            val startZ = alignment.worldToMapZ(current.first, current.second)
            val probe = HomeRoutePlanner(map).plan(startX, startZ, anchor.x, anchor.z, startHeadingRad)
            if (probe is RouteResult.Found && pathsEquivalent(previous.worldPoints, probe.worldPoints)) {
                return state
            }
        }
        return planFrom(map, request, alignment, current.first, current.second, now, announceStart = true)
    }

    /**
     * Whether two routes are the same corridor, measured as the average distance from each point
     * of one to the nearest point of the other, in both directions.
     */
    private fun pathsEquivalent(a: List<Pair<Float, Float>>, b: List<Pair<Float, Float>>): Boolean {
        if (a.size < 2 || b.size < 2) return false
        var forward = 0f
        for (p in a) {
            var best = Float.MAX_VALUE
            for (q in b) {
                val d = hypot(p.first - q.first, p.second - q.second)
                if (d < best) best = d
            }
            forward += best
        }
        var backward = 0f
        for (p in b) {
            var best = Float.MAX_VALUE
            for (q in a) {
                val d = hypot(p.first - q.first, p.second - q.second)
                if (d < best) best = d
            }
            backward += best
        }
        val average = (forward + backward) / (a.size + b.size)
        return average < PATH_EQUIVALENT_M
    }

    fun cancel(outcome: String = "已停止导航"): State {
        val request = activeRequest
        route = null
        maximumRouteMeters=null
        state = if (request == null) State.Idle else State.Done(request, outcome)
        return state
    }

    fun arriveNow(): State {
        val request = activeRequest ?: return state
        arrivalAnnounced = true
        state = State.Arriving(request, 0f, spokenOnce = true)
        return state
    }

    // ---- internals ----

    private var lastInstruction: String? = null

    /**
     * Records an instruction and returns it only when it is newly spoken.
     *
     * Keeping "was this actually said" separate from "is there something to say" matters: a caller
     * that only checked the cached text would swallow the very first arrival prompt, because the
     * duplicate-suppression window has only just been opened.
     */
    private fun speak(text: String, key: String, now: Long): String? {
        if (lastInstructionKey == key && now - lastInstructionAt < REPEAT_SUPPRESS_MS) return null
        lastInstructionKey = key; lastInstructionAt = now; lastInstruction = text
        return text
    }

    private fun describeLeg(legs: List<RouteLeg>, index: Int, headingKnown: Boolean): String {
        if (legs.isEmpty()) return "前方没有可确认的路线"
        val leg = legs.getOrNull(index) ?: legs.last()
        val distance = formatHalfMeter(leg.distanceMeters)
        val turn = when {
            !headingKnown -> ""
            leg.turnDegrees > TURN_SAY_THRESHOLD -> "，左转约 ${Math.abs(leg.turnDegrees)} 度"
            leg.turnDegrees < -TURN_SAY_THRESHOLD -> "，右转约 ${Math.abs(leg.turnDegrees)} 度"
            else -> "，继续直行"
        }
        val narrow = if (leg.narrow) "，注意此处较窄" else ""
        return "向前 $distance 米$turn$narrow"
    }

    /**
     * Which spoken leg the person is currently in, derived from distance walked rather than from
     * cell positions: the live grid is local and cannot see the whole route.
     */
    private fun legIndexFor(legs: List<RouteLeg>, remaining: Float, total: Float, x: Float, z: Float): Int {
        if (legs.isEmpty() || total <= 0f) return 0
        val travelled = total - remaining
        var cumulative = 0f
        for (index in legs.indices) {
            cumulative += legs[index].distanceMeters
            if (cumulative > travelled) return index
        }
        return legs.size - 1
    }

    /** Check observed occupied cells against the next short section of the planned polyline. */
    private fun routeBlockedAhead(points: List<Pair<Float, Float>>, x: Float, z: Float,
                                  occupied: List<Pair<Float, Float>>): Boolean {
        if (points.size < 2) return false
        data class Projection(val along: Float, val lateral: Float)
        fun project(px: Float, pz: Float, ax: Float, az: Float, bx: Float, bz: Float): Projection {
            val dx = bx - ax; val dz = bz - az
            val length2 = dx * dx + dz * dz
            val t = if (length2 <= 1e-6f) 0f else (((px - ax) * dx + (pz - az) * dz) / length2).coerceIn(0f, 1f)
            val qx = ax + t * dx; val qz = az + t * dz
            return Projection(t * kotlin.math.sqrt(length2), hypot(px - qx, pz - qz))
        }
        val lengths = (0 until points.lastIndex).map { i ->
            hypot(points[i + 1].first - points[i].first, points[i + 1].second - points[i].second)
        }
        val userProjections = lengths.indices.map { i ->
            val p = project(x, z, points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
            i to p
        }
        val nearest = userProjections.minByOrNull { it.second.lateral } ?: return false
        // If the tracked pose is no longer close to the saved route, do not keep speaking its
        // next turn. The caller will stop and require relocalization.
        if (nearest.second.lateral > MAX_USER_ROUTE_DEVIATION_M) return true
        val userAlong = lengths.take(nearest.first).sum() + nearest.second.along
        for ((ox, oz) in occupied) {
            var segmentStart = 0f
            for (i in lengths.indices) {
                val p = project(ox, oz, points[i].first, points[i].second, points[i + 1].first, points[i + 1].second)
                val ahead = segmentStart + p.along - userAlong
                if (ahead in MIN_OBSTACLE_LOOKAHEAD_M..MAX_OBSTACLE_LOOKAHEAD_M &&
                    p.lateral <= USER_ROUTE_CORRIDOR_M) return true
                segmentStart += lengths[i]
            }
        }
        return false
    }

    private fun formatHalfMeter(value: Float): String = "${(value * 2).roundToInt() / 2f}"

    companion object {
        const val ARRIVAL_RADIUS_M = 0.8f
        private const val STUCK_DISTANCE_M = 0.3f
        private const val STUCK_TIMEOUT_MS = 30_000L
        private const val DEVIATION_DISTANCE_M = 0.5f
        private const val DEVIATION_STREAK = 3
        private const val USER_ROUTE_CORRIDOR_M = 0.35f
        private const val NEAR_BODY_STOP_RADIUS_M = 0.45f
        private const val MAX_USER_ROUTE_DEVIATION_M = 0.55f
        private const val MIN_OBSTACLE_LOOKAHEAD_M = 0.15f
        private const val MAX_OBSTACLE_LOOKAHEAD_M = 1.8f
        private const val INSTRUCTION_INTERVAL_MS = 6_000L
        private const val LEG_SAY_DISTANCE_M = 1.5f
        private const val REPEAT_SUPPRESS_MS = 4_000L
        private const val TURN_SAY_THRESHOLD = 20
        /** A live point this close to a wall the map already knows is that wall, not a new obstacle. */
        private const val STATIC_WALL_FILTER_M = 0.18f
        /** Two routes closer than this on average are the same corridor. */
        private const val PATH_EQUIVALENT_M = 0.16f
    }
}
