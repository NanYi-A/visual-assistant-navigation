package cn.visualassistant.app.risk

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.RiskAssessment
import cn.visualassistant.app.core.RiskLevel

/** Reports a sustained change in detections, never certifies a walkable route. */
class RecoveryTracker {
    private var armed = false
    private var clearSince: Long? = null
    private var clearFrames = 0
    private var lastFrameAt: Long? = null

    fun update(items: List<Detection>, risk: RiskAssessment, now: Long, canSpeak: Boolean = true,
        rememberedObstacle: Boolean = false): RiskAssessment? {
        if (rememberedObstacle) {
            reset()
            return null
        }
        val previous = lastFrameAt
        if (previous != null && (now < previous || now - previous > 1000)) reset()
        lastFrameAt = now
        if (risk.level != RiskLevel.SAFE) armed = true
        // Even a new, unconfirmed obstacle must stop the recovery countdown.
        val blocked = items.any {
            it.confidence >= .45f && (it.box.bottom > .70f || it.box.area > .10f ||
                (it.box.left < .70f && it.box.right > .30f && it.box.bottom > .55f))
        }
        if (!armed || blocked || risk.level != RiskLevel.SAFE) {
            clearSince = null
            clearFrames = 0
            return null
        }
        if (clearSince == null) clearSince = now
        clearFrames++
        if (now - clearSince!! < 2000 || clearFrames < 5 || !canSpeak) return null
        armed = false
        clearSince = null
        clearFrames = 0
        return RiskAssessment(RiskLevel.SAFE,
            message = "连续未检测到前方障碍，请确认地面和周围后再继续。",
            isRecovery = true)
    }

    fun reset() {
        armed = false
        clearSince = null
        clearFrames = 0
        lastFrameAt = null
    }
}
