package com.lissy.fibcam

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

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

    /**
     * [trueSpiral]: Crop screen (largest fit inside the box). Otherwise the viewfinder (full height, [align]ed).
     * [selected]: an anchor to show as chosen (Follow guide), or -1.
     */
    fun draw(c: Canvas, fr: RectF, guide: Guide, quadrant: Int, triFlip: Boolean, trueSpiral: Boolean, align: Int = 1, selected: Int = -1) {
        val sh = GuideShape.build(guide, Box(fr.left, fr.top, fr.right, fr.bottom), quadrant, triFlip, trueSpiral, align)
        for (sg in sh.segs) c.drawLine(sg.a.x, sg.a.y, sg.b.x, sg.b.y, if (sg.dashed) dashed else line)
        if (sh.curve.isNotEmpty()) {
            path.reset(); path.moveTo(sh.curve[0].x, sh.curve[0].y)
            for (i in 1 until sh.curve.size) path.lineTo(sh.curve[i].x, sh.curve[i].y)
            c.drawPath(path, line)
        }
        for (m in sh.markers) when (m.kind) {
            Marker.BIG_RING -> { ring.color = IVORY; ring.alpha = line.alpha; c.drawCircle(m.p.x, m.p.y, m.radiusDp * d, ring); ring.color = MARKER; ring.alpha = line.alpha }
            Marker.DOT -> c.drawCircle(m.p.x, m.p.y, m.radiusDp * d, dot)
            else -> c.drawCircle(m.p.x, m.p.y, m.radiusDp * d, ring)
        }
        // the anchor Follow guide is using, where the guide offers a choice
        sh.anchors.getOrNull(selected)?.let { if (sh.anchors.size > 1) c.drawCircle(it.p.x, it.p.y, 2.4f * d, dot) }
    }
}
