package cn.visualassistant.app

import androidx.lifecycle.ViewModel
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.RiskAssessment
import cn.visualassistant.app.risk.RiskEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import cn.visualassistant.app.voice.SceneDescriber

data class AssistantUiState(
    val status: String = "正在启动本地检测",
    val detections: List<Detection> = emptyList(),
    val risk: RiskAssessment = RiskAssessment(cn.visualassistant.app.core.RiskLevel.SAFE),
    val inferenceMs: Long = 0,
    val cameraRunning: Boolean = true,
    val voiceBusy: Boolean = false,
    val voiceMessage: String = "",
)

class MainViewModel : ViewModel() {
    private val riskEngine = RiskEngine()
    private val sceneDescriber = SceneDescriber()
    private val obstacleMemory = cn.visualassistant.app.voice.ObstacleMemory()
    private val recoveryTracker = cn.visualassistant.app.risk.RecoveryTracker()
    private var recoveryDisplayUntil = 0L
    private val mutableState = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = mutableState.asStateFlow()

    fun onDetections(detections: List<Detection>, inferenceMs: Long): RiskAssessment? {
        if (!mutableState.value.cameraRunning) return null
        val now = android.os.SystemClock.elapsedRealtime()
        sceneDescriber.update(detections, now)
        obstacleMemory.update(detections, now)
        val caution = obstacleMemory.caution(now)
        val rawAssessment = riskEngine.assess(detections)
        val assessment = if (caution != null && rawAssessment.level != cn.visualassistant.app.core.RiskLevel.SAFE)
            rawAssessment.copy(message = rawAssessment.message.substringBefore("。") + "。" + caution) else rawAssessment
        // Disappearance or expiry of a remembered obstacle is not evidence of successful avoidance.
        val unresolved = obstacleMemory.unresolved(now)
        val recovery = recoveryTracker.update(detections, assessment, now, !mutableState.value.voiceBusy, unresolved)
        if (recovery != null) {
            recoveryDisplayUntil = now + 5000
            riskEngine.reset() // A new obstacle must alert immediately, even at the same severity.
        }
        val memoryDisplay = if (caution != null && assessment.level == cn.visualassistant.app.core.RiskLevel.SAFE)
            RiskAssessment(cn.visualassistant.app.core.RiskLevel.NOTICE, message = caution) else null
        val displayed = memoryDisplay ?: recovery ?: if (assessment.level == cn.visualassistant.app.core.RiskLevel.SAFE &&
            detections.none { it.confidence >= .45f } && now < recoveryDisplayUntil &&
            mutableState.value.risk.isRecovery) mutableState.value.risk else assessment
        mutableState.value = mutableState.value.copy(
            detections = detections,
            risk = displayed,
            inferenceMs = inferenceMs,
            voiceMessage = if (recovery != null) "" else mutableState.value.voiceMessage,
        )
        val alert = assessment.takeIf { riskEngine.shouldAlert(it, now) }
        if (alert != null) return alert
        if (!mutableState.value.voiceBusy && assessment.level == cn.visualassistant.app.core.RiskLevel.SAFE) {
            obstacleMemory.reminder(now)?.let {
                mutableState.value = mutableState.value.copy(voiceMessage = "")
                return RiskAssessment(cn.visualassistant.app.core.RiskLevel.NOTICE, message = it)
            }
        }
        return recovery
    }

    fun setStatus(message: String) { mutableState.value = mutableState.value.copy(status = message) }
    fun setVoice(busy: Boolean, message: String) {
        mutableState.value = mutableState.value.copy(voiceBusy = busy, voiceMessage = message)
    }
    fun answer(question: String): String {
        if (!mutableState.value.cameraRunning) return "检测已暂停，请先继续检测。"
        val now = android.os.SystemClock.elapsedRealtime()
        return obstacleMemory.answer(question, now)
            ?: sceneDescriber.answer(question, now, obstacleMemory.caution(now))
    }

    fun toggleCamera() {
        resetScene()
        val running = !mutableState.value.cameraRunning
        mutableState.value = mutableState.value.copy(
            cameraRunning = running,
            detections = if (running) mutableState.value.detections else emptyList(),
            risk = if (running) mutableState.value.risk else RiskAssessment(cn.visualassistant.app.core.RiskLevel.SAFE),
            status = if (running) "正在恢复检测" else "检测已暂停",
        )
    }

    fun resetScene() {
        sceneDescriber.clear()
        obstacleMemory.clear()
        riskEngine.reset()
        recoveryTracker.reset()
        recoveryDisplayUntil = 0
    }
}
