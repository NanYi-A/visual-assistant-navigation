package cn.visualassistant.app.voice

import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import cn.visualassistant.app.risk.AvoidanceGuide

/** Uses only recent detections; never interprets no detections as a safe path. */
class SceneDescriber {
    private data class Frame(val time: Long, val items: List<Detection>)
    private val frames = ArrayDeque<Frame>()

    fun update(items: List<Detection>, now: Long) {
        frames.addLast(Frame(now, items.filter { it.confidence >= 0.45f }))
        while (frames.size > 3) frames.removeFirst()
    }

    fun clear() = frames.clear()

    fun answer(question: String, now: Long, memoryCaution: String? = null): String {
        val asksScene = listOf("什么", "啥", "哪些", "看看", "描述", "介绍", "周围", "前面", "前方",
            "怎么走", "往哪", "往那", "哪边", "那边", "避开", "避让", "绕", "能走", "可以走")
            .any { question.contains(it) }
        if (!asksScene) return "可以问我：前面有什么，或者左边有什么。"
        val latest = frames.lastOrNull()
            ?: return "还没有获取到画面，请稍等再问。"
        if (now - latest.time > 2_000) return "当前画面已过期，请恢复检测后再问。"
        val previous = frames.dropLast(1).filter { now - it.time <= 2_000 }
        if (previous.isEmpty()) return "正在确认画面，请稍等再问。"
        // Match each historic instance at most once, so counts are not inflated.
        val stable = mutableSetOf<Int>()
        previous.forEach { frame ->
            val used = mutableSetOf<Int>()
            latest.items.forEachIndexed { index, item ->
                val match = frame.items.indices.filter { it !in used && frame.items[it].classId == item.classId }
                    .maxByOrNull { overlap(item.box, frame.items[it].box) }
                if (match != null && overlap(item.box, frame.items[match].box) >= 0.3f) {
                    used += match
                    stable += index
                }
            }
        }
        val requestedSide = when {
            "左" in question -> "左侧"
            "右" in question -> "右侧"
            else -> null
        }
        val stableItems = latest.items.filterIndexed { index, _ -> index in stable }
        // Guidance always considers the whole image, even for a question about one side.
        val guidance = memoryCaution ?: AvoidanceGuide.describe(stableItems, latest.items)
        val groups = stableItems
            .filter { requestedSide == null || direction(it) == requestedSide }
            .groupBy { direction(it) to it.label }
            .entries.sortedWith(compareBy({ if (it.key.first == "前方") 0 else 1 }, { -it.value.size }))
        if (groups.isEmpty()) return "${requestedSide ?: "当前画面"}暂时没有识别到明显目标。$guidance"
        val descriptions = groups.take(5).map { (key, items) ->
            val (side, label) = key
            val unit = when (label) {
                "行人" -> "位"
                "汽车", "自行车", "摩托车", "公交车", "卡车", "火车" -> "辆"
                "椅子" -> "把"
                "猫", "狗", "鸟" -> "只"
                else -> "个"
            }
            val count = listOf("零", "一", "两", "三", "四", "五", "六", "七", "八", "九")
                .getOrElse(items.size) { items.size.toString() }
            "${side}有${count}${unit}${label}"
        }
        return descriptions.joinToString("，") + (if (groups.size > 5) "，还有其他目标。" else "。") + guidance
    }

    private fun direction(d: Detection) = when {
        d.box.centerX < 0.36f -> "左侧"
        d.box.centerX > 0.64f -> "右侧"
        else -> "前方"
    }

    private fun overlap(a: NormalizedBox, b: NormalizedBox): Float {
        val area = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return area / (a.area + b.area - area + 1e-6f)
    }
}
