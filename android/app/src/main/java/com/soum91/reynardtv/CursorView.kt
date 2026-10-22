package com.soum91.reynardtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

/** The on-screen pointer driven by the remote's D-pad. Purely visual; MainActivity moves it. */
class CursorView(context: Context) : View(context) {
    var cursorX = 0f
        private set
    var cursorY = 0f
        private set

    private val density = resources.displayMetrics.density
    private val radius = 11f * density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = Color.argb(230, 252, 55, 39)
    }

    init {
        isFocusable = false
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0) {
            cursorX = w / 2f
            cursorY = h / 2f
        } else {
            moveTo(cursorX, cursorY)
        }
    }

    fun moveTo(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (width - 1).coerceAtLeast(0).toFloat())
        cursorY = y.coerceIn(0f, (height - 1).coerceAtLeast(0).toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cursorX, cursorY, radius, fill)
        canvas.drawCircle(cursorX, cursorY, radius, ring)
    }
}
