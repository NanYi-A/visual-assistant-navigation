package cn.visualassistant.app.risk

import cn.visualassistant.app.core.Detection

/** Image occupancy suggests where to LOOK, not a verified walkable route or distance. */
object AvoidanceGuide {
    fun describe(stable: List<Detection>, current: List<Detection> = stable): String {
        val obstacles = current.filter { it.confidence >= 0.45f && it.box.bottom > 0.55f }
        if (stable.none { it.confidence >= 0.45f && it.box.bottom > 0.55f }) {
            return "暂不能判断避让方向，请确认地面和周围再移动。"
        }
        // Include unconfirmed detections as blockers; never recommend toward a new obstacle.
        val left = coverage(obstacles, 0f, 0.36f)
        val right = coverage(obstacles, 0.64f, 1f)
        val side = when {
            left <= 0.20f && right >= 0.50f -> "左侧"
            right <= 0.20f && left >= 0.50f -> "右侧"
            else -> null
        }
        return if (side != null) "请先停下，可优先观察${side}，确认地面和通道后再绕行。"
        else "请先停下，暂不能确定可绕行的一侧。"
    }

    private fun coverage(items: List<Detection>, start: Float, end: Float): Float {
        val intervals = items.map { maxOf(start, it.box.left) to minOf(end, it.box.right) }
            .filter { it.first < it.second }.sortedBy { it.first }
        var covered = 0f
        var edge = start
        intervals.forEach { (left, right) ->
            covered += (right - maxOf(left, edge)).coerceAtLeast(0f)
            edge = maxOf(edge, right)
        }
        return covered / (end - start)
    }
}
