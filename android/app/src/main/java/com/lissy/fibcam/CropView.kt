package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.RenderNode
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
import kotlin.math.roundToInt
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
        set(v) { field = v; invalidate() }
    /** Art tab: a quick tap picks the point that stays sharp under Depth (reported 0..1 across the photo). */
    var focusMode = false
        set(v) { field = v; invalidate() }
    var onFocusTap: ((Float, Float) -> Unit)? = null
    /** Which thirds/φ point or corner diagonal a guide-linked focus uses (from the camera's setting). */
    var anchor = 0
    private var maskUntil = 0L
    /** Briefly outline the focus region (after a tap or a focus slider). */
    fun flashMask() { maskUntil = System.currentTimeMillis() + 1600; invalidate(); postInvalidateDelayed(1700) }

    // ---- focus coordinates: image uv (0..1 across the photo) <-> crop uv (0..1 across the tilted crop)
    fun imageToCrop(u: Float, v: Float): PointF {
        val dx = u * bw - cx; val dy = v * bh - cy
        val c = cos(theta); val s = sin(theta)
        return PointF(((dx * c + dy * s) + cw / 2) / cw, ((-dx * s + dy * c) + ch / 2) / ch)
    }
    fun cropToImage(a: Float, b: Float): PointF {
        val x = (a - 0.5f) * cw; val y = (b - 0.5f) * ch
        val c = cos(theta); val s = sin(theta)
        return PointF((cx + x * c - y * s) / bw, (cy + x * s + y * c) / bh)
    }

    /**
     * The look's focus region in crop uv (what the saved copy uses). Follow guide on: worked out from the
     * crop's own guide, so it moves with the crop. Off: the point you chose stays on the same part of the
     * photo however you crop or straighten.
     */
    fun cropMask(r: Recipe): FocusMask = maskIn(r, 1f)

    /**
     * The frame a look is rendered on, as a multiple of the crop's height. Plastic is the camera's square
     * guide inside its 3:4 frame: the camera renders it on the 3:4 frame and keeps the square (Art.finish), so
     * a square crop is rendered the same way, on the 3:4 frame around it. Everything else: the crop itself.
     */
    fun renderFactor(r: Recipe): Float = if (r.mode == ArtMode.HOLGA && abs(aspect - 1f) < 0.01f) 4f / 3f else 1f

    /** The look's mask in its render frame (see [renderFactor]): what the preview and the saved copy use. */
    fun renderMask(r: Recipe): FocusMask = maskIn(r, renderFactor(r))

    /**
     * The mask in a frame [fh] crop-heights tall, centred on the crop (the crop is the guide's frame inside it,
     * as the square guide sits inside the camera's frame). [fh] 1: the crop itself.
     */
    private fun maskIn(r: Recipe, fh: Float): FocusMask {
        val off = (fh - 1f) * ch / 2f
        val fr = RectF(0f, 0f, cw, ch * fh); val gf = RectF(0f, off, cw, off + ch)
        val aspect = cw / (ch * fh)
        fun toFrame(p: PointF) = PointF(p.x, (off + p.y * ch) / (ch * fh))
        // Tilt Shift on a line-friendly guide: the band runs through the guide's anchor, at its Angle (camera rule)
        if (r.follow && r.mode == ArtMode.TILT_SHIFT && guide in Catalog.tiltGuides) {
            guideAnchor()?.let { a -> val q = toFrame(PointF(a.u, a.v)); return Focus.resolve(r.mode, r.values, false, null, null, aspect = aspect).copy(cx = q.x, cy = q.y) }
        }
        if (r.follow && r.mode.follows && r.mode != ArtMode.TILT_SHIFT) {
            val g = GuideCtx(guide, quadrant, triFlip, anchor, fr, gf, trueSpiral = true)
            val m = Focus.resolve(r.mode, r.values, true, g, null, aspect = aspect)
            if (Focus.linked(g) != null) return m
        }
        val p = toFrame(imageToCrop(r.mask.cx, r.mask.cy))
        val m = Focus.resolve(r.mode, r.values, false, null, p, aspect = aspect)
        return if (r.mode == ArtMode.TILT_SHIFT) m.copy(cx = p.x, cy = p.y) else m
    }

    /**
     * Tilt Shift's band as its Focus and Angle place it on the crop (the camera's rule), pinned to that spot of
     * the photo from here on. Used whenever the values are set wholesale: choosing the look, Reset all.
     */
    fun pinTilt(r: Recipe): Recipe {
        if (r.mode != ArtMode.TILT_SHIFT || cw <= 0f) return r
        val m = Focus.resolve(r.mode, r.values, false, null, null, aspect = cw / ch)
        val ip = cropToImage(m.cx, m.cy)
        return r.copy(mask = r.mask.copy(cx = ip.x, cy = ip.y))
    }

    /** The guide's chosen anchor on the crop (crop uv), or null when the guide has none. */
    fun guideAnchor(): FocusAnchor? {
        if (cw <= 0f) return null
        val fr = RectF(0f, 0f, cw, ch)
        return Focus.linked(GuideCtx(guide, quadrant, triFlip, anchor, fr, fr, trueSpiral = true))
    }
    /** The anchor Follow guide is using, marked as in the viewfinder; -1 for none. */
    var selectedAnchor = -1
        set(v) { field = v; invalidate() }
    private var downX = 0f; private var downY = 0f; private var downT = 0L; private var multi = false
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var artShader: RuntimeShader? = null
    private var artShaderMode: ArtMode? = null
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val focusRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = T.AMBER }
    private val focusSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = T.AMBER }

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
        style = Paint.Style.STROKE; color = T.alpha(T.AMBER, 0.9f)
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
    private val painter = GuidePainter(d).apply { opacity = context.getSharedPreferences("fibcam", android.content.Context.MODE_PRIVATE).getFloat("guideOpacity", 0.75f) }
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
        val top = max(pad, inset)
        k = min((width - 2 * pad) / b.width, (height - top - pad) / b.height)
        ox = (width - b.width * k) / 2; oy = top + (height - top - pad - b.height * k) / 2
    }

    /** Off (Crop on): the whole photo with the crop box. On: the crop fills the view, upright, like the viewfinder. */
    var framed = false
        set(v) { field = v; mode = Mode.NONE; invalidate() }
    /** Room kept clear at the top (for the header) while the whole photo is shown. */
    var inset = 0f
        set(v) { if (field != v) { field = v; if (width > 0) fit(); invalidate() } }
    // where the crop sits on screen: its centre, screen px per photo px, and its turn on screen
    private val sc = PointF(); private var sk = 1f; private var sdeg = 0f
    /** Photo px → view px, and back. */
    private val vm = Matrix(); private val vmInv = Matrix()
    private fun place() {
        if (framed && cw > 0f) {
            sk = min(width / cw, height / ch); sc.set(width / 2f, height / 2f); sdeg = 0f
            vm.reset(); vm.postTranslate(-cx, -cy); vm.postRotate(-Math.toDegrees(theta.toDouble()).toFloat()); vm.postScale(sk, sk); vm.postTranslate(sc.x, sc.y)
        } else {
            sk = k; val p = toScreen(PointF(cx, cy)); sc.set(p.x, p.y); sdeg = Math.toDegrees(theta.toDouble()).toFloat()
            vm.reset(); vm.postScale(k, k); vm.postTranslate(ox, oy)
        }
        vm.invert(vmInv)
    }
    /** A view point as photo uv (0..1 across the photo). */
    private fun viewToUv(x: Float, y: Float): PointF {
        val pt = floatArrayOf(x, y); vmInv.mapPoints(pt)
        return PointF((pt[0] / bw).coerceIn(0f, 1f), (pt[1] / bh).coerceIn(0f, 1f))
    }

    private fun toScreen(p: PointF) = PointF(ox + p.x * k, oy + p.y * k)
    /** The crop's corners in view pixels: top-left, top-right, bottom-right, bottom-left (CI comparisons). */
    fun screenCorners(): List<PointF> {
        place()
        return corners().map { p -> floatArrayOf(p.x, p.y).also { vm.mapPoints(it) }.let { PointF(it[0], it[1]) } }
    }

    fun corners(x: Float = cx, y: Float = cy, w: Float = cw, t: Float = theta): Array<PointF> {
        val h = w / aspect; val c = cos(t); val s = sin(t)
        return arrayOf(-w / 2 to -h / 2, w / 2 to -h / 2, w / 2 to h / 2, -w / 2 to h / 2)
            .map { (a, b) -> PointF(x + a * c - b * s, y + a * s + b * c) }.toTypedArray()
    }

    fun fits(x: Float = cx, y: Float = cy, w: Float = cw, t: Float = theta): Boolean =
        corners(x, y, w, t).all { it.x >= -0.02f && it.y >= -0.02f && it.x <= bw + 0.02f && it.y <= bh + 0.02f }   // never past the photo: a crop of the whole photo saves at its exact size

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
        place()
        m.set(vm)
        val pic = picture(b)
        val w = cw * sk; val h = ch * sk
        c.save()
        if (framed) c.clipRect(sc.x - w / 2, sc.y - h / 2, sc.x + w / 2, sc.y + h / 2)   // only the crop, like the viewfinder
        c.drawBitmap(pic, m, bmpPaint)
        drawArt(c, pic)
        c.restore()
        val q = corners().map { toScreen(it) }
        if (!framed) {
            // dim everything outside the crop
            path.reset(); path.fillType = Path.FillType.EVEN_ODD
            path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            path.moveTo(q[0].x, q[0].y); for (i in 1..3) path.lineTo(q[i].x, q[i].y); path.close()
            c.drawPath(path, dim)
            path.reset(); path.moveTo(q[0].x, q[0].y); for (i in 1..3) path.lineTo(q[i].x, q[i].y); path.close()
            c.drawPath(path, edge)
        }
        // guide, in the crop's own frame
        c.save(); c.translate(sc.x, sc.y); c.rotate(sdeg)
        c.clipRect(-w / 2, -h / 2, w / 2, h / 2)
        painter.draw(c, RectF(-w / 2, -h / 2, w / 2, h / 2), guide, quadrant, triFlip, trueSpiral = true, selected = selectedAnchor)
        c.restore()
        recipe?.let { r ->
            // Look tab: briefly show the simulated focus region, in the crop's frame (never saved)
            if (focusMode && System.currentTimeMillis() < maskUntil && r.mode != ArtMode.OFF) {
                val fm = renderMask(r); val fh = renderFactor(r); val rh = h * fh
                if (fm.type != 0 && (r.mode == ArtMode.TILT_SHIFT || fm.strength > 0f)) {
                    c.save(); c.translate(sc.x, sc.y); c.rotate(sdeg)
                    c.clipRect(-w / 2, -h / 2, w / 2, h / 2)
                    val mx = -w / 2 + fm.cx * w; val my = -rh / 2 + fm.cy * rh
                    val inner = fm.size * rh; val outer = (fm.size + Focus.featherLen(fm, w / rh)) * rh
                    // the viewfinder's mask outline (OverlayView): solid edge of the sharp area, dashed end of the fade
                    focusRing.alpha = 220; focusRing.strokeWidth = 1.3f * d
                    focusSoft.alpha = 140; focusSoft.strokeWidth = 1f * d; focusSoft.pathEffect = DashPathEffect(floatArrayOf(4 * d, 5 * d), 0f)
                    if (fm.type == 2) {
                        c.drawCircle(mx, my, inner, focusRing); c.drawCircle(mx, my, outer, focusSoft); c.drawCircle(mx, my, 3 * d, focusRing)
                    } else {
                        val ux = cos(fm.angle); val uy = sin(fm.angle); val len = w + h
                        for ((off, p) in listOf(inner to focusRing, -inner to focusRing, outer to focusSoft, -outer to focusSoft)) {
                            val px0 = mx - uy * off; val py0 = my + ux * off
                            c.drawLine(px0 - ux * len, py0 - uy * len, px0 + ux * len, py0 + uy * len, p)
                        }
                    }
                    c.restore()
                }
            }
        }
        if (editingSecond && second != null) {
            second?.let { t ->
                // outline of the second photo, so you can see what you're moving
                val pts = floatArrayOf(0f, 0f, t.width.toFloat(), 0f, t.width.toFloat(), t.height.toFloat(), 0f, t.height.toFloat())
                secondMatrix(t).mapPoints(pts); vm.mapPoints(pts)
                path.reset(); path.moveTo(pts[0], pts[1])
                for (i in 1..3) path.lineTo(pts[i * 2], pts[i * 2 + 1])
                path.close(); c.drawPath(path, outline)
            }
        }
        if (!framed) for (p in q) { c.drawCircle(p.x, p.y, 6 * d, handle); c.drawCircle(p.x, p.y, 6 * d, handleEdge) }
    }
    /**
     * The look, drawn on the crop in the crop's own straightened frame: the same rectangle, mask and uniforms
     * the saved copy is rendered with (CropActivity.save → Art.render), only smaller. So the vignette, focus
     * band and grain sit where they will on the copy, however the crop is moved, sized or turned.
     * Tilt Shift goes through TiltFx, the layered blur the camera and saved copies use.
     */
    private fun drawArt(c: Canvas, b: Bitmap): Boolean {
        val r = recipe ?: return false
        if (r.mode == ArtMode.OFF || Build.VERSION.SDK_INT < 33) return false
        val w = cw * sk; val h = ch * sk
        if (w < 2f || h < 2f) return false
        val deg = sdeg
        // bitmap px → crop-local px (origin at the crop's top-left corner, axes along its edges)
        val local = Matrix(m).apply { postTranslate(-sc.x, -sc.y); postRotate(-deg); postTranslate(w / 2, h / 2) }
        val mask = renderMask(r)
        val fh = renderFactor(r)
        if (r.mode == ArtMode.TILT_SHIFT && c.isHardwareAccelerated) {
            val fx = tiltFx ?: TiltFx.create().also { tiltFx = it }
            if (fx != null) {
                val node = tiltNode ?: RenderNode("editTilt").also { tiltNode = it }
                node.setPosition(0, 0, w.roundToInt(), h.roundToInt())
                node.setRenderEffect(fx.effect(r.values, 0f, 0f, w, h, r.seed, mask))
                val rc = node.beginRecording()
                rc.concat(local); rc.drawBitmap(b, 0f, 0f, bmpPaint)
                node.endRecording()
                c.save(); c.translate(sc.x, sc.y); c.rotate(deg); c.translate(-w / 2, -h / 2)
                c.drawRenderNode(node)
                c.restore()
                return true
            }
        }
        if (artShaderMode != r.mode) { artShader = Art.newShader(r.mode); artShaderMode = r.mode }
        val sh = artShader ?: return false
        sh.setInputShader("content", BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(local) })
        // the render frame: the crop, or for Plastic the 3:4 frame around it; only the crop is drawn
        Art.setUniforms(sh, r.mode, r.values, 0f, -(fh - 1f) * h / 2f, w, h * fh, r.seed, mask)
        artPaint.shader = sh
        c.save(); c.translate(sc.x, sc.y); c.rotate(deg); c.translate(-w / 2, -h / 2)
        c.drawRect(0f, 0f, w, h, artPaint)
        c.restore()
        return true
    }
    private var tiltFx: TiltFx? = null
    private var tiltNode: RenderNode? = null

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

    /** A view-space movement as photo px. */
    private fun toPhotoVec(dx: Float, dy: Float): PointF { val v = floatArrayOf(dx, dy); vmInv.mapVectors(v); return PointF(v[0], v[1]) }

    private fun secondTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> if (focusMode && !multi && e.eventTime - downT < 350 && hypot(e.x - downX, e.y - downY) < slop) {
                viewToUv(e.x, e.y).let { onFocusTap?.invoke(it.x, it.y) }
            }
        }
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
                    val mv = toPhotoVec(mx - g0Cx, my - g0Cy); s2x = g0X + mv.x; s2y = g0Y + mv.y
                    comboDirty = true; invalidate(); onSecondChange?.invoke()
                } else if (!twoFinger && e.pointerCount == 1) {
                    val mv = toPhotoVec(e.x - lx, e.y - ly); s2x += mv.x; s2y += mv.y
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
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downT = e.eventTime; multi = false }
            MotionEvent.ACTION_POINTER_DOWN -> multi = true
            MotionEvent.ACTION_UP -> if (focusMode && !multi && e.eventTime - downT < 350 && hypot(e.x - downX, e.y - downY) < slop) {
                viewToUv(e.x, e.y).let { onFocusTap?.invoke(it.x, it.y) }
            }
        }
        if (framed) return true                    // Crop off: the crop stays put; taps still pick what stays sharp
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
