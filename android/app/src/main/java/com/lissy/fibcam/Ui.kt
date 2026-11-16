package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import kotlin.math.abs
import kotlin.math.roundToInt

/** Crooked Light design tokens, from the Figma colour styles and text settings. */
object T {
    const val AMBER = 0xFFE9B65B.toInt()
    const val CARBON = 0xFF101112.toInt()
    const val GRAPHITE = 0xFF232528.toInt()
    const val IVORY = 0xFFF4EDE1.toInt()
    const val BORDER = 0xFF4F5256.toInt()
    /** Secondary text in Settings. */
    const val SUBTLE = 0xFFAAA69E.toInt()
    /** The slider handle in the Look controls. */
    const val HANDLE = 0xFFF2ECE0.toInt()
    /** Selected labels and markers in the composition thumbnails. */
    const val MARKER = 0xFFE9B957.toInt()

    lateinit var regular: Typeface
    lateinit var medium: Typeface
    lateinit var semibold: Typeface

    fun init(ctx: Context) {
        if (::regular.isInitialized) return
        regular = ResourcesCompat.getFont(ctx, R.font.inter_regular) ?: Typeface.DEFAULT
        medium = ResourcesCompat.getFont(ctx, R.font.inter_medium) ?: Typeface.DEFAULT
        semibold = ResourcesCompat.getFont(ctx, R.font.inter_semibold) ?: Typeface.DEFAULT_BOLD
    }

    fun alpha(c: Int, a: Float) = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
}

fun Context.dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

/** Text in the design's sizes. Sizes are in dp so the fixed control layout never reflows. */
fun Context.text(s: String, size: Float, color: Int, face: Typeface, spacing: Float = 0f, caps: Boolean = false) = TextView(this).apply {
    text = s; setTextSize(TypedValue.COMPLEX_UNIT_DIP, size); setTextColor(color); typeface = face
    letterSpacing = spacing; isAllCaps = caps; includeFontPadding = false; maxLines = 1
}

fun pill(fill: Int, radius: Float, stroke: Int? = null, strokeW: Int = 1) = GradientDrawable().apply {
    setColor(fill); cornerRadius = radius; if (stroke != null) setStroke(strokeW, stroke)
}

/**
 * A horizontal scroller with the design's edge fades: a Carbon fade on each edge only while there is
 * more content beyond it. The fades are drawn over the content and stay put while it scrolls.
 */
class FadeScroller(ctx: Context, private val fadeW: Float, private val fadeColor: Int = T.CARBON) : HorizontalScrollView(ctx) {
    private val paint = Paint()
    private var lastW = -1
    private lateinit var left: LinearGradient
    private lateinit var right: LinearGradient
    init { isHorizontalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER; isFillViewport = false }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        if (width != lastW) {
            lastW = width
            left = LinearGradient(0f, 0f, fadeW, 0f, fadeColor, T.alpha(fadeColor, 0f), Shader.TileMode.CLAMP)
            right = LinearGradient(width - fadeW, 0f, width.toFloat(), 0f, T.alpha(fadeColor, 0f), fadeColor, Shader.TileMode.CLAMP)
        }
        c.save(); c.translate(scrollX.toFloat(), 0f)
        if (canScrollHorizontally(-1)) { paint.shader = left; c.drawRect(0f, 0f, fadeW, height.toFloat(), paint) }
        if (canScrollHorizontally(1)) { paint.shader = right; c.drawRect(width - fadeW, 0f, width.toFloat(), height.toFloat(), paint) }
        c.restore()
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); invalidate() }

    /** Brings [v] fully into view (clear of the fades) without jumping if it already is. */
    fun reveal(v: View, smooth: Boolean = true) = post {
        val pad = fadeW.roundToInt()
        val target = when {
            v.left - pad < scrollX -> v.left - pad
            v.right + pad > scrollX + width -> v.right + pad - width
            else -> return@post
        }
        if (smooth) smoothScrollTo(target.coerceAtLeast(0), 0) else scrollTo(target.coerceAtLeast(0), 0)
    }
}

/**
 * A thumbnail with a label underneath: the Look and Compose pickers and the orientation choices.
 * [art] is drawn into [artRect] (dp, inside the box); [crop] fills it like Figma's "Fill" (centre crop).
 */
class TileView(ctx: Context, private val art: Bitmap?, private val boxW: Float, private val boxH: Float, private val artRect: RectF,
               private val radius: Float, private val fill: Int?, private val label: String, private val labelSize: Float,
               private val crop: Boolean) : View(ctx) {
    var selected = false
        set(v) { field = v; invalidate() }
    private val d = ctx.dp(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = T.semibold; textSize = labelSize * d; textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val src = android.graphics.Rect()
    private val dst = RectF()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((boxW * d).roundToInt(), ((boxH + 2f + labelSize * 1.25f) * d).roundToInt())

    override fun onDraw(c: Canvas) {
        val bw = boxW * d; val bh = boxH * d; val r = radius * d
        clip.reset(); clip.addRoundRect(0f, 0f, bw, bh, r, r, Path.Direction.CW)
        c.save(); c.clipPath(clip)
        if (fill != null) { p.color = fill; c.drawRect(0f, 0f, bw, bh, p) }
        art?.let { b ->
            dst.set(artRect.left * d, artRect.top * d, artRect.right * d, artRect.bottom * d)
            if (crop) {
                val sa = b.width.toFloat() / b.height; val da = dst.width() / dst.height()
                if (sa > da) { val w = (b.height * da).roundToInt(); src.set((b.width - w) / 2, 0, (b.width + w) / 2, b.height) }
                else { val h = (b.width / da).roundToInt(); src.set(0, (b.height - h) / 2, b.width, (b.height + h) / 2) }
            } else src.set(0, 0, b.width, b.height)
            p.color = Color.WHITE; c.drawBitmap(b, src, dst, p)
        }
        c.restore()
        // the border sits inside the box, like Figma's inside strokes
        val sw = (if (selected) 2f else 1f) * d
        stroke.strokeWidth = sw; stroke.color = if (selected) T.AMBER else T.BORDER
        c.drawRoundRect(sw / 2, sw / 2, bw - sw / 2, bh - sw / 2, (r - sw / 2).coerceAtLeast(0f), (r - sw / 2).coerceAtLeast(0f), stroke)
        tp.color = if (selected) T.AMBER else T.IVORY
        val fm = tp.fontMetrics
        val lineH = labelSize * 1.25f * d
        c.drawText(label, bw / 2, bh + 2 * d + lineH / 2 - (fm.ascent + fm.descent) / 2, tp)
    }
}

/**
 * The Look slider: a 4dp rail, Amber fill, faint ticks every 10%, an 18dp handle. Detent ticks while
 * dragging (Settings › Haptic feedback). When [enabledLook] is false it dims and shows no fill (Leaks · Off).
 */
@SuppressLint("ClickableViewAccessibility")
class ValueSlider(ctx: Context, private val ticks: Boolean = true, neutral: Float? = null, private val handleColor: Int = T.HANDLE) : View(ctx) {
    var value = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var enabledLook = true
        set(v) { field = v; alpha = if (v) 1f else 0.4f; invalidate() }
    var onChange: ((Float) -> Unit)? = null
    var onStart: (() -> Unit)? = null
    var onStop: (() -> Unit)? = null
    private val d = ctx.dp(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val det = Detents(neutral)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val cy = height / 2f
        p.color = T.BORDER; c.drawRoundRect(0f, cy - 2 * d, w, cy + 2 * d, 2 * d, 2 * d, p)
        val v = if (enabledLook) value else 0f
        if (enabledLook && v > 0f) { p.color = T.AMBER; c.drawRoundRect(0f, cy - 2 * d, v * w, cy + 2 * d, 2 * d, 2 * d, p) }
        if (ticks) { p.color = T.alpha(Color.WHITE, 0.12f); for (i in 1..9) { val x = w * i / 10f; c.drawRect(x, cy - 2 * d, x + d, cy + 2 * d, p) } }
        val hx = (v * w).coerceIn(9 * d, w - 9 * d)
        p.color = handleColor; c.drawCircle(hx, cy, 9 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled || !enabledLook) return false
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); det.begin(value); onStart?.invoke(); set(f) }
            MotionEvent.ACTION_MOVE -> set(f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { set(f); onStop?.invoke() }
        }
        return true
    }
    private fun set(f: Float) { if (abs(f - value) < 0.0005f) return; det.move(this, f); value = f; onChange?.invoke(f) }
}

/**
 * Exposure compensation from −2 to +2 EV: neutral zero in the middle (Amber), whole stops marked,
 * quarter stops as faint ticks, an 18dp handle. Reports EV; the caller snaps to the camera's own steps.
 */
@SuppressLint("ClickableViewAccessibility")
class EvSlider(ctx: Context) : View(ctx) {
    var ev = 0f
        set(v) { field = v.coerceIn(-2f, 2f); invalidate() }
    var onChange: ((Float) -> Unit)? = null
    private val d = ctx.dp(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val det = Detents(0.5f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat() - d; val ty = 12 * d
        p.color = T.BORDER; c.drawRoundRect(0f, ty, w + d, ty + 4 * d, 2 * d, 2 * d, p)
        for (i in 0..16) {
            val x = w * i / 16f
            when {
                i == 8 -> { p.color = T.AMBER; c.drawRect(x, ty - 4 * d, x + d, ty + 8 * d, p) }
                i % 4 == 0 -> { p.color = T.alpha(T.IVORY, 0.38f); c.drawRect(x, ty - 4 * d, x + d, ty + 8 * d, p) }
                else -> { p.color = T.alpha(T.IVORY, 0.12f); c.drawRect(x, ty, x + d, ty + 4 * d, p) }
            }
        }
        val hx = (w * (ev + 2f) / 4f + d / 2).coerceIn(9 * d, w + d - 9 * d)
        p.color = T.IVORY; c.drawCircle(hx, ty + 2 * d, 9 * d, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        val f = (e.x / width).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent.requestDisallowInterceptTouchEvent(true); det.begin((ev + 2f) / 4f); move(f) }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> move(f)
        }
        return true
    }
    private fun move(f: Float) { det.move(this, f); onChange?.invoke(f * 4f - 2f) }
}

/** The Settings switch: a 36×22 track, 18dp knob, in a 44dp touch target. */
class SwitchView(ctx: Context) : View(ctx) {
    var checked = false
        set(v) { field = v; invalidate() }
    var onToggle: ((Boolean) -> Unit)? = null
    private val d = ctx.dp(1f)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    init { setOnClickListener { checked = !checked; onToggle?.invoke(checked) }; contentDescription = "Switch" }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((44 * d).roundToInt(), (44 * d).roundToInt())
    override fun onDraw(c: Canvas) {
        val l = 4 * d; val t = 11 * d
        p.color = if (checked) T.AMBER else T.BORDER
        c.drawRoundRect(l, t, l + 36 * d, t + 22 * d, 11 * d, 11 * d, p)
        p.color = if (checked) T.CARBON else T.IVORY
        val cx = if (checked) l + 25 * d else l + 11 * d
        c.drawCircle(cx, t + 11 * d, 9 * d, p)
    }
}

/** A plain bar for the Guide opacity setting: rail, fill, handle, no ticks. */
fun plainSlider(ctx: Context) = ValueSlider(ctx, ticks = false, handleColor = T.IVORY)
