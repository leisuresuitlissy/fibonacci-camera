package com.lissy.fibcam

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import kotlin.math.floor

/**
 * The app's haptics, all short and light, all through the system's touch-feedback channel (so Android's
 * own "touch feedback" setting is respected too). The in-app setting switches every one of them off.
 */
object Haptics {
    var enabled = true

    /** A slider detent: the lightest tick the phone has. */
    fun tick(v: View) {
        if (!enabled) return
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK else HapticFeedbackConstants.CLOCK_TICK)
    }

    /** A slightly firmer tick, for a slider's neutral point (0 EV). */
    fun neutral(v: View) {
        if (!enabled) return
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** Something landed: the subject on a guide point. */
    fun confirm(v: View) {
        if (!enabled) return
        v.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }
}

/**
 * Detents for a continuous slider, like a fine camera dial: a tick each time the thumb crosses a tenth
 * of its travel (and at both ends), in either direction. The slider itself never snaps.
 * Hysteresis: once a tick fires, the thumb has to move clearly past the boundary again before it can
 * fire again, so hovering on a line doesn't buzz. Only drags call [move]; restoring values never does.
 */
class Detents(private val neutral: Float? = null) {
    private val h = 0.15f              // in tenths of travel
    private var cell = 0
    private var end = -1               // 0 or 10 while resting on an end stop

    fun begin(f: Float) { val x = f * 10f; cell = floor(x).toInt().coerceIn(0, 10); end = endOf(x) }

    fun move(v: View, f: Float) {
        val x = (f * 10f).coerceIn(0f, 10f)
        val e = endOf(x)
        if (e >= 0 && e != end) { end = e; cell = e; Haptics.tick(v); return }
        if (e < 0) end = -1
        if (x >= cell + 1 + h || x < cell - h) {
            val old = cell
            cell = floor(x).toInt().coerceIn(0, 10)
            val n = neutral?.let { it * 10f }
            if (n != null && minOf(old, cell) < n && n <= maxOf(old, cell) + 0.0001f && crosses(old, cell, n)) Haptics.neutral(v) else Haptics.tick(v)
        }
    }

    private fun crosses(a: Int, b: Int, n: Float): Boolean {
        val lo = minOf(a, b) + 1; val hi = maxOf(a, b)
        return n >= lo - 0.0001f && n <= hi + 0.0001f
    }
    private fun endOf(x: Float) = if (x <= 0.001f) 0 else if (x >= 9.999f) 10 else -1
}
