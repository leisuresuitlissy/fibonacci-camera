package com.lissy.fibcam

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The Look tab's contextual controls (Figma "Look controls"): the look's adjustments as a scrolling row of
 * names with the active one in Amber, a divider and the pinned Follow guide toggle, an optional row of
 * choices (Holga's leaks, Expired's colour shift), then one slider for the active adjustment.
 * When the space is shorter than the design's height the gaps tighten; nothing reflows sideways.
 */
class LookControls(ctx: Context) : FrameLayout(ctx) {
    var onSelect: ((Int) -> Unit)? = null
    var onFollow: (() -> Unit)? = null
    var onChoice: ((Int) -> Unit)? = null
    val slider = ValueSlider(ctx)
    /** The height this block may use (px); set by the screen layout. */
    var available = Int.MAX_VALUE

    private fun dp(v: Float) = context.dp(v)
    private fun dpi(v: Float) = dp(v).roundToInt()

    private val names = FadeScroller(ctx, dp(24f))
    private val namesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val divider = View(ctx).apply { setBackgroundColor(T.alpha(Color.WHITE, 0.14f)) }
    private val follow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        setPadding(dpi(10f), 0, dpi(10f), 0)
        contentDescription = "Follow guide"
    }
    private val followIcon = ImageView(ctx).apply { setImageResource(R.drawable.ic_link) }
    private val followText = ctx.text("Follow guide", 10f, T.IVORY, T.medium, 0.08f, caps = true)
    private val choices = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val value = ctx.text("", 11f, T.AMBER, T.medium, 0.08f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val empty = ctx.text("No Look applied", 10f, T.IVORY, T.medium).apply { gravity = Gravity.CENTER }

    init {
        setBackgroundColor(T.CARBON)
        names.addView(namesRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        follow.addView(followIcon, LinearLayout.LayoutParams(dpi(14f), dpi(14f)))
        follow.addView(followText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(5f) })
        follow.setOnClickListener { onFollow?.invoke() }
        addView(names); addView(divider); addView(follow); addView(choices); addView(slider); addView(value); addView(empty)
    }

    private var mode = ArtMode.OFF
    private var active = 0

    /** Shows [mode]'s controls with control [active] selected. Rebuilds rows only when the look changes. */
    fun bind(mode: ArtMode, v: FloatArray, active: Int, follows: Boolean) {
        val rebuilt = mode != this.mode || namesRow.childCount == 0
        this.mode = mode; this.active = active
        val off = mode == ArtMode.OFF || mode.controls.isEmpty()
        empty.visibility = if (off) VISIBLE else GONE
        for (x in listOf(names, divider, follow, slider, value)) x.visibility = if (off) GONE else VISIBLE
        if (off) { choices.visibility = GONE; place(); return }

        if (rebuilt) {
            namesRow.removeAllViews()
            mode.controls.forEachIndexed { i, c ->
                namesRow.addView(context.text(c.label, 10f, T.IVORY, T.medium, 0.08f, caps = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { onSelect?.invoke(i) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { if (i > 0) leftMargin = dpi(14f) })
            }
            names.scrollTo(0, 0)
        }
        for (i in 0 until namesRow.childCount) (namesRow.getChildAt(i) as TextView).setTextColor(if (i == active) T.AMBER else T.IVORY)
        namesRow.getChildAt(active)?.let { names.reveal(it, smooth = !rebuilt) }

        // Follow guide: Ivory outline off, Amber on
        follow.visibility = if (mode.follows) VISIBLE else INVISIBLE
        val fc = if (follows) T.AMBER else T.IVORY
        follow.background = pill(T.CARBON, dp(13f), fc, dpi(1f).coerceAtLeast(1))
        followIcon.setColorFilter(fc); followText.setTextColor(fc)

        val c = mode.controls[active]
        val variation = v.getOrElse(5) { 0f }.roundToInt().coerceIn(0, (mode.variations.size - 1).coerceAtLeast(0))
        if (c.choices) {
            choices.visibility = VISIBLE
            choices.removeAllViews()
            mode.variations.forEachIndexed { i, name ->
                choices.addView(context.text(name, 10f, if (i == variation) T.AMBER else T.IVORY, T.medium, 0.08f, caps = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { onChoice?.invoke(i) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { if (i > 0) leftMargin = dpi(14f) })
            }
        } else choices.visibility = GONE

        if (c.slot < 0) { slider.visibility = GONE; value.visibility = GONE }
        else {
            // Leaks · Off: the amount has nothing to act on, so the slider dims and reads 0
            val dormant = c.choices && mode.variations.getOrNull(variation) == "OFF"
            slider.enabledLook = !dormant
            slider.value = v[c.slot]
            value.alpha = if (dormant) 0.4f else 1f
            setValue(if (dormant) 0f else v[c.slot])
        }
        place()
    }

    /** The readout for the active control (degrees for Tilt Shift's angle). */
    fun setValue(f: Float) {
        val c = mode.controls.getOrNull(active) ?: return
        value.text = if (c.degrees) "${((f - 0.5f) * 90f).roundToInt()}°" else "${(f * 100).roundToInt()}"
    }

    private fun lp(v: View, x: Float, y: Float, w: Int, h: Int) {
        v.layoutParams = LayoutParams(w, h).apply { leftMargin = dpi(x); topMargin = dpi(y) }
    }

    /** Natural height in dp for the current control. */
    private fun natural(): Float {
        if (empty.visibility == VISIBLE) return 72f
        val hasChoices = choices.visibility == VISIBLE; val hasSlider = slider.visibility == VISIBLE
        return 4f + 32f + 6f + (if (hasChoices) 26f + (if (hasSlider) 6f else 0f) else 0f) + (if (hasSlider) 18f else 0f) + 12f
    }

    private fun place() {
        val w = (width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - dpi(32f)))
        val wd = w / dp(1f)
        if (empty.visibility == VISIBLE) { lp(empty, 0f, 0f, w, dpi(72f)); requestLayout(); return }
        val compact = dp(natural()) > available
        val top = if (compact) 0f else 4f
        val gap = if (compact) 4f else 6f
        lp(names, 0f, top, dpi(wd - 149f), dpi(32f))
        lp(divider, wd - 139f, top + 7f, dpi(1f), dpi(18f))
        lp(follow, wd - 128f, top + 3f, dpi(126f), dpi(26f))
        var y = top + 32f + gap
        if (choices.visibility == VISIBLE) { lp(choices, 0f, y, w, dpi(26f)); y += 26f + gap }
        lp(slider, 0f, y, dpi(wd - 40f), dpi(18f))
        lp(value, wd - 30f, y, dpi(30f), dpi(18f))
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (w != ow) post { place() } }

    @Suppress("unused") private val locale = Locale.US
}
