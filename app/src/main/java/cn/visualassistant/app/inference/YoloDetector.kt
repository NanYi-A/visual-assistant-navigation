package cn.visualassistant.app.inference

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.core.NormalizedBox
import java.nio.FloatBuffer

class YoloDetector(context: Context) : AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val labels = CocoLabels.zh

    init {
        val model = context.assets.open("yolo11n_int8.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(2)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        session = environment.createSession(model, options)
    }

    fun detect(bitmap: Bitmap): List<Detection> {
        val transform = LetterboxTransform(bitmap.width, bitmap.height)
        val input = preprocess(bitmap, transform)
        OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, 640, 640)).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val output = result[0].value as Array<Array<FloatArray>>
                return postprocess(output[0], transform)
            }
        }
    }

    private fun preprocess(source: Bitmap, t: LetterboxTransform): FloatArray {
        val canvasBitmap = Bitmap.createBitmap(640, 640, Bitmap.Config.ARGB_8888)
        Canvas(canvasBitmap).apply {
            drawColor(Color.rgb(114, 114, 114))
            drawBitmap(
                source,
                null,
                android.graphics.RectF(t.padX, t.padY, t.padX + t.scaledWidth, t.padY + t.scaledHeight),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        val pixels = IntArray(640 * 640)
        canvasBitmap.getPixels(pixels, 0, 640, 0, 0, 640, 640)
        canvasBitmap.recycle()
        val data = FloatArray(3 * 640 * 640)
        pixels.forEachIndexed { index, pixel ->
            data[index] = Color.red(pixel) / 255f
            data[640 * 640 + index] = Color.green(pixel) / 255f
            data[2 * 640 * 640 + index] = Color.blue(pixel) / 255f
        }
        return data
    }

    private fun postprocess(raw: Array<FloatArray>, t: LetterboxTransform): List<Detection> {
        // YOLO11 export is normally [84, 8400]. Normalize to one candidate per loop.
        val channelsFirst = raw.size <= 100
        val candidateCount = if (channelsFirst) raw[0].size else raw.size
        val channelCount = if (channelsFirst) raw.size else raw[0].size
        val candidates = ArrayList<Detection>()
        for (i in 0 until candidateCount) {
            fun value(channel: Int): Float = if (channelsFirst) raw[channel][i] else raw[i][channel]
            var bestClass = -1
            var bestScore = 0.45f
            for (c in 4 until channelCount) {
                val score = value(c)
                if (score > bestScore) { bestScore = score; bestClass = c - 4 }
            }
            if (bestClass < 0) continue
            val cx = value(0); val cy = value(1); val w = value(2); val h = value(3)
            candidates += Detection(
                bestClass,
                labels.getOrElse(bestClass) { "目标" },
                bestScore,
                t.restore(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2),
            )
        }
        return nms(candidates, 0.45f).take(20)
    }

    private fun nms(items: List<Detection>, threshold: Float): List<Detection> {
        val remaining = items.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (remaining.isNotEmpty()) {
            val best = remaining.removeAt(0)
            kept += best
            remaining.removeAll { it.classId == best.classId && iou(it.box, best.box) > threshold }
        }
        return kept
    }

    private fun iou(a: NormalizedBox, b: NormalizedBox): Float {
        val intersection = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0f) *
            (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0f)
        return intersection / (a.area + b.area - intersection + 1e-6f)
    }

    override fun close() { session.close() }
}

private object CocoLabels {
    val zh = listOf(
        "行人", "自行车", "汽车", "摩托车", "飞机", "公交车", "火车", "卡车", "船", "红绿灯",
        "消防栓", "停止标志", "停车计费器", "长椅", "鸟", "猫", "狗", "马", "羊", "牛",
        "大象", "熊", "斑马", "长颈鹿", "背包", "雨伞", "手提包", "领带", "行李箱", "飞盘",
        "滑雪板", "单板滑雪", "球", "风筝", "棒球棒", "棒球手套", "滑板", "冲浪板", "网球拍", "瓶子",
        "酒杯", "杯子", "叉子", "刀", "勺子", "碗", "香蕉", "苹果", "三明治", "橙子",
        "西兰花", "胡萝卜", "热狗", "披萨", "甜甜圈", "蛋糕", "椅子", "沙发", "盆栽", "床",
        "餐桌", "马桶", "电视", "笔记本电脑", "鼠标", "遥控器", "键盘", "手机", "微波炉", "烤箱",
        "烤面包机", "水槽", "冰箱", "书", "钟表", "花瓶", "剪刀", "毛绒玩具", "吹风机", "牙刷",
    )
}
