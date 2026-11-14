package com.lissy.fibcam

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.hypot

/**
 * Draws any guide into a rectangle. The viewfinder uses it with the spiral stretched to the
 * frame; the Crop screen uses it with the true, unstretched spiral inside the crop box.
 */
class GuidePainter(private val d: Float) {
    private val gold = 0xFFE0B252.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = white; alpha = 97 }
    private val curve = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.4f * d; color = white; alpha = 220; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = gold }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = gold }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = white; alpha = 150; pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f) }
    private val path = Path()

    fun draw(c: Canvas, fr: RectF, guide: Guide, quadrant: Int, triFlip: Boolean, trueSpiral: Boolean) {
        when (guide) {
            Guide.OFF -> {}
            Guide.THIRDS -> grid(c, fr, floatArrayOf(1 / 3f, 2 / 3f))
            Guide.PHI_GRID -> grid(c, fr, floatArrayOf((1 - 1 / PHI).toFloat(), (1 / PHI).toFloat()))
            Guide.SPIRAL -> spiral(c, fr, quadrant, trueSpiral)
            Guide.TRIANGLES -> triangles(c, fr, triFlip)
            Guide.DIAGONALS -> diagonals(c, fr)
            Guide.DYNAMIC -> dynamic(c, fr)
            Guide.CENTRE -> centre(c, fr)
            Guide.MARKS -> marks(c, fr)
        }
    }

    private fun seg(c: Canvas, a: PointF, b: PointF) = c.drawLine(a.x, a.y, b.x, b.y, line)
    private fun power(c: Canvas, p: PointF, r: Float = 4f) = c.drawCircle(p.x, p.y, r * d, ring)
    private fun corners(fr: RectF) = arrayOf(PointF(fr.left, fr.top), PointF(fr.right, fr.top), PointF(fr.right, fr.bottom), PointF(fr.left, fr.bottom))

    private fun grid(c: Canvas, fr: RectF, fs: FloatArray) {
        for (f in fs) {
            val x = fr.left + f * fr.width(); val y = fr.top + f * fr.height()
            c.drawLine(x, fr.top, x, fr.bottom, line); c.drawLine(fr.left, y, fr.right, y, line)
        }
        for (a in fs) for (b in fs) power(c, PointF(fr.left + a * fr.width(), fr.top + b * fr.height()))
    }

    private fun spiral(c: Canvas, fr: RectF, quadrant: Int, trueSpiral: Boolean) {
        val s = Spiral.of(quadrant, wide = trueSpiral && fr.width() > fr.height())
        val eye: PointF; val sx: Float; val sy: Float
        if (trueSpiral) { val (e, u) = Geo.phiEye(s, fr); eye = e; sx = u; sy = u }
        else { eye = Geo.spiralEye(s, fr); sx = fr.width() / s.bw; sy = fr.height() / s.bh }
        path.reset()
        val p = s.pts
        path.moveTo(eye.x + p[0] * sx, eye.y + p[1] * sy)
        var i = 2
        while (i < p.size) { path.lineTo(eye.x + p[i] * sx, eye.y + p[i + 1] * sy); i += 2 }
        c.drawPath(path, curve)
        c.drawCircle(eye.x, eye.y, 11 * d, ring)
        c.drawCircle(eye.x, eye.y, 2.5f * d, dot)
    }

    private fun triangles(c: Canvas, fr: RectF, triFlip: Boolean) {
        val (tl, tr, br, bl) = corners(fr)
        val a: PointF; val b: PointF; val c1: PointF; val c2: PointF
        if (!triFlip) { a = tl; b = br; c1 = tr; c2 = bl } else { a = tr; b = bl; c1 = tl; c2 = br }
        val f1 = Geo.footOf(a, b, c1); val f2 = Geo.footOf(a, b, c2)
        seg(c, a, b); seg(c, c1, f1); seg(c, c2, f2); power(c, f1); power(c, f2)
    }

    private fun diagonals(c: Canvas, fr: RectF) {
        val (tl, tr, br, bl) = corners(fr)
        for ((p, dir) in listOf(tl to (1f to 1f), tr to (-1f to 1f), bl to (1f to -1f), br to (-1f to -1f))) {
            val q = Geo.exit(p, dir.first, dir.second, fr); seg(c, p, q); power(c, q, 3.2f)
        }
    }

    private fun dynamic(c: Canvas, fr: RectF) {
        val (tl, tr, br, bl) = corners(fr)
        seg(c, tl, br); seg(c, tr, bl)
        fun recip(p: PointF, a: PointF, b: PointF) {
            val f = Geo.footOf(a, b, p)
            val dx = f.x - p.x; val dy = f.y - p.y; val n = hypot(dx, dy)
            seg(c, p, Geo.exit(p, dx / n, dy / n, fr)); power(c, f)
        }
        recip(tl, tr, bl); recip(br, tr, bl); recip(tr, tl, br); recip(bl, tl, br)
    }

    private fun centre(c: Canvas, fr: RectF) {
        val cx = fr.centerX(); val cy = fr.centerY()
        c.drawLine(cx, fr.top, cx, fr.bottom, line); c.drawLine(fr.left, cy, fr.right, cy, line)
        c.drawCircle(cx, cy, 18 * d, line); c.drawCircle(cx, cy, 2.5f * d, dot)
    }

    private fun marks(c: Canvas, fr: RectF) {
        for (ar in floatArrayOf(1.25f, 1f)) {
            val h = fr.width() * ar
            if (h > fr.height()) continue
            val top = fr.top + (fr.height() - h) / 2
            c.drawRect(fr.left + 1, top, fr.right - 1, top + h, dashed)
        }
    }
}
