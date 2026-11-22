package com.lissy.fibcam

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * CI only: proves each look comes out of Edit a copy exactly as it does from the camera.
 * The same photo and settings go through both save paths:
 *   camera  — Art.finish(Art.render(photo, recipe, the camera's mask)), as MainActivity saves a look photo
 *   editor  — EditRender.copy(photo, the editor's whole-photo crop, recipe, CropView.cropMask), as Save a copy does
 * and every pixel is compared. Results go to files/lab/parity/report.txt.
 */
object EditParity {
    private const val SEED = 0.37f

    /** Call from a thread with a Looper (CropView's gesture detectors need one), never the main thread. */
    fun run(ctx: Context): File? {
        if (Build.VERSION.SDK_INT < 33) return null
        appCtx = ctx.applicationContext
        val dir = File(ctx.getExternalFilesDir("lab"), "parity").apply { deleteRecursively(); mkdirs() }
        val report = StringBuilder("photo\tlook\tsettings\tcamera\teditor\tmax Δ\tmean Δ\tpixels Δ>2\n")
        for ((pname, photo) in listOf("pattern" to pattern(), "scene" to scene())) {
            for ((mode, _, _) in Catalog.looks) {
                if (mode == ArtMode.OFF) continue
                val cases = mutableListOf("defaults" to mode.defaults)
                cases += "all controls 80%" to mode.defaults.also { v ->
                    for (c in mode.controls) if (c.slot >= 0 && c.slot != 5) v[c.slot] = 0.8f
                    if (mode.variations.size > 1) v[5] = 1f
                }
                for ((cname, v) in cases) report.append(compare(dir, pname, photo, mode, cname, v, follow = false))
                if (mode.follows) report.append(compare(dir, pname, photo, mode, "defaults, Follow guide on Thirds", mode.defaults, follow = true))
            }
        }
        File(dir, "report.txt").writeText(report.toString())
        return dir
    }

    private fun compare(dir: File, pname: String, photo: Bitmap, mode: ArtMode, cname: String, raw: FloatArray, follow: Boolean): String {
        val w = photo.width; val h = photo.height
        // ---- camera: the whole frame, the mask the viewfinder would give (MainActivity.liveMask / placeTilt)
        val v = raw.copyOf()
        val fr = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val gf = if (mode == ArtMode.HOLGA) RectF(0f, (h - w) / 2f, w.toFloat(), (h + w) / 2f) else RectF(fr)   // OverlayView.squareGuide
        val g = GuideCtx(Guide.THIRDS, 0, false, 0, fr, gf)
        if (mode == ArtMode.TILT_SHIFT && follow) Focus.linked(g)?.let { a ->
            a.angle?.let { t0 -> var t = t0; while (t > 1.5707964f) t -= 3.1415927f; while (t < -1.5707964f) t += 3.1415927f; v[3] = t / 1.5707964f + 0.5f }
            v[1] = Focus.tiltFocusThrough(a.u, a.v, (v[3] - 0.5f) * 1.5707964f, w.toFloat() / h)
        }
        val camMask = Focus.resolve(mode, v, follow, g, null, null, w.toFloat() / h)
        val cam = Art.finish(Art.render(photo, Recipe(mode, v, SEED, follow, camMask)), mode)

        // ---- editor: an untouched photo opened in Edit a copy (Plastic squares the crop, as it does there)
        val crop = CropView(appCtx).apply {
            bmp = photo; guide = Guide.THIRDS; quadrant = 0; triFlip = false; anchor = 0
            aspect = if (mode == ArtMode.HOLGA) 1f else w.toFloat() / h
            reset()
        }
        // as CropActivity.selectLook sets it up, and as Save a copy renders it
        val r = Recipe(mode, raw.copyOf(), SEED, follow).let { if (!follow) crop.pinTilt(it) else it }
        val ed = EditRender.copy(photo, crop.cx, crop.cy, crop.cw, crop.ch, 0f, 1f, r, crop.renderMask(r), crop.renderFactor(r))

        val line = StringBuilder("$pname\t${mode.name}\t$cname\t${cam.width}×${cam.height}\t${ed.width}×${ed.height}\t")
        if (cam.width != ed.width || cam.height != ed.height) {
            line.append("size differs\t-\t-\n")
        } else {
            val a = IntArray(cam.width * cam.height); val b = IntArray(a.size)
            cam.getPixels(a, 0, cam.width, 0, 0, cam.width, cam.height); ed.getPixels(b, 0, ed.width, 0, 0, ed.width, ed.height)
            var mx = 0; var sum = 0L; var over = 0
            for (i in a.indices) {
                val d = max(abs(Color.red(a[i]) - Color.red(b[i])), max(abs(Color.green(a[i]) - Color.green(b[i])), abs(Color.blue(a[i]) - Color.blue(b[i]))))
                if (d > mx) mx = d; sum += d; if (d > 2) over++
            }
            line.append(String.format(Locale.US, "%d\t%.3f\t%.3f%%\n", mx, sum.toDouble() / a.size, over * 100.0 / a.size))
            if (mx > 2 || cname == "defaults") {
                val tag = "${pname}_${mode.name}_" + cname.replace(Regex("[^A-Za-z0-9]+"), "-")
                File(dir, "$tag-camera.png").outputStream().use { cam.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(dir, "$tag-editor.png").outputStream().use { ed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        cam.recycle(); ed.recycle()
        return line.toString()
    }

    private lateinit var appCtx: Context

    /** The look lab's detailed test card: fine checks, text and lines, so blur, grain and edges show. */
    private fun pattern(): Bitmap {
        val w = 1200; val h = 1600
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until h step 8) for (x in 0 until w step 8) {
            pt.color = if (((x / 8) + (y / 8)) % 2 == 0) Color.rgb(40 + x * 180 / w, 60, 40 + y * 180 / h) else Color.rgb(235, 225 - y * 60 / h, 200 - x * 80 / w)
            c.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, pt)
        }
        pt.color = Color.BLACK; pt.textSize = 30f
        for (y in 60 until h step 110) for (x in 20 until w step 230) c.drawText("Aa 1:2 %", x.toFloat(), y.toFloat(), pt)
        pt.style = Paint.Style.STROKE; pt.strokeWidth = 2f
        for (i in 0..24) c.drawLine(0f, i * 70f, w.toFloat(), i * 70f + 40f, pt)
        return src
    }

    /** A photo-like scene: sky gradient, foliage greens, skin tones, deep shadow and bright highlights. */
    private fun scene(): Bitmap {
        val w = 1200; val h = 1600
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(src)
        val pt = Paint(Paint.ANTI_ALIAS_FLAG)
        pt.shader = LinearGradient(0f, 0f, 0f, h * 0.55f, Color.rgb(70, 120, 190), Color.rgb(235, 200, 160), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h * 0.55f, pt)
        pt.shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), Color.rgb(60, 110, 50), Color.rgb(20, 35, 20), Shader.TileMode.CLAMP)
        c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), pt)
        pt.shader = RadialGradient(w * 0.75f, h * 0.18f, 120f, Color.WHITE, Color.argb(0, 255, 240, 200), Shader.TileMode.CLAMP)
        c.drawCircle(w * 0.75f, h * 0.18f, 120f, pt)
        pt.shader = RadialGradient(w * 0.42f, h * 0.5f, 220f, Color.rgb(232, 180, 150), Color.rgb(170, 110, 85), Shader.TileMode.CLAMP)
        c.drawOval(w * 0.42f - 170f, h * 0.5f - 230f, w * 0.42f + 170f, h * 0.5f + 230f, pt)
        pt.shader = null; pt.color = Color.rgb(12, 10, 10)
        c.drawRect(w * 0.08f, h * 0.62f, w * 0.3f, h * 0.95f, pt)
        pt.color = Color.WHITE; pt.textSize = 44f
        c.drawText("Crooked Light 1:2", w * 0.1f, h * 0.06f, pt)
        return src
    }
}
