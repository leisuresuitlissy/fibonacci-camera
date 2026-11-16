package com.lissy.fibcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Proves the Follow guide targets match the guides as drawn: every anchor sits on the drawing, inside the
 * box, and moves correctly when the guide is rotated or mirrored or the box changes shape.
 */
class GuideShapeTest {
    // the reference phone's viewfinder (384 × 512 dp at 2.8125 px/dp), offset like the real screen;
    // Holga's square inside it; and the same frame turned landscape
    private val portrait = Box(0f, 112.5f, 1080f, 1552.5f)
    private val square = Box(0f, 292.5f, 1080f, 1372.5f)
    private val landscape = Box(40f, 60f, 1480f, 1140f)
    private val boxes = listOf(portrait, square, landscape)
    private val guides = Guide.values().filter { GuideShape.hasAnchors(it) }

    private fun near(a: Float, b: Float, tol: Float = 0.5f) = abs(a - b) < tol
    private fun assertPt(msg: String, want: Pt, got: Pt, tol: Float = 0.5f) =
        assertTrue("$msg: want (${want.x}, ${want.y}) got (${got.x}, ${got.y})", near(want.x, got.x, tol) && near(want.y, got.y, tol))

    @Test fun everyAnchorIsOnTheDrawingAndInsideTheBox() {
        for (f in boxes) for (g in guides) for (q in 0..3) for (flip in listOf(false, true)) {
            val sh = GuideShape.build(g, f, q, flip)
            assertTrue("$g has anchors", sh.anchors.isNotEmpty())
            for (a in sh.anchors) {
                assertTrue("$g q$q flip$flip anchor on drawing in $f", GuideShape.onDrawing(sh, a.p))
                assertTrue("$g anchor inside $f", a.p.x > f.l && a.p.x < f.r && a.p.y > f.t && a.p.y < f.b)
            }
        }
    }

    @Test fun cropMarksAndOffHaveNoFocalPoint() {
        assertFalse(GuideShape.hasAnchors(Guide.OFF)); assertFalse(GuideShape.hasAnchors(Guide.MARKS))
        for (f in boxes) {
            assertTrue(GuideShape.build(Guide.MARKS, f, 0, false).anchors.isEmpty())
            assertTrue(GuideShape.build(Guide.OFF, f, 0, false).anchors.isEmpty())
        }
    }

    @Test fun spiralAnchorIsTheEyeWhereTheCurveConverges() {
        for (f in boxes) for (q in 0..3) {
            val sh = GuideShape.build(Guide.SPIRAL, f, q, false)
            assertEquals(1, sh.anchors.size)
            assertPt("spiral q$q eye is curve start", sh.curve[0], sh.anchors[0].p, 0.01f)
            assertNull("the eye has no line direction", sh.anchors[0].angle)
            val e = sh.anchors[0].p
            assertEquals("q$q eye left/right", q == 0 || q == 2, e.x < f.cx)
            assertEquals("q$q eye top/bottom", q == 0 || q == 1, e.y < f.cy)
        }
    }

    @Test fun rotatingTheSpiralMirrorsItsEye() {
        for (f in boxes) {
            val e0 = GuideShape.build(Guide.SPIRAL, f, 0, false).anchors[0].p
            val e1 = GuideShape.build(Guide.SPIRAL, f, 1, false).anchors[0].p
            val e2 = GuideShape.build(Guide.SPIRAL, f, 2, false).anchors[0].p
            val e3 = GuideShape.build(Guide.SPIRAL, f, 3, false).anchors[0].p
            assertPt("top right mirrors top left", Pt(f.l + f.r - e0.x, e0.y), e1)
            assertPt("bottom left mirrors top left", Pt(e0.x, f.t + f.b - e0.y), e2)
            assertPt("bottom right mirrors both", Pt(f.l + f.r - e0.x, f.t + f.b - e0.y), e3)
        }
    }

    @Test fun thirdsAndGridAnchorsAreTheirIntersections() {
        for (f in boxes) {
            for ((g, a) in listOf(Guide.THIRDS to 1 / 3f, Guide.PHI_GRID to (1 - 1 / PHI).toFloat())) {
                val an = GuideShape.build(g, f, 0, false).anchors.map { it.p }
                val want = listOf(Pt(f.l + a * f.w, f.t + a * f.h), Pt(f.r - a * f.w, f.t + a * f.h), Pt(f.l + a * f.w, f.b - a * f.h), Pt(f.r - a * f.w, f.b - a * f.h))
                for (i in 0..3) assertPt("$g anchor $i", want[i], an[i])
            }
        }
    }

    @Test fun tappingPicksTheNearestIntersection() {
        val sh = GuideShape.build(Guide.THIRDS, portrait, 0, false)
        val br = sh.anchors[3].p
        assertEquals(3, GuideShape.nearest(sh, Pt(br.x + 30f, br.y - 25f)))
        assertEquals(0, GuideShape.nearest(sh, Pt(portrait.l + 10f, portrait.t + 10f)))
    }

    @Test fun centreIsTheExactCentre() {
        for (f in boxes) assertPt("centre", Pt(f.cx, f.cy), GuideShape.build(Guide.CENTRE, f, 0, false).anchors.single().p, 0.01f)
    }

    @Test fun triangleAnchorsAreThePerpendicularFeetOnTheDiagonal() {
        for (f in boxes) for (flip in listOf(false, true)) {
            val sh = GuideShape.build(Guide.TRIANGLES, f, 0, flip)
            val a = if (!flip) f.tl else f.tr; val b = if (!flip) f.br else f.bl
            val corners = if (!flip) listOf(f.tr, f.bl) else listOf(f.tl, f.br)
            assertEquals(2, sh.anchors.size)
            for ((i, an) in sh.anchors.withIndex()) {
                val c = corners[i]; val p = an.p
                // on the diagonal
                val cross = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
                assertTrue("on diagonal", abs(cross) / a.dist(b) < 0.5f)
                // the line from the corner meets it at a right angle
                val dot = (c.x - p.x) * (b.x - a.x) + (c.y - p.y) * (b.y - a.y)
                assertTrue("perpendicular", abs(dot) / (c.dist(p) * a.dist(b)) < 1e-3f)
                assertEquals("direction is the diagonal", atan2(b.y - a.y, b.x - a.x), an.angle!!, 1e-4f)
            }
            if (flip) {
                val un = GuideShape.build(Guide.TRIANGLES, f, 0, false).anchors.map { it.p }
                for (i in 0..1) assertPt("mirrored triangles mirror their anchors", Pt(f.l + f.r - un[i].x, un[i].y), sh.anchors[i].p)
            }
        }
    }

    @Test fun diagonalAnchorsAreWhereTheCornerLinesCross() {
        // 3:4 portrait: a diamond at (½, ⅜), (⅓, ½), (⅔, ½), (½, ⅝) of the frame
        val an = GuideShape.build(Guide.DIAGONALS, portrait, 0, false).anchors.map { Pt((it.p.x - portrait.l) / portrait.w, (it.p.y - portrait.t) / portrait.h) }
        val want = listOf(Pt(0.5f, 0.375f), Pt(1 / 3f, 0.5f), Pt(2 / 3f, 0.5f), Pt(0.5f, 0.625f))
        assertEquals(4, an.size)
        for (i in 0..3) assertPt("diagonal crossing $i", want[i], an[i], 0.001f)
        // a square: all four lines meet at the centre
        val sq = GuideShape.build(Guide.DIAGONALS, square, 0, false).anchors
        assertEquals(1, sq.size); assertPt("square centre", Pt(square.cx, square.cy), sq[0].p)
        assertTrue(sq.all { it.angle == null })
    }

    @Test fun dynamicAnchorsAreTheMarkedFeetAndSymmetric() {
        for (f in boxes) {
            val sh = GuideShape.build(Guide.DYNAMIC, f, 0, false)
            assertEquals(4, sh.anchors.size)
            for (a in sh.anchors) assertTrue("anchor is a marked point", sh.markers.any { it.p.dist(a.p) < 0.01f })
            for (a in sh.anchors) assertTrue("mirror image is also an anchor", sh.anchors.any { it.p.dist(Pt(f.l + f.r - a.p.x, a.p.y)) < 0.5f })
            // each lies on the main diagonal it names as its direction
            for (a in sh.anchors) {
                val d = a.angle!!; val nx = -kotlin.math.sin(d); val ny = kotlin.math.cos(d)
                val start = if (abs(d - atan2(f.h, f.w)) < 1e-3f) f.tl else f.tr
                assertTrue("on its diagonal", abs((a.p.x - start.x) * nx + (a.p.y - start.y) * ny) < 0.5f)
            }
        }
    }

    @Test fun maskTurnsWithTheSavedPhoto() {
        val m = FocusMask(1, 0.3f, 0.2f, 0.4f, 0.1f, 0.5f, 0.3f)
        val back = Focus.toPhoto(Focus.toPhoto(m, 90, 0.75f), 270, 1 / 0.75f)
        assertEquals(m.cx, back.cx, 1e-5f); assertEquals(m.cy, back.cy, 1e-5f); assertEquals(m.angle, back.angle, 1e-5f)
        assertEquals(m.size, back.size, 1e-5f); assertEquals(m.feather, back.feather, 1e-4f)
        var r = m
        repeat(4) { r = Focus.toPhoto(r, 90, if (it % 2 == 0) 0.75f else 1 / 0.75f) }
        assertEquals(m.cx, r.cx, 1e-5f); assertEquals(m.cy, r.cy, 1e-5f)
        val upside = Focus.toPhoto(m, 180, 0.75f)
        assertEquals(1 - m.cx, upside.cx, 1e-5f); assertEquals(1 - m.cy, upside.cy, 1e-5f)
        assertEquals(m, Focus.toPhoto(m, 0, 0.75f))
    }
}
