package cn.visualassistant.app.spatial

/**
 * Two-stage decision for a stalled camera feed during capture: a short stall
 * only warns the user, a sustained stall ends the recording. Runs on the UI
 * tick regardless of text-to-speech readiness, so a muted or slow TTS engine
 * can no longer disable the capture watchdog.
 */
class FrameWatchdog(private val warnAfterMs: Long = 2500, private val stopAfterMs: Long = 5000) {
    enum class Action { NONE, WARN, STOP }

    private var warned = false

    fun reset() { warned = false }

    fun evaluate(nowMs: Long, lastFrameMs: Long): Action {
        val stalledFor = if (lastFrameMs <= 0) Long.MAX_VALUE else nowMs - lastFrameMs
        return when {
            stalledFor < warnAfterMs -> { warned = false; Action.NONE }
            stalledFor < stopAfterMs -> if (warned) Action.NONE else { warned = true; Action.WARN }
            else -> Action.STOP
        }
    }
}
