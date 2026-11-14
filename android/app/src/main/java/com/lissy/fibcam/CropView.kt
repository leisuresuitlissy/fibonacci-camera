package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.atan2
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
    /** The art look, drawn live on the photo. Applied relative to the crop, as the saved copy will be. */
    var recipe: Recipe? = null
    /** Art tab: a quick tap picks the point that stays sharp under Depth (reported 0..1 across the photo). */
    var focusMode = false
        set(v) { field = v; invalidate() }
    var onFocusTap: ((Float, Float) -> Unit)? = null
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var multi = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
        set(v) { field = v; invalidate() }
    private var artShader: RuntimeShader? = null
    private var artShaderMode: ArtMode? = null
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFE0B252.toInt() }
    private val focusDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0B252.toInt() }

    // ---- second exposure: centre in base-bitmap px, width as a fraction of base width, rotation in degrees
    var second: Bitmap? = null
        set(v) { field = v; comboDirty = true; invalidate() }
    var blend = Blend.SCREEN
        set(v) { field = v; comboDirty = true; invalidate() }
    var strength = 0.6f
        set(v) { field = v; comboDirty = true; invalidate() }
    var s2x = 0f; var s2y = 0f; var s2w = 1f; var s2r = 0f
    /** While true, gestures move, scale and turn the second photo instead of the crop. */
    var editingSecond = false
        set(v) { field = v; mode = Mode.NONE; invalidate() }
    var onSecondChange: (() -> Unit)? = null
    private var combo: Bitmap? = null
    private var comboDirty = true
    private val s2m = Matrix()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xE6E0B252.toInt()
        strokeWidth = 1.4f * resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6f * resources.displayMetrics.density, 4f * resources.displayMetrics.density), 0f)
    }

    /** Fit the second photo to cover the base, centred and level. */
    fun resetSecond() {
        val t = second ?: return
        s2x = bw / 2; s2y = bh / 2; s2r = 0f
        s2w = max(1f, (bh / t.height) * t.width / bw)
        comboDirty = true; invalidate(); onSecondChange?.invoke()
    }

    private fun secondMatrix(t: Bitmap): Matrix {
        val g = s2w * bw / t.width
        s2m.reset(); s2m.postTranslate(-t.width / 2f, -t.height / 2f); s2m.postScale(g, g); s2m.postRotate(s2r); s2m.postTranslate(s2x, s2y)
        return s2m
    }

    /** The two photos already combined, at display size, so crop, guides and art all act on the pair. */
    private fun picture(b: Bitmap): Bitmap {
        val t = second ?: return b
        var out = combo
        if (out == null || out.width != b.width || out.height != b.height) { out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888); combo = out; comboDirty = true }
        if (comboDirty) {
            val cv = Canvas(out!!)
            cv.drawBitmap(b, 0f, 0f, null)
            cv.drawBitmap(t, secondMatrix(t), blend.paint(strength))
            comboDirty = false
        }
        return out!!
    }

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
        val q = corners().map { toScreen(it) }
        val pic = picture(b)
        if (!drawArt(c, pic, q)) c.drawBitmap(pic, m, bmpPaint)
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
        recipe?.let { r ->
            // Art tab: show what stays sharp
            if (focusMode && r.mode != ArtMode.OFF && r.mode != ArtMode.TILT_SHIFT && r.values.getOrElse(4) { 0f } > 0f) {
                val fx = ox + r.fx * bw * k; val fy = oy + r.fy * bh * k
                focusRing.strokeWidth = 1.6f * d
                c.drawCircle(fx, fy, 18 * d, focusRing); c.drawCircle(fx, fy, 2.5f * d, focusDot)
            }
        }
        if (editingSecond && second != null) {
            second?.let { t ->
                // outline of the second photo, so you can see what you're moving
                val pts = floatArrayOf(0f, 0f, t.width.toFloat(), 0f, t.width.toFloat(), t.height.toFloat(), 0f, t.height.toFloat())
                secondMatrix(t).mapPoints(pts)
                path.reset(); path.moveTo(ox + pts[0] * k, oy + pts[1] * k)
                for (i in 1..3) path.lineTo(ox + pts[i * 2] * k, oy + pts[i * 2 + 1] * k)
                path.close(); c.drawPath(path, outline)
            }
        } else {
            for (p in q) { c.drawCircle(p.x, p.y, 6 * d, handle); c.drawCircle(p.x, p.y, 6 * d, handleEdge) }
        }
    }

    private fun drawArt(c: Canvas, b: Bitmap, q: List<PointF>): Boolean {
        val r = recipe ?: return false
        if (r.mode == ArtMode.OFF || Build.VERSION.SDK_INT < 33) return false
        if (artShaderMode != r.mode) { artShader = Art.newShader(r.mode); artShaderMode = r.mode }
        val sh = artShader ?: return false
        sh.setInputShader("content", BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) })
        val x0 = q.minOf { it.x }; val y0 = q.minOf { it.y }; val x1 = q.maxOf { it.x }; val y1 = q.maxOf { it.y }
        val sfx = ox + r.fx * bw * k; val sfy = oy + r.fy * bh * k
        Art.setUniforms(sh, r.values, x0, y0, x1 - x0, y1 - y0, r.seed, (sfx - x0) / (x1 - x0), (sfy - y0) / (y1 - y0))
        artPaint.shader = sh
        c.drawRect(ox, oy, ox + b.width * k, oy + b.height * k, artPaint)
        return true
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

    // two-finger state for the second photo
    private var g0Dist = 0f; private var g0Ang = 0f; private var g0W = 1f; private var g0R = 0f
    private var g0Cx = 0f; private var g0Cy = 0f; private var g0X = 0f; private var g0Y = 0f
    private var twoFinger = false

    private fun secondTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; twoFinger = false }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFinger = true
                g0Dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)).coerceAtLeast(1f)
                g0Ang = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                g0W = s2w; g0R = s2r; g0X = s2x; g0Y = s2y
                g0Cx = (e.getX(0) + e.getX(1)) / 2; g0Cy = (e.getY(0) + e.getY(1)) / 2
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFinger && e.pointerCount >= 2) {
                    val dist = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))
                    val ang = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                    val mx = (e.getX(0) + e.getX(1)) / 2; val my = (e.getY(0) + e.getY(1)) / 2
                    s2w = (g0W * dist / g0Dist).coerceIn(0.1f, 6f)
                    var r = g0R + (ang - g0Ang)
                    while (r > 180f) r -= 360f; while (r < -180f) r += 360f
                    s2r = if (abs(r) < 1.5f) 0f else r              // sticks at level
                    s2x = g0X + (mx - g0Cx) / k; s2y = g0Y + (my - g0Cy) / k
                    comboDirty = true; invalidate(); onSecondChange?.invoke()
                } else if (!twoFinger && e.pointerCount == 1) {
                    s2x += (e.x - lx) / k; s2y += (e.y - ly) / k
                    lx = e.x; ly = e.y
                    comboDirty = true; invalidate(); onSecondChange?.invoke()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 2) {
                // carry on with one finger from wherever the remaining finger is
                val keep = if (e.actionIndex == 0) 1 else 0
                lx = e.getX(keep); ly = e.getY(keep); twoFinger = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> twoFinger = false
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bmp == null) return false
        if (editingSecond && second != null) return secondTouch(e)
        scaler.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> if (focusMode && !multi && e.eventTime - downT < 350 && hypot(e.x - downX, e.y - downY) < slop) {
                val u = ((e.x - ox) / k / bw).coerceIn(0f, 1f); val v = ((e.y - oy) / k / bh).coerceIn(0f, 1f)
                onFocusTap?.invoke(u, v)
            }
        }
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
