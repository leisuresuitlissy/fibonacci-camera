package com.lissy.fibcam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View

/** Small line icons drawn on a 24-unit grid, so there are no image assets to manage. */
class IconButton(ctx: Context, var icon: Icon, var tint: Int) : View(ctx) {
    enum class Icon { FLASH_OFF, FLASH_AUTO, FLASH_ON, COACH, FLIP, PHOTO }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    var glyphFraction = 0.5f

    override fun onDraw(c: Canvas) {
        val s = minOf(width, height) * glyphFraction
        c.save()
        c.translate((width - s) / 2f, (height - s) / 2f)
        c.scale(s / 24f, s / 24f)
        p.color = tint; p.strokeWidth = 1.7f; p.style = Paint.Style.STROKE
        when (icon) {
            Icon.FLASH_OFF, Icon.FLASH_AUTO, Icon.FLASH_ON -> {
                path.reset()
                path.moveTo(13f, 2f); path.lineTo(4.5f, 13.5f); path.lineTo(11f, 13.5f); path.lineTo(10f, 22f)
                path.lineTo(19.5f, 10.5f); path.lineTo(13f, 10.5f); path.close()
                if (icon == Icon.FLASH_ON) { p.style = Paint.Style.FILL_AND_STROKE }
                c.drawPath(path, p)
                p.style = Paint.Style.STROKE
                if (icon == Icon.FLASH_OFF) c.drawLine(3f, 3f, 21f, 21f, p)
                if (icon == Icon.FLASH_AUTO) {
                    p.strokeWidth = 1.5f
                    c.drawLine(17f, 22f, 19.5f, 15.5f, p); c.drawLine(19.5f, 15.5f, 22f, 22f, p); c.drawLine(18f, 19.8f, 21f, 19.8f, p)
                }
            }
            Icon.COACH -> {
                path.reset()
                path.addRoundRect(RectF(3f, 4f, 21f, 16f), 3f, 3f, Path.Direction.CW)
                c.drawPath(path, p)
                c.drawLine(8f, 16f, 6f, 20.5f, p); c.drawLine(6f, 20.5f, 11.5f, 16f, p)
                c.drawLine(7.5f, 8.5f, 16.5f, 8.5f, p); c.drawLine(7.5f, 11.8f, 13f, 11.8f, p)
            }
            Icon.FLIP -> {
                val r = RectF(4.5f, 4.5f, 19.5f, 19.5f)
                c.drawArc(r, 200f, 140f, false, p)
                c.drawArc(r, 20f, 140f, false, p)
                // arrowheads at the end of each arc
                c.drawLine(18.6f, 7.2f, 19.3f, 3.6f, p); c.drawLine(18.6f, 7.2f, 15.2f, 6.4f, p)
                c.drawLine(5.4f, 16.8f, 4.7f, 20.4f, p); c.drawLine(5.4f, 16.8f, 8.8f, 17.6f, p)
                p.style = Paint.Style.FILL; c.drawCircle(12f, 12f, 2.2f, p)
            }
            Icon.PHOTO -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, p)
                path.reset(); path.moveTo(4f, 17f); path.lineTo(9.5f, 11.5f); path.lineTo(13f, 15f); path.lineTo(15.5f, 12.5f); path.lineTo(20f, 17f)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL; c.drawCircle(16f, 9f, 1.4f, p)
            }
        }
        c.restore()
    }
}
