package com.lissy.fibcam

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot

/**
 * Draws any guide into a rectangle, in the Figma guide style: Ivory lines at one shared weight
 * ([GUIDE_STROKE_DP], curves included), small Amber marker rings on the points that matter.
 * The stroke is set in screen pixels, so scaling or rotating the geometry never thickens it.
 * The viewfinder uses the true golden spiral spanning the frame's height; the Crop screen fits it in the box.
 */
class GuidePainter(private val d: Float) {
    companion object {
        /** The one line weight every viewfinder guide uses (Figma: 1). */
        const val GUIDE_STROKE_DP = 1f
        private const val MARKER_STROKE_DP = 1.15f
        private const val MARKER_RADIUS_DP = 2.4f
        private const val IVORY = 0xFFF4EDE1.toInt()
        private const val MARKER = 0xFFE9B957.toInt()
    }

    /** 0..1, applied to every guide type alike (Settings › Guide opacity). */
    var opacity = 0.75f
        set(v) { field = v.coerceIn(0f, 1f); applyAlpha() }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = GUIDE_STROKE_DP * d; color = IVORY; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val dashed = Paint(line).apply { pathEffect = DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = MARKER_STROKE_DP * d; color = MARKER }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = MARKER }
    private val path = Path()

    init { applyAlpha() }
    private fun applyAlpha() {
        val a = (255 * opacity).toInt()
        line.alpha = a; dashed.alpha = a; ring.alpha = a; dot.alpha = a
    }

    /** [trueSpiral]: Crop screen (largest fit inside the box). Otherwise the viewfinder (full height, [align]ed). */
    fun draw(c: Canvas, fr: RectF, guide: Guide, quadrant: Int, triFlip: Boolean, trueSpiral: Boolean, align: Int = 1) {
        when (guide) {
            Guide.OFF -> {}
            Guide.THIRDS -> grid(c, fr, floatArrayOf(1 / 3f, 2 / 3f), 2f)
            Guide.PHI_GRID -> grid(c, fr, floatArrayOf((1 - 1 / PHI).toFloat(), (1 / PHI).toFloat()), 2f)
            Guide.SPIRAL -> spiral(c, fr, quadrant, trueSpiral, align)
            Guide.TRIANGLES -> triangles(c, fr, triFlip)
            Guide.DIAGONALS -> diagonals(c, fr)
            Guide.DYNAMIC -> dynamic(c, fr)
            Guide.CENTRE -> centre(c, fr)
            Guide.MARKS -> marks(c, fr)
        }
    }

    private fun seg(c: Canvas, a: PointF, b: PointF) = c.drawLine(a.x, a.y, b.x, b.y, line)
    private fun marker(c: Canvas, p: PointF, r: Float = MARKER_RADIUS_DP) = c.drawCircle(p.x, p.y, r * d, ring)
    private fun corners(fr: RectF) = arrayOf(PointF(fr.left, fr.top), PointF(fr.right, fr.top), PointF(fr.right, fr.bottom), PointF(fr.left, fr.bottom))

    private fun grid(c: Canvas, fr: RectF, fs: FloatArray, markerR: Float) {
        for (f in fs) {
            val x = fr.left + f * fr.width(); val y = fr.top + f * fr.height()
            c.drawLine(x, fr.top, x, fr.bottom, line); c.drawLine(fr.left, y, fr.right, y, line)
        }
        for (a in fs) for (b in fs) marker(c, PointF(fr.left + a * fr.width(), fr.top + b * fr.height()), markerR)
    }

    private fun spiral(c: Canvas, fr: RectF, quadrant: Int, trueSpiral: Boolean, align: Int) {
        val s = Spiral.of(quadrant, wide = trueSpiral && fr.width() > fr.height())
        // one scale for both axes: the golden-ratio geometry is never stretched
        val (eye, u) = if (trueSpiral) Geo.phiEye(s, fr) else Geo.shootSpiral(s, fr, align).let { it.first to it.second }
        path.reset()
        val p = s.pts
        path.moveTo(eye.x + p[0] * u, eye.y + p[1] * u)
        var i = 2
        while (i < p.size) { path.lineTo(eye.x + p[i] * u, eye.y + p[i + 1] * u); i += 2 }
        c.drawPath(path, line)
        marker(c, eye)
    }

    private fun triangles(c: Canvas, fr: RectF, triFlip: Boolean) {
        val (tl, tr, br, bl) = corners(fr)
        val a: PointF; val b: PointF; val c1: PointF; val c2: PointF
        if (!triFlip) { a = tl; b = br; c1 = tr; c2 = bl } else { a = tr; b = bl; c1 = tl; c2 = br }
        val f1 = Geo.footOf(a, b, c1); val f2 = Geo.footOf(a, b, c2)
        seg(c, a, b); seg(c, c1, f1); seg(c, c2, f2); marker(c, f1); marker(c, f2)
    }

    private fun diagonals(c: Canvas, fr: RectF) {
        val (tl, tr, br, bl) = corners(fr)
        for ((p, dir) in listOf(tl to (1f to 1f), tr to (-1f to 1f), bl to (1f to -1f), br to (-1f to -1f))) {
            val q = Geo.exit(p, dir.first, dir.second, fr); seg(c, p, q); marker(c, q)
        }
    }

    /** Both diagonals and the four reciprocals: symmetric, so it has no orientations. */
    private fun dynamic(c: Canvas, fr: RectF) {
        val (tl, tr, br, bl) = corners(fr)
        seg(c, tl, br); seg(c, tr, bl)
        fun recip(p: PointF, a: PointF, b: PointF) {
            val f = Geo.footOf(a, b, p)
            val dx = f.x - p.x; val dy = f.y - p.y; val n = hypot(dx, dy)
            seg(c, p, Geo.exit(p, dx / n, dy / n, fr)); marker(c, f)
        }
        recip(tl, tr, bl); recip(br, tr, bl); recip(tr, tl, br); recip(bl, tl, br)
    }

    private fun centre(c: Canvas, fr: RectF) {
        val cx = fr.centerX(); val cy = fr.centerY()
        c.drawLine(cx, fr.top, cx, fr.bottom, line); c.drawLine(fr.left, cy, fr.right, cy, line)
        ring.color = IVORY; ring.alpha = line.alpha
        c.drawCircle(cx, cy, 13.5f * d, ring)
        ring.color = MARKER; ring.alpha = line.alpha
        c.drawCircle(cx, cy, 1.8f * d, dot)
    }

    /** The 4:5 crop as a dashed box, and the 1:1 crop's top and bottom edges. */
    private fun marks(c: Canvas, fr: RectF) {
        val h45 = fr.width() * 1.25f
        if (h45 <= fr.height() + 0.5f) {
            val top = fr.top + (fr.height() - h45) / 2
            path.reset(); path.addRect(fr.left, top, fr.right, top + h45, Path.Direction.CW); c.drawPath(path, dashed)
        }
        val h11 = fr.width()
        if (h11 <= fr.height()) {
            val top = fr.top + (fr.height() - h11) / 2
            c.drawLine(fr.left, top, fr.right, top, dashed); c.drawLine(fr.left, top + h11, fr.right, top + h11, dashed)
        }
    }
}
