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
        /** A drag on the picture; return true if it moved the focus region. */
        fun onFocusDrag(dy: Float): Boolean = false
        fun onFocusDragEnd() {}
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
    /** Hide the guide lines (the guide still drives any linked focus). */
    var hidden = false
        set(v) { field = v; invalidate() }
    /** The guide anchor Follow guide is using (shown as the target); -1 for none. */
    var selectedAnchor = -1
        set(v) { field = v; invalidate() }
    /** Settings › Guide opacity, applied to every guide alike. */
    var guideOpacity: Float
        get() = painter.opacity
        set(v) { painter.opacity = v; invalidate() }
    private var mask: FocusMask? = null
    private var maskAlpha = 0f
    private var maskAnim: ValueAnimator? = null
    private var dragging = false
    private val maskLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE9B65B.toInt() }
    private val maskSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE9B65B.toInt() }

    private val d = resources.displayMetrics.density
    private val painter = GuidePainter(d)
    private val gold = 0xFFE9B65B.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = white; alpha = 26 }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val link = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = gold; alpha = 200; pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f) }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = white }
    private var focusAt: PointF? = null
    private var focusT = 1f
    private var focusAlpha = 0f
    private var focusColor = 0xFFFFFFFF.toInt()
    private var fadeAnim: ValueAnimator? = null
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
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (listener?.onFocusDrag(-dy) == true) { dragging = true; return true }
            return false
        }
    })
    private val pinch = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean { listener?.onPinch(det.scaleFactor); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) taps.onTouchEvent(event)
        if ((event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) && dragging) {
            dragging = false; listener?.onFocusDragEnd()
        }
        return true
    }

    /** A ring where you tapped: it shrinks onto the point and fades, like any camera's focus mark. */
    /** Autofocus requested at (x, y): an Ivory ring closes in and waits for the camera's answer. */
    fun showFocus(x: Float, y: Float) {
        focusAt = PointF(x, y); focusColor = white; focusAlpha = 1f
        focusAnim?.cancel(); fadeAnim?.cancel()
        focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            addUpdateListener { focusT = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    /**
     * The camera's answer: true = focus confirmed (the ring turns Amber), false = it couldn't lock or the
     * lens is fixed-focus, null = autofocus isn't available here. Only a confirmed lock turns Amber.
     */
    fun focusResult(ok: Boolean?) {
        if (focusAt == null) return
        focusColor = if (ok == true) gold else 0xFF8A877F.toInt()
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = if (ok == true) 500 else 250; duration = 500
            addUpdateListener { focusAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (frame.isEmpty) return
        c.drawRect(frame, edge)
        if (!hidden) painter.draw(c, guideFrame, guide, quadrant, triFlip, trueSpiral = false, align = align, selected = selectedAnchor)
        drawMask(c)
        drawFocus(c)
        if (coach) drawCoach(c)
    }

    /** Shows the simulated focus region while it's being adjusted; it fades away by itself. */
    fun showMask(m: FocusMask) {
        mask = m
        maskAnim?.cancel(); maskAlpha = 1f; invalidate()
        maskAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            startDelay = 1400; duration = 500
            addUpdateListener { maskAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    fun fadeMask() { if (maskAlpha > 0f && maskAnim?.isRunning != true) showMask(mask ?: return) }

    private fun drawMask(c: Canvas) {
        val m = mask ?: return
        if (maskAlpha <= 0.01f || frame.isEmpty) return
        val h = frame.height()
        val cx = frame.left + m.cx * frame.width(); val cy = frame.top + m.cy * h
        val inner = m.size * h; val outer = (m.size + 0.03f + m.feather * 0.55f) * h
        maskLine.alpha = (220 * maskAlpha).toInt(); maskLine.strokeWidth = 1.3f * d; maskLine.pathEffect = null
        maskSoft.alpha = (140 * maskAlpha).toInt(); maskSoft.strokeWidth = 1f * d; maskSoft.pathEffect = DashPathEffect(floatArrayOf(4 * d, 5 * d), 0f)
        c.save(); c.clipRect(frame)
        if (m.type == 2) {
            c.drawCircle(cx, cy, inner, maskLine); c.drawCircle(cx, cy, outer, maskSoft)
            c.drawCircle(cx, cy, 3 * d, maskLine)
        } else if (m.type == 1) {
            val ux = kotlin.math.cos(m.angle); val uy = kotlin.math.sin(m.angle)
            val nx = -uy; val ny = ux
            val len = frame.width() + frame.height()
            for ((off, p) in listOf(inner to maskLine, -inner to maskLine, outer to maskSoft, -outer to maskSoft)) {
                val ox = cx + nx * off; val oy = cy + ny * off
                c.drawLine(ox - ux * len, oy - uy * len, ox + ux * len, oy + uy * len, p)
            }
        }
        c.restore()
    }

    private fun drawFocus(c: Canvas) {
        val p = focusAt ?: return
        if (focusAlpha <= 0f) return
        val r = (34 - 8 * focusT) * d
        focusPaint.color = focusColor
        focusPaint.alpha = (230 * focusAlpha).toInt()
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
