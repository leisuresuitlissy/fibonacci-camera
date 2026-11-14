package com.lissy.fibcam

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val PHI = 1.6180339887498949

enum class Guide(val label: String) {
    OFF("OFF"), THIRDS("THIRDS"), PHI_GRID("φ GRID"), SPIRAL("SPIRAL"), TRIANGLES("TRIANGLES"),
    DIAGONALS("DIAGONALS"), DYNAMIC("DYNAMIC"), CENTRE("CENTRE"), MARKS("4:5 · 1:1")
}

/**
 * A golden spiral with its eye at the origin, longest box side normalised to 1.
 * Orientation 0..3 = eye top-left, top-right, bottom-left, bottom-right; the long side
 * is always vertical, so it always matches a portrait frame.
 */
class Spiral(val pts: FloatArray, val bw: Float, val bh: Float, val ox: Float, val oy: Float) {
    /** Where the eye sits inside the box, as a fraction of the box. */
    val eyeFx get() = -ox / bw
    val eyeFy get() = -oy / bh

    companion object {
        private val cache = HashMap<Int, Spiral>()
        /** [wide] lays the long side horizontally, for landscape crops. */
        fun of(quadrant: Int, wide: Boolean = false, turns: Double = 2.25): Spiral =
            cache.getOrPut(quadrant + if (wide) 4 else 0) { build(quadrant, wide, turns) }

        private fun build(quadrant: Int, wide: Boolean, turns: Double): Spiral {
            val b = ln(PHI) / (PI / 2)
            val n = 600
            val xs = DoubleArray(n + 1); val ys = DoubleArray(n + 1)
            val flipX = quadrant == 1 || quadrant == 3
            val flipY = quadrant == 2 || quadrant == 3
            for (i in 0..n) {
                val th = turns * 2 * PI * i / n
                val r = exp(b * th)
                // at 2.25 turns this box is 0.639 wide by 1 tall: long side vertical
                var x = r * cos(th); var y = r * sin(th)
                if (wide) { val t = x; x = y; y = t }
                if (flipX) x = -x
                if (flipY) y = -y
                xs[i] = x; ys[i] = y
            }
            val ex = xs[0]; val ey = ys[0]
            val mnx = xs.min(); val mxx = xs.max(); val mny = ys.min(); val mxy = ys.max()
            val bw = mxx - mnx; val bh = mxy - mny
            val k = 1.0 / max(bw, bh)
            val pts = FloatArray((n + 1) * 2)
            for (i in 0..n) { pts[i * 2] = ((xs[i] - ex) * k).toFloat(); pts[i * 2 + 1] = ((ys[i] - ey) * k).toFloat() }
            val s = Spiral(pts, (bw * k).toFloat(), (bh * k).toFloat(), ((mnx - ex) * k).toFloat(), ((mny - ey) * k).toFloat())
            // Normalise so the eye lands in the named quadrant whatever the trig did.
            val wantLeft = !flipX; val wantTop = !flipY
            val fixX = (s.eyeFx < 0.5f) != wantLeft
            val fixY = (s.eyeFy < 0.5f) != wantTop
            if (!fixX && !fixY) return s
            for (i in 0..n) { if (fixX) pts[i * 2] = -pts[i * 2]; if (fixY) pts[i * 2 + 1] = -pts[i * 2 + 1] }
            val nox = if (fixX) -(s.ox + s.bw) else s.ox
            val noy = if (fixY) -(s.oy + s.bh) else s.oy
            return Spiral(pts, s.bw, s.bh, nox, noy)
        }
    }
}

object Geo {
    fun footOf(a: PointF, b: PointF, p: PointF): PointF {
        val vx = b.x - a.x; val vy = b.y - a.y
        val t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / (vx * vx + vy * vy)
        return PointF(a.x + vx * t, a.y + vy * t)
    }

    /** Where a ray from p in direction d leaves the frame. */
    fun exit(p: PointF, dx: Float, dy: Float, fr: RectF): PointF {
        var t = Float.MAX_VALUE
        if (dx > 0) t = min(t, (fr.right - p.x) / dx) else if (dx < 0) t = min(t, (fr.left - p.x) / dx)
        if (dy > 0) t = min(t, (fr.bottom - p.y) / dy) else if (dy < 0) t = min(t, (fr.top - p.y) / dy)
        return PointF(p.x + dx * t, p.y + dy * t)
    }

    /** The spiral stretched so its box is exactly the frame. Returns the eye in screen px. */
    fun spiralEye(s: Spiral, fr: RectF): PointF {
        val sx = fr.width() / s.bw; val sy = fr.height() / s.bh
        return PointF(fr.left - s.ox * sx, fr.top - s.oy * sy)
    }

    /**
     * Shoot mode: the true golden spiral, unstretched, spanning the frame top to bottom.
     * [align] 0 = left, 1 = centre, 2 = right. Returns the eye, the scale and the box's left edge.
     */
    fun shootSpiral(s: Spiral, fr: RectF, align: Int): Triple<PointF, Float, Float> {
        val u = fr.height() / s.bh
        val boxW = s.bw * u
        val left = when (align) { 0 -> fr.left; 2 -> fr.right - boxW; else -> fr.left + (fr.width() - boxW) / 2 }
        return Triple(PointF(left - s.ox * u, fr.top - s.oy * u), u, left)
    }

    /** The true spiral (no stretching) at the largest size that fits inside the frame, centred. */
    fun phiEye(s: Spiral, fr: RectF): Pair<PointF, Float> {
        val u = min(fr.width() / s.bw, fr.height() / s.bh)
        val left = fr.left + (fr.width() - s.bw * u) / 2; val top = fr.top + (fr.height() - s.bh * u) / 2
        return PointF(left - s.ox * u, top - s.oy * u) to u
    }

    /** Points the subject should land on for a guide, in screen px. */
    fun goals(guide: Guide, fr: RectF, quadrant: Int, triFlip: Boolean, align: Int = 1): List<PointF> {
        val tl = PointF(fr.left, fr.top); val tr = PointF(fr.right, fr.top)
        val br = PointF(fr.right, fr.bottom); val bl = PointF(fr.left, fr.bottom)
        fun grid(fs: FloatArray) = fs.flatMap { a -> fs.map { b -> PointF(fr.left + a * fr.width(), fr.top + b * fr.height()) } }
        return when (guide) {
            Guide.SPIRAL -> listOf(shootSpiral(Spiral.of(quadrant), fr, align).first)
            Guide.THIRDS -> grid(floatArrayOf(1 / 3f, 2 / 3f))
            Guide.PHI_GRID -> grid(floatArrayOf((1 - 1 / PHI).toFloat(), (1 / PHI).toFloat()))
            Guide.TRIANGLES -> if (!triFlip) listOf(footOf(tl, br, tr), footOf(tl, br, bl)) else listOf(footOf(tr, bl, tl), footOf(tr, bl, br))
            Guide.DYNAMIC -> listOf(footOf(tr, bl, tl), footOf(tr, bl, br), footOf(tl, br, tr), footOf(tl, br, bl))
            Guide.CENTRE -> listOf(PointF(fr.centerX(), fr.centerY()))
            else -> emptyList()
        }
    }

    fun dist(a: PointF, b: PointF) = hypot(a.x - b.x, a.y - b.y)

    /** Degrees off level from a gravity vector, portrait or landscape; null when pointing at floor/sky. */
    fun levelOff(gx: Float, gy: Float, gz: Float): Float? {
        if (abs(gz) > 7f) return null
        val portrait = abs(gy) >= abs(gx)
        var dev = Math.toDegrees(kotlin.math.atan2(if (portrait) gx else gy, if (portrait) gy else gx).toDouble()).toFloat()
        dev = abs(dev); if (dev > 90f) dev = 180f - dev
        return dev
    }
}
