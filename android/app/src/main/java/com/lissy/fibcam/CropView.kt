package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The photo, fixed and fitted, with a crop box over it. The box keeps its ratio, can be moved,
 * pinched or pulled by a corner, and tilts with the straighten dial. It never leaves the photo.
 * All crop numbers are in the displayed bitmap's pixels; export scales them to full resolution.
 */
class CropView(ctx: Context) : View(ctx) {
    var onChange: (() -> Unit)? = null

    var bmp: Bitmap? = null
        set(v) { field = v; if (width > 0) fit(); invalidate() }

    // crop: centre, width, ratio (w/h), tilt in radians
    var cx = 0f; var cy = 0f; var cw = 0f
    var aspect = (1 / PHI).toFloat()
    var theta = 0f
    var guide = Guide.SPIRAL
    var quadrant = 0
    var triFlip = false

    private val d = resources.displayMetrics.density
    private val painter = GuidePainter(d)
    private val dim = Paint().apply { color = 0xBD07080C.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * d; color = 0xE6FFFFFF.toInt() }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2FFFFFF.toInt() }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x8C0B0B0C.toInt() }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val m = Matrix()

    // fit of the bitmap on screen
    private var k = 1f; private var ox = 0f; private var oy = 0f

    val bw get() = bmp?.width?.toFloat() ?: 1f
    val bh get() = bmp?.height?.toFloat() ?: 1f
    val ch get() = cw / aspect

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private fun fit() {
        val b = bmp ?: return
        val pad = 20 * d
        k = min((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        ox = (width - b.width * k) / 2; oy = (height - b.height * k) / 2
    }

    private fun toScreen(p: PointF) = PointF(ox + p.x * k, oy + p.y * k)

    fun corners(x: Float = cx, y: Float = cy, w: Float = cw, t: Float = theta): Array<PointF> {
        val h = w / aspect; val c = cos(t); val s = sin(t)
        return arrayOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2)
            .map { (a, b) -> PointF(x + a * c - b * s, y + a * s + b * c) }.toTypedArray()
    }

    fun fits(x: Float = cx, y: Float = cy, w: Float = cw, t: Float = theta): Boolean =
        corners(x, y, w, t).all { it.x >= -0.5f && it.y >= -0.5f && it.x <= bw + 0.5f && it.y <= bh + 0.5f }

    /** Largest width that fits at the current centre and tilt. */
    fun maxWidth(x: Float = cx, y: Float = cy): Float {
        var lo = 0f; var hi = max(bw, bh) * 2
        repeat(30) { val mid = (lo + hi) / 2; if (fits(x, y, mid)) lo = mid else hi = mid }
        return lo
    }

    /** Biggest crop of the current ratio, centred. */
    fun reset() { cx = bw / 2; cy = bh / 2; cw = maxWidth(); changed() }

    /** After a ratio or tilt change: keep the centre if possible, otherwise come back toward the middle. */
    fun refit() {
        if (!fits()) {
            var mw = maxWidth()
            if (mw < min(bw, bh) * 0.25f) { cx = bw / 2; cy = bh / 2; mw = maxWidth() }
            cw = min(cw, mw)
        }
        changed()
    }

    private fun changed() { invalidate(); onChange?.invoke() }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (k <= 0f || width == 0) return
        m.reset(); m.postScale(k, k); m.postTranslate(ox, oy)
        c.drawBitmap(b, m, bmpPaint)
        val q = corners().map { toScreen(it) }
        // dim everything outside the crop
        path.reset(); path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.moveTo(q[0].x, q[0].y); for (i in 1..3) path.lineTo(q[i].x, q[i].y); path.close()
        c.drawPath(path, dim)
        path.reset(); path.moveTo(q[0].x, q[0].y); for (i in 1..3) path.lineTo(q[i].x, q[i].y); path.close()
        c.drawPath(path, edge)
        // guide, in the crop's own (tilted) frame
        val sc = toScreen(PointF(cx, cy)); val w = cw * k; val h = ch * k
        c.save(); c.translate(sc.x, sc.y); c.rotate(Math.toDegrees(theta.toDouble()).toFloat())
        c.clipRect(-w / 2, -h / 2, w / 2, h / 2)
        painter.draw(c, RectF(-w / 2, -h / 2, w / 2, h / 2), guide, quadrant, triFlip, trueSpiral = true)
        c.restore()
        for (p in q) { c.drawCircle(p.x, p.y, 6 * d, handle); c.drawCircle(p.x, p.y, 6 * d, handleEdge) }
    }

    // ---------------------------------------------------------------- touch
    private enum class Mode { NONE, MOVE, CORNER }
    private var mode = Mode.NONE
    private var lx = 0f; private var ly = 0f

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(det: ScaleGestureDetector): Boolean { setWidth(cw * det.scaleFactor); return true }
    })

    private fun setWidth(w: Float) {
        val minW = min(bw, bh) * 0.08f
        cw = w.coerceIn(minW, max(minW, maxWidth())); changed()
    }

    private fun inside(sx: Float, sy: Float): Boolean {
        val px = (sx - ox) / k - cx; val py = (sy - oy) / k - cy
        val c = cos(-theta); val s = sin(-theta)
        val lx = px * c - py * s; val ly = px * s + py * c
        return abs(lx) <= cw / 2 && abs(ly) <= ch / 2
    }

    private fun tryMove(dx: Float, dy: Float) {
        when {
            fits(cx + dx, cy + dy) -> { cx += dx; cy += dy }
            fits(cx + dx, cy) -> cx += dx
            fits(cx, cy + dy) -> cy += dy
            else -> {
                var lo = 0f; var hi = 1f
                repeat(16) { val t = (lo + hi) / 2; if (fits(cx + dx * t, cy + dy * t)) lo = t else hi = t }
                cx += dx * lo; cy += dy * lo
            }
        }
        changed()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lx = e.x; ly = e.y
                val near = corners().map { toScreen(it) }.any { hypot(it.x - e.x, it.y - e.y) < 30 * d }
                mode = when { near -> Mode.CORNER; inside(e.x, e.y) -> Mode.MOVE; else -> Mode.NONE }
            }
            MotionEvent.ACTION_POINTER_DOWN -> mode = Mode.NONE
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                when (mode) {
                    Mode.MOVE -> tryMove((e.x - lx) / k, (e.y - ly) / k)
                    Mode.CORNER -> {
                        val sc = toScreen(PointF(cx, cy))
                        val before = hypot(lx - sc.x, ly - sc.y); val after = hypot(e.x - sc.x, e.y - sc.y)
                        if (before > 1f) setWidth(cw * after / before)
                    }
                    Mode.NONE -> {}
                }
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return true
    }
}
