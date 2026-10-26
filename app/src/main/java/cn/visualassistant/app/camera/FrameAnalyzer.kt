package cn.visualassistant.app.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import cn.visualassistant.app.core.Detection
import cn.visualassistant.app.inference.YoloDetector
import java.util.concurrent.atomic.AtomicBoolean

class FrameAnalyzer(
    private val detector: YoloDetector,
    private val onResult: (List<Detection>, Long) -> Unit,
    private val onError: (Throwable) -> Unit,
) : ImageAnalysis.Analyzer {
    private val running = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!running.compareAndSet(false, true)) {
            image.close()
            return
        }
        val started = System.nanoTime()
        try {
            val bitmap = image.toRgbaBitmap()
            val oriented = if (image.imageInfo.rotationDegrees == 0) bitmap else {
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply {
                    postRotate(image.imageInfo.rotationDegrees.toFloat())
                }, true).also { bitmap.recycle() }
            }
            val detections = detector.detect(oriented)
            oriented.recycle()
            onResult(detections, (System.nanoTime() - started) / 1_000_000)
        } catch (error: Throwable) {
            onError(error)
        } finally {
            running.set(false)
            image.close()
        }
    }
}

private fun ImageProxy.toRgbaBitmap(): Bitmap {
    val plane = planes.first()
    val buffer = plane.buffer
    buffer.rewind()
    val rowPixels = plane.rowStride / 4
    val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
    padded.copyPixelsFromBuffer(buffer)
    if (rowPixels == width) return padded
    return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
}

