package cn.visualassistant.app.inference

import cn.visualassistant.app.core.NormalizedBox

data class LetterboxTransform(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val targetSize: Int = 640,
) {
    val scale = minOf(targetSize.toFloat() / sourceWidth, targetSize.toFloat() / sourceHeight)
    val scaledWidth = sourceWidth * scale
    val scaledHeight = sourceHeight * scale
    val padX = (targetSize - scaledWidth) / 2f
    val padY = (targetSize - scaledHeight) / 2f

    fun restore(x1: Float, y1: Float, x2: Float, y2: Float): NormalizedBox = NormalizedBox(
        ((x1 - padX) / scale / sourceWidth).coerceIn(0f, 1f),
        ((y1 - padY) / scale / sourceHeight).coerceIn(0f, 1f),
        ((x2 - padX) / scale / sourceWidth).coerceIn(0f, 1f),
        ((y2 - padY) / scale / sourceHeight).coerceIn(0f, 1f),
    )
}

