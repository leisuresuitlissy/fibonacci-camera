package com.lissy.fibcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Straighten dial: drag the ruler, ±45°, sticks at 0. Double-tap to level. */
class DialView(ctx: Context) : View(ctx) {
    var onChange: ((Float) -> Unit)? = null
    var value = 0f
        set(v) { field = v.coerceIn(-45f, 45f); invalidate() }

    private val d = resources.displayMetrics.density
    private val pxPerDeg = 6 * d
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF5F3EF.toInt(); strokeCap = Paint.Cap.ROUND }
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0B252.toInt(); strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFA3A09A.toInt(); textSize = 9 * d; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE0B252.toInt(); textSize = 11 * d; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private var lastX = 0f
    private var raw = 0f
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { value = 0f; onChange?.invoke(0f); return true }
    })

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val base = height * 0.62f
        for (g in -45..45) {
            val x = cx + (g - value) * pxPerDeg
            if (x < 8 * d || x > width - 8 * d) continue
            val major = g % 15 == 0; val mid = g % 5 == 0
            val h = when { major -> 16 * d; mid -> 10 * d; else -> 5 * d }
            tick.alpha = when { major -> 210; mid -> 120; else -> 60 }
            tick.strokeWidth = if (major) 1.5f * d else 1f * d
            c.drawLine(x, base - h, x, base, tick)
            if (major && g != 0) c.drawText((if (g > 0) "+" else "") + g + "°", x, base + 13 * d, lab)
        }
        c.drawLine(cx, base - 22 * d, cx, base + 2 * d, pointer)
        val v = if (abs(value) < 0.05f) "0°" else String.format(Locale.US, "%+.1f°", value)
        c.drawText(v, cx, base - 27 * d, big)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; raw = value; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> {
                raw = (raw - (e.x - lastX) / pxPerDeg).coerceIn(-45f, 45f)   // finger position kept unrounded
                lastX = e.x
                var v = raw
                if (abs(v) < 0.75f) v = 0f                    // a little grip at level
                v = (v * 2).roundToInt() / 2f                  // half-degree steps
                if (v != value) { value = v; onChange?.invoke(value) }
            }
        }
        return true
    }
}
