package cn.visualassistant.app.core

data class NormalizedBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = (right - left).coerceAtLeast(0f)
    val height: Float get() = (bottom - top).coerceAtLeast(0f)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
}

data class Detection(
    val classId: Int,
    val label: String,
    val confidence: Float,
    val box: NormalizedBox,
)

enum class RiskLevel { SAFE, NOTICE, WARNING, DANGER }

data class RiskAssessment(
    val level: RiskLevel,
    val detection: Detection? = null,
    val direction: String = "前方",
    val message: String = "前方暂未发现明显障碍",
    val isRecovery: Boolean = false,
)
