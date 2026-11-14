package com.lissy.fibcam

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.graphics.BlendMode as AndroidBlend

/** The four ways two exposures can meet. */
enum class Blend(val label: String, val mode: AndroidBlend) {
    NORMAL("Normal", AndroidBlend.SRC_OVER),
    SCREEN("Screen", AndroidBlend.SCREEN),
    LIGHTEN("Lighten", AndroidBlend.LIGHTEN),
    MULTIPLY("Multiply", AndroidBlend.MULTIPLY);

    /** Strength is the layer's opacity: 0 = only the base, 1 = the full blend. */
    fun paint(strength: Float) = Paint(Paint.FILTER_BITMAP_FLAG).apply { blendMode = mode; alpha = (strength.coerceIn(0f, 1f) * 255).toInt() }
}

object Exposure {
    fun decode(cr: ContentResolver, uri: Uri, maxLong: Int = 0, mutable: Boolean = false): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(cr, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            d.isMutableRequired = mutable
            if (maxLong > 0) {
                val long = maxOf(info.size.width, info.size.height)
                var s = 1; while (long / (s * 2) >= maxLong) s *= 2
                if (s > 1) d.setTargetSampleSize(s)
            }
        }

    /**
     * Lays [top] over a mutable copy of [base], scaled to cover it and centred — exactly how the
     * viewfinder shows the first exposure over the live picture.
     */
    fun coverOnto(base: Bitmap, top: Bitmap, blend: Blend, strength: Float): Bitmap {
        val out = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true)
        val s = maxOf(out.width.toFloat() / top.width, out.height.toFloat() / top.height)
        val m = Matrix().apply {
            postTranslate(-top.width / 2f, -top.height / 2f); postScale(s, s); postTranslate(out.width / 2f, out.height / 2f)
        }
        Canvas(out).drawBitmap(top, m, blend.paint(strength))
        return out
    }

    /** Places [top] on [base] with a free transform: centre (in base px), width as a fraction of base width, rotation. */
    fun placeOnto(base: Bitmap, top: Bitmap, cx: Float, cy: Float, widthFrac: Float, rotDeg: Float, blend: Blend, strength: Float): Bitmap {
        val out = if (base.isMutable) base else base.copy(Bitmap.Config.ARGB_8888, true)
        val g = widthFrac * out.width / top.width
        val m = Matrix().apply {
            postTranslate(-top.width / 2f, -top.height / 2f); postScale(g, g); postRotate(rotDeg); postTranslate(cx, cy)
        }
        Canvas(out).drawBitmap(top, m, blend.paint(strength))
        return out
    }
}
