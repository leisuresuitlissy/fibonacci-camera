package com.lissy.fibcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Tilt Shift's band: Feather only shapes the transition, and never uses up the room for full blur. */
class TiltBandTest {
    private val aspect = 0.75f

    /** Distance (frame heights) from the band's centre line to the frame's farthest corner. */
    private fun far(m: FocusMask): Float {
        val nx = -sin(m.angle); val ny = cos(m.angle)
        var f = 0f
        for (sx in floatArrayOf(-0.5f, 0.5f)) for (sy in floatArrayOf(-0.5f, 0.5f))
            f = max(f, abs((sx * aspect - (m.cx - 0.5f) * aspect) * nx + (sy - (m.cy - 0.5f)) * ny))
        return f
    }

    private fun band(focus: Float, width: Float, angle: Float, feather: Float): FocusMask {
        val raw = ArtMode.TILT_SHIFT.defaults.copyOf().also { it[1] = focus; it[2] = width; it[3] = angle; it[4] = feather }
        return Focus.tiltBand(Art.eff(ArtMode.TILT_SHIFT, raw), aspect)
    }

    @Test fun maxFeatherLeavesAFullyBlurredRegion() {
        for (focus in floatArrayOf(0f, 0.15f, 0.5f, 0.85f, 1f)) for (angle in floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)) for (width in floatArrayOf(0f, 0.5f, 1f)) {
            val m = band(focus, width, angle, 1f)
            val f = Focus.featherLen(m, aspect)
            val room = far(m) - m.size
            if (room > 0.1f) assertTrue("focus=$focus angle=$angle width=$width: feather $f eats room $room", m.size + f < far(m) - 0.25f * room)
        }
    }

    @Test fun minFeatherIsStillAGradientNotAHardEdge() {
        val f = Focus.featherLen(band(0.5f, 0.5f, 0.5f, 0f), aspect)
        assertTrue("min feather $f", f > 0.03f)
    }

    @Test fun featherOnlyChangesTheTransition() {
        val a = band(0.5f, 0.5f, 0.5f, 0f); val b = band(0.5f, 0.5f, 0.5f, 1f)
        assertEquals(a.size, b.size, 1e-6f)          // the sharp region is the same
        assertEquals(a.strength, b.strength, 1e-6f)  // and so is the blur strength
        assertTrue(Focus.featherLen(b, aspect) > 3f * Focus.featherLen(a, aspect))
    }

    @Test fun rotatingToLandscapeKeepsTheRelativeFeather() {
        val m = band(0.5f, 0.5f, 0.5f, 0.7f)
        assertEquals(m.feather, Focus.toPhoto(m, 90, aspect).feather, 1e-6f)
    }

    @Test fun blurRadiusScalesWithTheFrameAndNotWithFeather() {
        val v = Art.eff(ArtMode.TILT_SHIFT, ArtMode.TILT_SHIFT.defaults)
        assertEquals(TiltFx.radius(v, 1000f) * 4f, TiltFx.radius(v, 4000f), 1e-3f)
        assertTrue(TiltFx.radius(v, 1000f) >= 50f)   // default: at least 5% of the short side
    }
}
