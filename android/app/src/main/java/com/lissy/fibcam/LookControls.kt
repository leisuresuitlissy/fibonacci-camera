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
 * names across the full width with the active one in Amber, an optional row of choices (Holga's leaks,
 * Expired's colour shift), then one slider for the active adjustment. Follow guide lives in Compose; when it
 * places Tilt Shift's band, the controls it overrides (Focus, and Angle where the guide sets one) show the
 * guide's value, dimmed, until it is turned off.
 * When the space is shorter than the design's height the gaps tighten; nothing reflows sideways.
 */
class LookControls(ctx: Context) : FrameLayout(ctx) {
    var onSelect: ((Int) -> Unit)? = null
    var onChoice: ((Int) -> Unit)? = null
    var onReset: (() -> Unit)? = null
    val slider = ValueSlider(ctx)
    /** The height this block may use (px); set by the screen layout. */
    var available = Int.MAX_VALUE

    private fun dp(v: Float) = context.dp(v)
    private fun dpi(v: Float) = dp(v).roundToInt()

    private val names = FadeScroller(ctx, dp(24f))
    private val namesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val divider = View(ctx).apply { setBackgroundColor(T.alpha(Color.WHITE, 0.14f)) }
    /** Reset all: this Look's sliders and choices back to their starting 50%. Dimmed when there's nothing to reset. */
    private val reset = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_reset_ev) }, LinearLayout.LayoutParams(dpi(12f), dpi(12f)))
        addView(ctx.text("Reset all", 11f, T.IVORY, T.medium, caps = true), LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = dpi(5f) })
        background = android.graphics.drawable.InsetDrawable(pill(T.CARBON, dp(13f), T.IVORY, dpi(1f).coerceAtLeast(1)), 0, dpi(9f), 0, dpi(9f))
        setPadding(dpi(10f), 0, dpi(10f), 0)
        contentDescription = "Reset all settings for this Look"
    }
    private val choices = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val value = ctx.text("", 12f, T.AMBER, T.medium, 0.08f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val empty = ctx.text("No Look applied", 11f, T.IVORY, T.medium).apply { gravity = Gravity.CENTER }

    init {
        setBackgroundColor(T.CARBON)
        names.addView(namesRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        reset.setOnClickListener { onReset?.invoke() }
        addView(names); addView(divider); addView(reset); addView(choices); addView(slider); addView(value); addView(empty)
    }

    private var mode = ArtMode.OFF
    private var active = 0

    /**
     * Shows [mode]'s controls with control [active] selected. [locked] maps value slots the guide is setting
     * to the value it sets (shown dimmed). Rebuilds rows only when the look changes.
     */
    fun bind(mode: ArtMode, v: FloatArray, active: Int, locked: Map<Int, Float> = emptyMap()) {
        val rebuilt = mode != this.mode || namesRow.childCount == 0
        this.mode = mode; this.active = active
        val off = mode == ArtMode.OFF || mode.controls.isEmpty()
        empty.visibility = if (off) VISIBLE else GONE
        for (x in listOf(names, divider, reset, slider, value)) x.visibility = if (off) GONE else VISIBLE
        if (off) { choices.visibility = GONE; place(); return }

        if (rebuilt) {
            namesRow.removeAllViews()
            mode.controls.forEachIndexed { i, c ->
                namesRow.addView(context.text(c.label, 11f, T.IVORY, T.medium, caps = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { onSelect?.invoke(i) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { if (i > 0) leftMargin = dpi(14f) })
            }
            names.scrollTo(0, 0)
        }
        for (i in 0 until namesRow.childCount) (namesRow.getChildAt(i) as TextView).apply { setTextColor(if (i == active) T.AMBER else T.IVORY); typeface = if (i == active) T.semibold else T.medium }
        namesRow.getChildAt(active)?.let { names.reveal(it, smooth = !rebuilt) }
        val d0 = mode.defaults
        val changed = d0.indices.any { kotlin.math.abs(v.getOrElse(it) { d0[it] } - d0[it]) > 0.001f }
        reset.isEnabled = changed; reset.alpha = if (changed) 1f else 0.4f

        val c = mode.controls[active]
        val variation = v.getOrElse(5) { 0f }.roundToInt().coerceIn(0, (mode.variations.size - 1).coerceAtLeast(0))
        if (c.choices) {
            choices.visibility = VISIBLE
            choices.removeAllViews()
            mode.variations.forEachIndexed { i, name ->
                choices.addView(context.text(name, 11f, if (i == variation) T.AMBER else T.IVORY, if (i == variation) T.semibold else T.medium, caps = true).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener { onChoice?.invoke(i) }
                }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { if (i > 0) leftMargin = dpi(14f) })
            }
        } else choices.visibility = GONE

        if (c.slot < 0) { slider.visibility = GONE; value.visibility = GONE }
        else {
            // Leaks · Off: the amount has nothing to act on, so the slider dims and reads 0
            val dormant = c.choices && mode.variations.getOrNull(variation) == "OFF"
            val guided = locked[c.slot]
            slider.enabledLook = !dormant
            slider.locked = guided != null
            slider.value = guided ?: v[c.slot]
            value.alpha = if (dormant || guided != null) 0.4f else 1f
            setValue(if (dormant) 0f else guided ?: v[c.slot])
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
        lp(names, 0f, top, dpi(wd - 121f), dpi(32f))
        lp(divider, wd - 111f, top + 7f, dpi(1f), dpi(18f))
        lp(reset, wd - 100f, top - 6f, dpi(100f), dpi(44f))
        var y = top + 32f + gap
        if (choices.visibility == VISIBLE) { lp(choices, 0f, y, w, dpi(26f)); y += 26f + gap }
        lp(slider, 0f, y, dpi(wd - 40f), dpi(18f))
        lp(value, wd - 30f, y, dpi(30f), dpi(18f))
        requestLayout()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { super.onSizeChanged(w, h, ow, oh); if (w != ow) post { place() } }

    @Suppress("unused") private val locale = Locale.US
}
