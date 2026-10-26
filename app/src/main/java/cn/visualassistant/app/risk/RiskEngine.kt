package cn.visualassistant.app.risk

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.RiskAssessment
import cn.visualassistant.app.core.RiskLevel

/** Pure Kotlin policy: deterministic, testable, and independent from camera/UI. */
class RiskEngine(
    private val minConfidence: Float = 0.45f,
    private val stableFrames: Int = 2,
    private val normalCooldownMs: Long = 4_000,
) {
    private data class Seen(val boxCenter: Float, val count: Int)
    private val history = mutableMapOf<String, Seen>()
    private var lastAlertAt = Long.MIN_VALUE
    private var lastLevel = RiskLevel.SAFE
    private var repeatDelayMs = normalCooldownMs

    fun assess(detections: List<Detection>): RiskAssessment {
        val candidates = detections.filter { it.confidence >= minConfidence }
        val keyedCandidates = candidates.groupBy(::trackKey).mapValues { (_, items) ->
            items.maxBy { it.confidence }
        }
        val present = keyedCandidates.keys
        history.keys.retainAll(present)

        keyedCandidates.forEach { (key, detection) ->
            val old = history[key]
            val sameTrack = old != null && kotlin.math.abs(old.boxCenter - detection.box.centerX) < 0.18f
            history[key] = Seen(detection.box.centerX, if (sameTrack) old!!.count + 1 else 1)
        }

        val stable = keyedCandidates.mapNotNull { (key, detection) ->
            detection.takeIf { (history[key]?.count ?: 0) >= stableFrames }
        }
        val best = stable.maxByOrNull { score(it) } ?: return RiskAssessment(RiskLevel.SAFE)
        val level = levelFor(best)
        val direction = when {
            best.box.centerX < 0.36f -> "左侧"
            best.box.centerX > 0.64f -> "右侧"
            else -> "前方"
        }
        val proximity = if (best.box.bottom > 0.82f || best.box.area > 0.20f) "近处" else ""
        val prefix = when (level) {
            RiskLevel.DANGER -> "危险，请停下，"
            RiskLevel.WARNING -> "注意，"
            RiskLevel.NOTICE -> "提示，"
            RiskLevel.SAFE -> ""
        }
        val guidance = if (level == RiskLevel.SAFE) "" else "。" + AvoidanceGuide.describe(stable, candidates)
        return RiskAssessment(level, best, direction, "$prefix$direction$proximity\u6709${best.label}$guidance")
    }

    fun shouldAlert(assessment: RiskAssessment, nowMs: Long): Boolean {
        if (assessment.level == RiskLevel.SAFE) return false
        val upgraded = assessment.level.ordinal > lastLevel.ordinal
        val cooledDown = lastAlertAt == Long.MIN_VALUE || nowMs - lastAlertAt >= repeatDelayMs
        if (upgraded || cooledDown) {
            lastLevel = assessment.level
            lastAlertAt = nowMs
            // Leave time for the longer guidance; danger escalation still interrupts immediately.
            repeatDelayMs = maxOf(normalCooldownMs, assessment.message.length * 250L + 500L)
            return true
        }
        return false
    }

    fun reset() {
        history.clear()
        lastAlertAt = Long.MIN_VALUE
        lastLevel = RiskLevel.SAFE
        repeatDelayMs = normalCooldownMs
    }

    private fun levelFor(d: Detection): RiskLevel {
        val central = d.box.centerX in 0.30f..0.70f
        return when {
            central && (d.box.bottom > 0.88f || d.box.area > 0.28f) -> RiskLevel.DANGER
            central && (d.box.bottom > 0.72f || d.box.area > 0.12f) -> RiskLevel.WARNING
            d.box.bottom > 0.70f || d.box.area > 0.10f -> RiskLevel.NOTICE
            else -> RiskLevel.SAFE
        }
    }

    private fun score(d: Detection): Float {
        val centrality = 1f - (kotlin.math.abs(d.box.centerX - 0.5f) * 2f).coerceIn(0f, 1f)
        return d.box.bottom * 0.45f + d.box.area.coerceAtMost(0.4f) * 1.1f + centrality * 0.25f
    }

    private fun trackKey(d: Detection): String = "${d.classId}:${(d.box.centerX * 5).toInt().coerceIn(0, 4)}"
}
