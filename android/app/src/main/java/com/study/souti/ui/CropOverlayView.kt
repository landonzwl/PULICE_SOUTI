package com.study.souti.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 0)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    private val cropRect = RectF()

    var cropLeft = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cropTop = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cropRight = 0f
        set(value) {
            field = value
            invalidate()
        }

    var cropBottom = 0f
        set(value) {
            field = value
            invalidate()
        }

    var showFocusMarker = false
        set(value) {
            field = value
            invalidate()
        }

    var focusX = 0.5f
        set(value) {
            field = value
            invalidate()
        }

    var focusY = 0.5f
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        cropRect.set(
            width * cropLeft,
            height * cropTop,
            width * (1f - cropRight),
            height * (1f - cropBottom),
        )
        canvas.drawRect(0f, 0f, width, cropRect.top, scrimPaint)
        canvas.drawRect(0f, cropRect.bottom, width, height, scrimPaint)
        canvas.drawRect(0f, cropRect.top, cropRect.left, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect.right, cropRect.top, width, cropRect.bottom, scrimPaint)
        canvas.drawRect(cropRect, borderPaint)

        if (showFocusMarker) {
            canvas.drawCircle(
                width * focusX,
                height * focusY,
                36f,
                focusPaint,
            )
        }
    }
}
