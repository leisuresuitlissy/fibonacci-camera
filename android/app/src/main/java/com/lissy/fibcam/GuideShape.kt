package com.lissy.fibcam

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/*
 * The one definition of every composition guide: what is drawn, and the focal anchors Follow guide
 * can use. GuidePainter draws exactly these lines and markers; Focus takes its sharp region from exactly
 * these anchors; Coach aims at them. Plain Kotlin (no Android types), so the geometry is unit-tested.
 *
 * Everything is in the pixels of the box it is built on (the visible camera image, or Holga's square
 * inside it). Rotating the spiral, mirroring the triangles or changing the box moves the drawing and
 * the anchors together, because both come out of the same calculation.
 */

data class Pt(val x: Float, val y: Float) {
    fun dist(o: Pt) = hypot(x - o.x, y - o.y)
}

data class Box(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    val cx get() = (l + r) / 2
    val cy get() = (t + b) / 2
    val tl get() = Pt(l, t)
    val tr get() = Pt(r, t)
    val bl get() = Pt(l, b)
    val br get() = Pt(r, b)
}

class Seg(val a: Pt, val b: Pt, val dashed: Boolean = false)

/** A marker: [RING] small Amber ring (power points), [BIG_RING] the Ivory ring at the centre, [DOT] a filled Amber dot. */
class Marker(val p: Pt, val radiusDp: Float, val kind: Int) {
    companion object { const val RING = 0; const val BIG_RING = 1; const val DOT = 2 }
}

/**
 * A place the sharp region can sit. [angle] (radians, screen space) is the guide line running through it
 * when that line is unambiguous (the triangles' diagonal, the dynamic diagonal it lies on); null where
 * two lines cross or there is no line (the spiral's eye, a thirds intersection, the centre).
 */
class Anchor(val p: Pt, val angle: Float? = null)

class Shape(val segs: List<Seg>, val curve: List<Pt>, val markers: List<Marker>, val anchors: List<Anchor>)

object GuideShape {
    private val EMPTY = Shape(emptyList(), emptyList(), emptyList(), emptyList())

    /** Whether Follow guide has anything to follow for [g]: Off and the crop marks have no focal point. */
    fun hasAnchors(g: Guide) = g != Guide.OFF && g != Guide.MARKS

    fun foot(a: Pt, b: Pt, p: Pt): Pt {
        val vx = b.x - a.x; val vy = b.y - a.y
        val t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / (vx * vx + vy * vy)
        return Pt(a.x + vx * t, a.y + vy * t)
    }

    /** Where a ray from [p] in direction ([dx], [dy]) leaves the box. */
    fun exit(p: Pt, dx: Float, dy: Float, f: Box): Pt {
        var t = Float.MAX_VALUE
        if (dx > 0) t = min(t, (f.r - p.x) / dx) else if (dx < 0) t = min(t, (f.l - p.x) / dx)
        if (dy > 0) t = min(t, (f.b - p.y) / dy) else if (dy < 0) t = min(t, (f.t - p.y) / dy)
        return Pt(p.x + dx * t, p.y + dy * t)
    }

    /** Where two segments cross, or null if they don't (within a small tolerance). */
    fun cross(s1: Seg, s2: Seg): Pt? {
        val d1x = s1.b.x - s1.a.x; val d1y = s1.b.y - s1.a.y
        val d2x = s2.b.x - s2.a.x; val d2y = s2.b.y - s2.a.y
        val den = d1x * d2y - d1y * d2x
        if (abs(den) < 1e-6f) return null
        val ex = s2.a.x - s1.a.x; val ey = s2.a.y - s1.a.y
        val t = (ex * d2y - ey * d2x) / den
        val u = (ex * d1y - ey * d1x) / den
        val e = 1e-4f
        if (t < -e || t > 1 + e || u < -e || u > 1 + e) return null
        return Pt(s1.a.x + d1x * t, s1.a.y + d1y * t)
    }

    private fun ang(a: Pt, b: Pt) = atan2(b.y - a.y, b.x - a.x)

    /**
     * [trueSpiral]: the Crop screen's spiral, the largest that fits the box. Otherwise the viewfinder's,
     * spanning the box's height, [align]ed (0 left, 1 centre, 2 right).
     */
    fun build(guide: Guide, f: Box, quadrant: Int, triFlip: Boolean, trueSpiral: Boolean = false, align: Int = 1): Shape {
        if (f.w <= 0f || f.h <= 0f) return EMPTY
        return when (guide) {
            Guide.OFF -> EMPTY
            Guide.THIRDS -> grid(f, 1 / 3f)
            Guide.PHI_GRID -> grid(f, (1 - 1 / PHI).toFloat())
            Guide.SPIRAL -> spiral(f, quadrant, trueSpiral, align)
            Guide.TRIANGLES -> triangles(f, triFlip)
            Guide.DIAGONALS -> diagonals(f)
            Guide.DYNAMIC -> dynamic(f)
            Guide.CENTRE -> centre(f)
            Guide.MARKS -> marks(f)
        }
    }

    /** Two lines each way at [a] and 1−[a]; anchors at the four intersections: TL, TR, BL, BR. */
    private fun grid(f: Box, a: Float): Shape {
        val fs = floatArrayOf(a, 1 - a)
        val segs = ArrayList<Seg>()
        for (k in fs) {
            val x = f.l + k * f.w; val y = f.t + k * f.h
            segs.add(Seg(Pt(x, f.t), Pt(x, f.b))); segs.add(Seg(Pt(f.l, y), Pt(f.r, y)))
        }
        val pts = listOf(Pt(f.l + a * f.w, f.t + a * f.h), Pt(f.r - a * f.w, f.t + a * f.h), Pt(f.l + a * f.w, f.b - a * f.h), Pt(f.r - a * f.w, f.b - a * f.h))
        return Shape(segs, emptyList(), pts.map { Marker(it, 2f, Marker.RING) }, pts.map { Anchor(it) })
    }

    /** The golden spiral; one anchor, its eye, the point the curve converges on. */
    private fun spiral(f: Box, quadrant: Int, trueSpiral: Boolean, align: Int): Shape {
        val s = Spiral.of(quadrant, wide = trueSpiral && f.w > f.h)
        // one scale for both axes: the golden-ratio geometry is never stretched
        val eye: Pt; val u: Float
        if (trueSpiral) {
            u = min(f.w / s.bw, f.h / s.bh)
            val left = f.l + (f.w - s.bw * u) / 2; val top = f.t + (f.h - s.bh * u) / 2
            eye = Pt(left - s.ox * u, top - s.oy * u)
        } else {
            u = f.h / s.bh
            val boxW = s.bw * u
            val left = when (align) { 0 -> f.l; 2 -> f.r - boxW; else -> f.l + (f.w - boxW) / 2 }
            eye = Pt(left - s.ox * u, f.t - s.oy * u)
        }
        val p = s.pts
        val curve = ArrayList<Pt>(p.size / 2)
        var i = 0
        while (i < p.size) { curve.add(Pt(eye.x + p[i] * u, eye.y + p[i + 1] * u)); i += 2 }
        return Shape(emptyList(), curve, listOf(Marker(eye, 2.4f, Marker.RING)), listOf(Anchor(eye)))
    }

    /**
     * One diagonal with a perpendicular to it from each remaining corner. A (unflipped) runs top-left to
     * bottom-right. The anchors are where the perpendiculars meet the diagonal; the diagonal is their line.
     */
    private fun triangles(f: Box, flip: Boolean): Shape {
        val a: Pt; val b: Pt; val c1: Pt; val c2: Pt
        if (!flip) { a = f.tl; b = f.br; c1 = f.tr; c2 = f.bl } else { a = f.tr; b = f.bl; c1 = f.tl; c2 = f.br }
        val f1 = foot(a, b, c1); val f2 = foot(a, b, c2)
        val dir = ang(a, b)
        return Shape(listOf(Seg(a, b), Seg(c1, f1), Seg(c2, f2)), emptyList(),
            listOf(Marker(f1, 2.4f, Marker.RING), Marker(f2, 2.4f, Marker.RING)), listOf(Anchor(f1, dir), Anchor(f2, dir)))
    }

    /**
     * 45° lines from each corner, marked where they reach the sides (as designed). Those edge marks sit on
     * the frame's border, so they make poor focal points; the anchors are where the corner lines cross each
     * other inside the frame (two on a portrait or landscape frame, one at the centre of a square).
     */
    private fun diagonals(f: Box): Shape {
        val segs = ArrayList<Seg>(); val marks = ArrayList<Marker>()
        for ((p, d) in listOf(f.tl to (1f to 1f), f.tr to (-1f to 1f), f.bl to (1f to -1f), f.br to (-1f to -1f))) {
            val q = exit(p, d.first, d.second, f); segs.add(Seg(p, q)); marks.add(Marker(q, 2.4f, Marker.RING))
        }
        val crossings = ArrayList<Pt>()
        for (i in segs.indices) for (j in i + 1 until segs.size) {
            val c = cross(segs[i], segs[j]) ?: continue
            // a crossing at a shared end (a corner, or where two lines meet on the border) is not a focal point
            val onEdge = abs(c.x - f.l) < 0.5f || abs(c.x - f.r) < 0.5f || abs(c.y - f.t) < 0.5f || abs(c.y - f.b) < 0.5f
            if (!onEdge && crossings.none { it.dist(c) < 0.5f }) crossings.add(c)
        }
        crossings.sortWith(compareBy({ it.y }, { it.x }))
        return Shape(segs, emptyList(), marks, crossings.map { Anchor(it) })
    }

    /**
     * Both diagonals and the four reciprocals (from each corner, perpendicular to the other diagonal).
     * The anchors are where each reciprocal meets the diagonal it is perpendicular to (the marked points);
     * each lies on one main diagonal, which is its line. Order: TL, BR (on TR–BL), TR, BL (on TL–BR).
     */
    private fun dynamic(f: Box): Shape {
        val segs = arrayListOf(Seg(f.tl, f.br), Seg(f.tr, f.bl))
        val marks = ArrayList<Marker>(); val anchors = ArrayList<Anchor>()
        fun recip(p: Pt, a: Pt, b: Pt) {
            val ft = foot(a, b, p)
            val dx = ft.x - p.x; val dy = ft.y - p.y; val n = hypot(dx, dy)
            segs.add(Seg(p, exit(p, dx / n, dy / n, f)))
            marks.add(Marker(ft, 2.4f, Marker.RING)); anchors.add(Anchor(ft, ang(a, b)))
        }
        recip(f.tl, f.tr, f.bl); recip(f.br, f.tr, f.bl); recip(f.tr, f.tl, f.br); recip(f.bl, f.tl, f.br)
        return Shape(segs, emptyList(), marks, anchors)
    }

    private fun centre(f: Box): Shape {
        val c = Pt(f.cx, f.cy)
        return Shape(listOf(Seg(Pt(f.cx, f.t), Pt(f.cx, f.b)), Seg(Pt(f.l, f.cy), Pt(f.r, f.cy))), emptyList(),
            listOf(Marker(c, 13.5f, Marker.BIG_RING), Marker(c, 1.8f, Marker.DOT)), listOf(Anchor(c)))
    }

    /** The 4:5 crop as a dashed box and the 1:1 crop's top and bottom edges. Boundaries only: no anchors. */
    private fun marks(f: Box): Shape {
        val segs = ArrayList<Seg>()
        val h45 = f.w * 1.25f
        if (h45 <= f.h + 0.5f) {
            val t = f.t + (f.h - h45) / 2; val b = t + h45
            segs.add(Seg(Pt(f.l, t), Pt(f.r, t), true)); segs.add(Seg(Pt(f.r, t), Pt(f.r, b), true))
            segs.add(Seg(Pt(f.r, b), Pt(f.l, b), true)); segs.add(Seg(Pt(f.l, b), Pt(f.l, t), true))
        }
        val h11 = f.w
        if (h11 <= f.h) {
            val t = f.t + (f.h - h11) / 2
            segs.add(Seg(Pt(f.l, t), Pt(f.r, t), true)); segs.add(Seg(Pt(f.l, t + h11), Pt(f.r, t + h11), true))
        }
        return Shape(segs, emptyList(), emptyList(), emptyList())
    }

    /** The anchor index nearest [p], for tap-to-select. */
    fun nearest(shape: Shape, p: Pt): Int = shape.anchors.indices.minByOrNull { shape.anchors[it].p.dist(p) } ?: -1

    /** Whether [p] lies on something drawn: a segment, the curve, or a marker. Used by the geometry tests. */
    fun onDrawing(shape: Shape, p: Pt, tol: Float = 0.75f): Boolean {
        if (shape.markers.any { it.p.dist(p) < tol }) return true
        for (s in shape.segs) {
            val ft = foot(s.a, s.b, p)
            val within = (ft.x - minOf(s.a.x, s.b.x)) >= -tol && (ft.x - maxOf(s.a.x, s.b.x)) <= tol &&
                (ft.y - minOf(s.a.y, s.b.y)) >= -tol && (ft.y - maxOf(s.a.y, s.b.y)) <= tol
            if (within && ft.dist(p) < tol) return true
        }
        return shape.curve.any { it.dist(p) < tol }
    }
}
