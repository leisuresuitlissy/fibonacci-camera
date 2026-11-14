package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
    /** Tracked subject in screen px, or null. */
    var target: PointF? = null
    var targetLost = false
    var aligned = false

    private val d = resources.displayMetrics.density
    private val gold = 0xFFE0B252.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = white; alpha = 97 }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = white; alpha = 220; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = gold }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = gold }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = white; alpha = 26 }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = white; alpha = 150; pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val path = Path()

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
        when (guide) {
            Guide.OFF -> {}
            Guide.THIRDS -> grid(c, floatArrayOf(1 / 3f, 2 / 3f))
            Guide.PHI_GRID -> grid(c, floatArrayOf((1 - 1 / PHI).toFloat(), (1 / PHI).toFloat()))
            Guide.SPIRAL -> spiral(c)
            Guide.TRIANGLES -> triangles(c)
            Guide.DIAGONALS -> diagonals(c)
            Guide.DYNAMIC -> dynamic(c)
            Guide.CENTRE -> centre(c)
            Guide.MARKS -> marks(c)
        }
        drawTarget(c)
    }

    private fun seg(c: Canvas, a: PointF, b: PointF, p: Paint = line) = c.drawLine(a.x, a.y, b.x, b.y, p)
    private fun power(c: Canvas, p: PointF, r: Float = 4f) = c.drawCircle(p.x, p.y, r * d, ring)

    private fun grid(c: Canvas, fs: FloatArray) {
        for (f in fs) {
            val x = frame.left + f * frame.width(); val y = frame.top + f * frame.height()
            c.drawLine(x, frame.top, x, frame.bottom, line); c.drawLine(frame.left, y, frame.right, y, line)
        }
        for (a in fs) for (b in fs) power(c, PointF(frame.left + a * frame.width(), frame.top + b * frame.height()))
    }

    private fun spiral(c: Canvas) {
        val s = Spiral.of(quadrant)
        val sx = frame.width() / s.bw; val sy = frame.height() / s.bh
        val eye = Geo.spiralEye(s, frame)
        path.reset()
        val p = s.pts
        path.moveTo(eye.x + p[0] * sx, eye.y + p[1] * sy)
        var i = 2
        while (i < p.size) { path.lineTo(eye.x + p[i] * sx, eye.y + p[i + 1] * sy); i += 2 }
        c.drawPath(path, curve)
        c.drawCircle(eye.x, eye.y, 11 * d, ring)
        c.drawCircle(eye.x, eye.y, 2.5f * d, dot)
    }

    private fun corners() = arrayOf(PointF(frame.left, frame.top), PointF(frame.right, frame.top), PointF(frame.right, frame.bottom), PointF(frame.left, frame.bottom))

    private fun triangles(c: Canvas) {
        val (tl, tr, br, bl) = corners()
        val a: PointF; val b: PointF; val c1: PointF; val c2: PointF
        if (!triFlip) { a = tl; b = br; c1 = tr; c2 = bl } else { a = tr; b = bl; c1 = tl; c2 = br }
        val f1 = Geo.footOf(a, b, c1); val f2 = Geo.footOf(a, b, c2)
        seg(c, a, b); seg(c, c1, f1); seg(c, c2, f2); power(c, f1); power(c, f2)
    }

    private fun diagonals(c: Canvas) {
        val (tl, tr, br, bl) = corners()
        for ((p, dir) in listOf(tl to (1f to 1f), tr to (-1f to 1f), bl to (1f to -1f), br to (-1f to -1f))) {
            val q = Geo.exit(p, dir.first, dir.second, frame); seg(c, p, q); power(c, q, 3.2f)
        }
    }

    private fun dynamic(c: Canvas) {
        val (tl, tr, br, bl) = corners()
        seg(c, tl, br); seg(c, tr, bl)
        fun recip(p: PointF, a: PointF, b: PointF) {
            val f = Geo.footOf(a, b, p)
            val dx = f.x - p.x; val dy = f.y - p.y; val n = kotlin.math.hypot(dx, dy)
            seg(c, p, Geo.exit(p, dx / n, dy / n, frame)); power(c, f)
        }
        recip(tl, tr, bl); recip(br, tr, bl); recip(tr, tl, br); recip(bl, tl, br)
    }

    private fun centre(c: Canvas) {
        val cx = frame.centerX(); val cy = frame.centerY()
        c.drawLine(cx, frame.top, cx, frame.bottom, line); c.drawLine(frame.left, cy, frame.right, cy, line)
        c.drawCircle(cx, cy, 18 * d, line); c.drawCircle(cx, cy, 2.5f * d, dot)
    }

    private fun marks(c: Canvas) {
        for (ar in floatArrayOf(1.25f, 1f)) {
            val h = frame.width() * ar
            if (h > frame.height()) continue
            val top = frame.top + (frame.height() - h) / 2
            c.drawRect(frame.left + 1, top, frame.right - 1, top + h, dashed)
        }
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
