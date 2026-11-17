package com.study.souti.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object ImageProcessor {
    const val MAX_LONG_EDGE = 2048
    const val JPEG_QUALITY = 90
    private const val MAX_DECODE_LONG_EDGE = 3600

    data class Crop(
        val left: Float = 0f,
        val top: Float = 0f,
        val right: Float = 0f,
        val bottom: Float = 0f,
    ) {
        companion object {
            val FULL = Crop()
        }
    }

    fun process(sourceFile: File, destinationFile: File, crop: Crop = Crop.FULL) {
        val rotation = readRotation(sourceFile)
        val decoded = decodeSampled(sourceFile, crop)
        var output: Bitmap? = null
        try {
            val cropRect = cropRectOf(decoded.width, decoded.height, crop)
            val targetSize = calculateScaledSize(cropRect[2], cropRect[3])
            val matrix = Matrix()
            if (rotation != 0) matrix.postRotate(rotation.toFloat())
            matrix.postScale(
                targetSize[0].toFloat() / cropRect[2],
                targetSize[1].toFloat() / cropRect[3],
            )
            val transformed = Bitmap.createBitmap(
                decoded,
                cropRect[0],
                cropRect[1],
                cropRect[2],
                cropRect[3],
                matrix,
                true,
            )
            output = transformed

            val parent = destinationFile.parentFile
                ?: throw IOException("输出文件没有父目录")
            if (!parent.exists() && !parent.mkdirs()) {
                throw IOException("无法创建图像输出目录")
            }
            FileOutputStream(destinationFile).buffered().use { stream ->
                if (!transformed.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) {
                    throw IOException("JPEG 编码失败")
                }
                stream.flush()
            }
        } catch (failure: Throwable) {
            destinationFile.delete()
            throw failure
        } finally {
            if (output != null && output !== decoded) output.recycle()
            decoded.recycle()
        }
    }

    fun cropRectOf(width: Int, height: Int, crop: Crop): IntArray {
        val x = (width * crop.left).roundToInt().coerceIn(0, width - 1)
        val y = (height * crop.top).roundToInt().coerceIn(0, height - 1)
        val cropWidth = (width * (1f - crop.left - crop.right)).roundToInt()
            .coerceIn(1, width - x)
        val cropHeight = (height * (1f - crop.top - crop.bottom)).roundToInt()
            .coerceIn(1, height - y)
        return intArrayOf(x, y, cropWidth, cropHeight)
    }

    fun calculateScaledSize(
        width: Int,
        height: Int,
        maxLongEdge: Int = MAX_LONG_EDGE,
    ): IntArray {
        require(width > 0 && height > 0) { "图像尺寸必须大于 0" }
        require(maxLongEdge > 0) { "长边限制必须大于 0" }
        val longEdge = max(width, height)
        if (longEdge <= maxLongEdge) return intArrayOf(width, height)

        val scale = maxLongEdge.toDouble() / longEdge
        return intArrayOf(
            (width * scale).roundToInt().coerceAtLeast(1),
            (height * scale).roundToInt().coerceAtLeast(1),
        )
    }

    private fun decodeSampled(sourceFile: File, crop: Crop): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("无法解码相机图像")
        }

        val cropFraction = max(1f - crop.left - crop.right, 1f - crop.top - crop.bottom)
            .coerceIn(0.2f, 1f)
        val decodeTarget = (MAX_LONG_EDGE / cropFraction).roundToInt()
            .coerceIn(MAX_LONG_EDGE, MAX_DECODE_LONG_EDGE)

        var sampleSize = 1
        val longEdge = max(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sampleSize * 2) >= decodeTarget) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(sourceFile.absolutePath, options)
            ?: throw IOException("无法解码相机图像")
    }

    private fun readRotation(sourceFile: File): Int {
        val orientation = ExifInterface(sourceFile.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }
}
