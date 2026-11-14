package com.lissy.fibcam

import androidx.camera.core.ImageProxy
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Follows whatever you tapped. A 15×15 patch around the tap is matched frame to frame on a
 * 192-px-wide greyscale copy of the feed, using zero-mean SAD so exposure changes don't throw it.
 * No model, no network, so it works on trees and rocks as well as people.
 *
 * Coordinates are normalised (0..1) in the upright, un-mirrored camera image.
 */
class Tracker {
    data class State(val on: Boolean, val lost: Boolean, val nx: Float, val ny: Float)

    private val tw = 192
    private val pr = 7
    private val pn = (2 * pr + 1) * (2 * pr + 1)
    private val sr = 14

    @Volatile private var request: FloatArray? = null
    @Volatile private var clearRequested = false

    private var gray = FloatArray(0)
    private var w = 0
    private var h = 0
    private var tpl: FloatArray? = null
    private var u = 0
    private var v = 0
    private var bad = 0
    private var lost = false

    fun target(nx: Float, ny: Float) { request = floatArrayOf(nx, ny) }
    fun clear() { clearRequested = true; request = null }

    /** Runs on the analysis thread. Returns null when nothing is being tracked. */
    fun process(image: ImageProxy): State? {
        if (clearRequested) { clearRequested = false; tpl = null }
        val req = request
        if (tpl == null && req == null) return null
        sample(image)
        if (req != null) {
            request = null
            u = (req[0] * w).roundToInt().coerceIn(pr, w - pr - 1)
            v = (req[1] * h).roundToInt().coerceIn(pr, h - pr - 1)
            tpl = patchAt(u, v); bad = 0; lost = false
        } else step()
        return State(true, lost, u.toFloat() / w, v.toFloat() / h)
    }

    /** Upright greyscale at tw px wide, from the Y plane, honouring the frame's rotation. */
    private fun sample(image: ImageProxy) {
        val plane = image.planes[0]
        val buf = plane.buffer
        val rs = plane.rowStride; val ps = plane.pixelStride
        val sw = image.width; val sh = image.height
        val rot = image.imageInfo.rotationDegrees
        val rw = if (rot == 90 || rot == 270) sh else sw
        val rh = if (rot == 90 || rot == 270) sw else sh
        val nw = tw; val nh = max(24, (tw.toFloat() * rh / rw).roundToInt())
        if (nw != w || nh != h) { w = nw; h = nh; gray = FloatArray(w * h) }
        val fx = rw.toFloat() / w; val fy = rh.toFloat() / h
        for (y in 0 until h) {
            val ry = ((y + 0.5f) * fy).toInt().coerceIn(0, rh - 1)
            for (x in 0 until w) {
                val rx = ((x + 0.5f) * fx).toInt().coerceIn(0, rw - 1)
                val sx: Int; val sy: Int
                when (rot) {
                    90 -> { sx = ry; sy = sh - 1 - rx }
                    180 -> { sx = sw - 1 - rx; sy = sh - 1 - ry }
                    270 -> { sx = sw - 1 - ry; sy = rx }
                    else -> { sx = rx; sy = ry }
                }
                gray[y * w + x] = (buf.get(sy * rs + sx * ps).toInt() and 0xFF).toFloat()
            }
        }
    }

    private fun patchAt(x: Int, y: Int): FloatArray {
        val p = FloatArray(pn); var k = 0; var s = 0f
        for (dy in -pr..pr) for (dx in -pr..pr) { val g = gray[(y + dy) * w + (x + dx)]; p[k++] = g; s += g }
        s /= pn
        for (i in 0 until pn) p[i] -= s
        return p
    }

    private fun step() {
        val t = tpl ?: return
        val r = if (lost) sr * 2 else sr
        val x0 = max(pr, u - r); val x1 = min(w - pr - 1, u + r)
        val y0 = max(pr, v - r); val y1 = min(h - pr - 1, v + r)
        var best = Float.MAX_VALUE; var bx = u; var by = v
        for (y in y0..y1) for (x in x0..x1) {
            var m = 0f
            for (dy in -pr..pr) { val row = (y + dy) * w + x; for (dx in -pr..pr) m += gray[row + dx] }
            m /= pn
            var sad = 0f; var k = 0
            val cap = best * pn
            var dy = -pr
            while (dy <= pr && sad < cap) {
                val row = (y + dy) * w + x
                for (dx in -pr..pr) { val d = gray[row + dx] - m - t[k++]; sad += if (d < 0) -d else d }
                dy++
            }
            if (dy <= pr) continue          // bailed early: already worse than best
            sad /= pn
            if (sad < best) { best = sad; bx = x; by = y }
        }
        if (best < 16f) {
            u = bx; v = by; bad = 0; lost = false
            val cur = patchAt(bx, by)        // drift slowly with the subject's look
            for (i in 0 until pn) t[i] = t[i] * 0.92f + cur[i] * 0.08f
        } else if (++bad > 8) lost = true
    }
}
