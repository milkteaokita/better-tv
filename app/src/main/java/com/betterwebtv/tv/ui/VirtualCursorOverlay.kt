package com.betterwebtv.tv.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class VirtualCursorOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var cursorX: Float = -1f
    private var cursorY: Float = -1f
    private var holdSteps: Int = 0
    private var lastMoveTimeMs: Long = 0L
    private var pulseRadius: Float = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD54F")
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val arrowPath = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (cursorX < 0f || cursorY < 0f) {
            cursorX = w * 0.5f
            cursorY = h * 0.5f
        } else {
            cursorX = cursorX.coerceIn(0f, (w - 1).coerceAtLeast(0).toFloat())
            cursorY = cursorY.coerceIn(0f, (h - 1).coerceAtLeast(0).toFloat())
        }
    }

    fun resetToCenter() {
        if (width > 0 && height > 0) {
            cursorX = width * 0.5f
            cursorY = height * 0.5f
            invalidate()
        }
    }

    fun moveBy(dxSign: Int, dySign: Int) {
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        holdSteps = if (now - lastMoveTimeMs < 180L) {
            (holdSteps + 1).coerceAtMost(14)
        } else {
            0
        }
        lastMoveTimeMs = now

        val step = 18f + holdSteps * 4.5f
        cursorX = (cursorX + dxSign * step).coerceIn(8f, (width - 8).toFloat())
        cursorY = (cursorY + dySign * step).coerceIn(8f, (height - 8).toFloat())
        invalidate()
    }

    fun performClick(targetView: View) {
        if (width <= 0 || height <= 0) return
        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            cursorX,
            cursorY,
            0
        )
        val upEvent = MotionEvent.obtain(
            downTime,
            downTime + 55L,
            MotionEvent.ACTION_UP,
            cursorX,
            cursorY,
            0
        )
        targetView.dispatchTouchEvent(downEvent)
        targetView.dispatchTouchEvent(upEvent)
        downEvent.recycle()
        upEvent.recycle()

        pulseRadius = 26f
        invalidate()
        postDelayed({
            pulseRadius = 0f
            invalidate()
        }, 180L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (cursorX < 0f || cursorY < 0f) return

        if (pulseRadius > 0f) {
            canvas.drawCircle(cursorX, cursorY, pulseRadius, pulsePaint)
        }

        val s = resources.displayMetrics.density * 1.25f
        arrowPath.reset()
        arrowPath.moveTo(cursorX, cursorY)
        arrowPath.lineTo(cursorX, cursorY + 26f * s)
        arrowPath.lineTo(cursorX + 7f * s, cursorY + 20f * s)
        arrowPath.lineTo(cursorX + 13f * s, cursorY + 30f * s)
        arrowPath.lineTo(cursorX + 17f * s, cursorY + 28f * s)
        arrowPath.lineTo(cursorX + 11f * s, cursorY + 18f * s)
        arrowPath.lineTo(cursorX + 19f * s, cursorY + 18f * s)
        arrowPath.close()

        canvas.drawPath(arrowPath, fillPaint)
        canvas.drawPath(arrowPath, strokePaint)
    }
}
