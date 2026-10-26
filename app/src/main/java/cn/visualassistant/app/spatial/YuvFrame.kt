package cn.visualassistant.app.spatial

import android.graphics.Bitmap
import android.media.Image
import kotlin.math.max

/**
 * A cheap snapshot of an ARCore CPU image. Copying plane bytes on the GL thread
 * costs a memcpy; the expensive per-pixel YUV conversion runs later on a worker
 * thread so a stalled converter cannot freeze rendering or pose updates.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val timestampNs: Long,
    private val planes: List<ByteArray>,
    private val rowStrides: IntArray,
    private val pixelStrides: IntArray,
    private val rotationDegrees: Int,
) {
    private fun value(p:Int,px:Int,py:Int):Int {
        val safeX=px.coerceIn(0,if(p==0)width-1 else (width-1)/2)
        val safeY=py.coerceIn(0,if(p==0)height-1 else (height-1)/2)
        return planes[p][safeY*rowStrides[p]+safeX*pixelStrides[p]].toInt() and 255
    }

    /** RGB sampled in the camera image coordinate system at each depth pixel. */
    fun alignedRgb(imageCoordinates:FloatArray,targetWidth:Int,targetHeight:Int):ByteArray {
        require(imageCoordinates.size==targetWidth*targetHeight*2)
        val out=ByteArray(targetWidth*targetHeight*3)
        for(i in 0 until targetWidth*targetHeight){
            val nx=imageCoordinates[i*2].coerceIn(0f,.999999f);val ny=imageCoordinates[i*2+1].coerceIn(0f,.999999f)
            val x=(nx*width).toInt();val y=(ny*height).toInt();val yy=max(0,value(0,x,y)-16)
            val u=value(1,x/2,y/2)-128;val v=value(2,x/2,y/2)-128
            out[i*3]=((298*yy+409*v+128 shr 8).coerceIn(0,255)).toByte()
            out[i*3+1]=((298*yy-100*u-208*v+128 shr 8).coerceIn(0,255)).toByte()
            out[i*3+2]=((298*yy+516*u+128 shr 8).coerceIn(0,255)).toByte()
        }
        return out
    }

    fun toBitmap(): Bitmap {
        val scale = max(1, width / 640)
        val w = width / scale; val h = height / scale
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val ix = x * scale; val iy = y * scale
            val yy = max(0, value(0, ix, iy) - 16)
            val u = value(1, ix / 2, iy / 2) - 128; val v = value(2, ix / 2, iy / 2) - 128
            val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
            val index = when (rotationDegrees) {
                90 -> x * h + (h - 1 - y)
                180 -> (h - 1 - y) * w + (w - 1 - x)
                270 -> (w - 1 - x) * h + y
                else -> y * w + x
            }
            pixels[index] = (255 shl 24) or (r shl 16) or (g shl 8) or b
        }
        return if (rotationDegrees in listOf(90, 270)) Bitmap.createBitmap(pixels, h, w, Bitmap.Config.ARGB_8888)
        else Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    companion object {
        /** Copies every plane immediately; the source Image must still be open. */
        fun from(image: Image, rotationDegrees: Int): YuvFrame {
            val src = image.planes
            val data = src.map { plane ->
                val dup = plane.buffer.duplicate()
                dup.clear()
                val bytes = ByteArray(dup.remaining())
                dup.get(bytes)
                bytes
            }
            return YuvFrame(image.width, image.height, image.timestamp, data,
                IntArray(src.size) { src[it].rowStride },
                IntArray(src.size) { src[it].pixelStride },
                rotationDegrees)
        }
    }
}
