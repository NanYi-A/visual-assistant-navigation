package cn.visualassistant.app.spatial

enum class ScanMode(
    val wireName: String,
    val displayName: String,
    val keyframeLifetimeMs: Long,
    val maxLocalKeyframes: Int,
    val persistent: Boolean,
) {
    OUTDOOR(
        wireName = "outdoor",
        displayName = "户外即时扫描",
        keyframeLifetimeMs = 12_000,
        maxLocalKeyframes = 28,
        persistent = false,
    ),
    HOME(
        wireName = "home",
        displayName = "家庭路线建图",
        keyframeLifetimeMs = 120_000,
        maxLocalKeyframes = 192,
        persistent = true,
    );

    companion object {
        fun fromWireName(value: String?): ScanMode = entries.firstOrNull { it.wireName == value } ?: OUTDOOR
    }
}
