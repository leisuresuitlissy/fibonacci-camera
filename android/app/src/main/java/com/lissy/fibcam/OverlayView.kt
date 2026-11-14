package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/** Draws the guide and the tracked subject over the viewfinder, and turns touches into intents. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onSubjectTap(sx: Float, sy: Float)
        fun onDoubleTap()
        fun onPinch(factor: Float)
    }

    var listener: Listener? = null
    val frame = RectF()
    var guide = Guide.SPIRAL
    var quadrant = 0
    var triFlip = false
    var align = 1
    /** Tracked subject in screen px, or null. */
    var target: PointF? = null
    var targetLost = false
    var aligned = false

    private val d = resources.displayMetrics.density
    private val painter = GuidePainter(d)
    private val gold = 0xFFE0B252.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = white; alpha = 26 }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }

    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { listener?.onSubjectTap(e.x, e.y); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean { listener?.onDoubleTap(); return true }
    })
    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean { listener?.onPinch(det.scaleFactor); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) taps.onTouchEvent(event)
        return true
    }

    override fun onDraw(c: Canvas) {
        if (frame.isEmpty) return
        c.drawRect(frame, edge)
        painter.draw(c, frame, guide, quadrant, triFlip, trueSpiral = false, align = align)   // true φ, full height
        drawTarget(c)
    }

    private fun drawTarget(c: Canvas) {
        val p = target ?: return
        mark.color = if (aligned && !targetLost) gold else white
        mark.alpha = 230
        mark.pathEffect = if (targetLost) DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) else null
        c.drawCircle(p.x, p.y, 17 * d, mark)
        mark.pathEffect = null
        val a = 19 * d; val b = 24 * d
        c.drawLine(p.x - b, p.y, p.x - a, p.y, mark); c.drawLine(p.x + a, p.y, p.x + b, p.y, mark)
        c.drawLine(p.x, p.y - b, p.x, p.y - a, mark); c.drawLine(p.x, p.y + a, p.x, p.y + b, mark)
    }
}
