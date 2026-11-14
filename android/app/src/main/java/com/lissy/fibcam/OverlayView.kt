package com.lissy.fibcam

import android.animation.ValueAnimator
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

/**
 * Draws the guide over the viewfinder. Normal shooting shows only the guide and a focus ring
 * that fades after a tap. The tracked subject and the line to the goal appear only in Coach mode.
 */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onSubjectTap(sx: Float, sy: Float)
        fun onDoubleTap()
        fun onPinch(factor: Float)
    }

    var listener: Listener? = null
    /** The camera image on screen. */
    val frame = RectF()
    /** When the output is square (Holga), guides are laid out on the square that will be saved. */
    var squareGuide = false
    var guide = Guide.SPIRAL
    var quadrant = 0
    var triFlip = false
    var align = 1
    // Coach mode only
    var coach = false
    var target: PointF? = null
    var targetLost = false
    var goal: PointF? = null
    var aligned = false

    private val d = resources.displayMetrics.density
    private val painter = GuidePainter(d)
    private val gold = 0xFFE0B252.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = white; alpha = 26 }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = gold; alpha = 200; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = white }
    private var focusAt: PointF? = null
    private var focusT = 1f
    private var focusAnim: ValueAnimator? = null

    val guideFrame: RectF get() {
        if (!squareGuide || frame.isEmpty) return frame
        val s = minOf(frame.width(), frame.height())
        return RectF(frame.centerX() - s / 2, frame.centerY() - s / 2, frame.centerX() + s / 2, frame.centerY() + s / 2)
    }

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

    /** A ring where you tapped: it shrinks onto the point and fades, like any camera's focus mark. */
    fun showFocus(x: Float, y: Float) {
        focusAt = PointF(x, y)
        focusAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            addUpdateListener { focusT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        if (frame.isEmpty) return
        c.drawRect(frame, edge)
        painter.draw(c, guideFrame, guide, quadrant, triFlip, trueSpiral = false, align = align)
        drawFocus(c)
        if (coach) drawCoach(c)
    }

    private fun drawFocus(c: Canvas) {
        val p = focusAt ?: return
        if (focusT >= 1f) return
        val shrink = minOf(1f, focusT / 0.25f)
        val r = (34 - 8 * shrink) * d
        val fade = if (focusT < 0.6f) 1f else 1f - (focusT - 0.6f) / 0.4f
        focusPaint.alpha = (230 * fade).toInt()
        c.drawCircle(p.x, p.y, r, focusPaint)
    }

    private fun drawCoach(c: Canvas) {
        val p = target ?: return
        val g = goal
        if (g != null && !aligned && !targetLost) c.drawLine(p.x, p.y, g.x, g.y, link)
        mark.color = if (aligned && !targetLost) gold else white
        mark.alpha = 230
        mark.pathEffect = DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
        c.drawCircle(p.x, p.y, 17 * d, mark)
        mark.pathEffect = null
        val a = 19 * d; val b = 24 * d
        c.drawLine(p.x - b, p.y, p.x - a, p.y, mark); c.drawLine(p.x + a, p.y, p.x + b, p.y, mark)
        c.drawLine(p.x, p.y - b, p.x, p.y - a, mark); c.drawLine(p.x, p.y + a, p.x, p.y + b, mark)
    }
}
