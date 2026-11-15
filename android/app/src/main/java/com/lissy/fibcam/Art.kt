package com.lissy.fibcam

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Values per mode, always six: 0 intensity, 1..3 the mode's own sliders (p1..p3), 4 Feather (Tilt Shift)
 * or Depth (the others), 5 Holga's leak style. Names that are empty are hidden in the UI.
 * Every mode starts at 50% intensity; the shaders map that through each mode's own curve.
 */
enum class ArtMode(
    val label: String,
    val sliders: List<Pair<String, Float>>,
    val about: String = "",
    /** A set of named variations kept in values[5] (Holga's leak styles, Expired's colour drift). */
    val variations: List<String> = emptyList(),
    val variationLabel: String = "",
    val defaultVariation: Int = 0,
) {
    OFF("OFF", emptyList()),
    TILT_SHIFT("TILT SHIFT", listOf("Intensity" to 0.5f, "Focus" to 0.55f, "Width" to 0.22f, "Angle" to 0.5f, "Feather" to 0.5f)),
    HOLGA("HOLGA", listOf("Intensity" to 0.5f, "Vignette" to 0.55f, "Softness" to 0.4f, "Leaks" to 0.45f, "Depth" to 0.3f),
        variations = listOf("OFF", "WARM", "COOL", "NEUTRAL", "MIXED", "RANDOM"), variationLabel = "LEAK", defaultVariation = 1),
    PINHOLE("PINHOLE", listOf("Intensity" to 0.5f, "Softness" to 0.4f, "Vignette" to 0.5f, "" to 0f, "Depth" to 0f)),
    GRAINY_FILM("GRAINY FILM", listOf("Intensity" to 0.5f, "Grain" to 0.5f, "Contrast" to 0.55f, "" to 0f, "Depth" to 0.35f)),
    CROSS_PROCESS("CROSS PROCESS", listOf("Intensity" to 0.5f, "Colour" to 0.5f, "" to 0f, "" to 0f, "Depth" to 0.35f)),
    DREAM_LENS("DREAM LENS", listOf("Intensity" to 0.5f, "Glow" to 0.5f, "Softness" to 0.4f, "" to 0f, "Depth" to 0.35f)),
    INFRARED("INFRARED", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Halation" to 0.3f, "" to 0f, "Depth" to 0.35f),
        about = "Infrared-inspired: a simulation from visible light, not real infrared."),
    CYANOTYPE("CYANOTYPE", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Paper" to 0.5f, "" to 0f, "Depth" to 0.35f)),
    BLEACH_BYPASS("BLEACH BYPASS", listOf("Intensity" to 0.5f, "Saturation" to 0.35f, "Contrast" to 0.5f, "" to 0f, "Depth" to 0.35f)),
    // The newer looks are colour and tone first: Depth starts at 0 and stays optional.
    KINO("KINO", listOf("Intensity" to 0.5f, "Halation" to 0.5f, "Coolness" to 0.5f, "Grain" to 0.35f, "Depth" to 0f)),
    EXPIRED("EXPIRED", listOf("Intensity" to 0.5f, "Age" to 0.5f, "Grain" to 0.45f, "" to 0f, "Depth" to 0f),
        variations = listOf("WARM", "MAGENTA", "COOL", "RANDOM"), variationLabel = "COLOUR", defaultVariation = 1),
    REDSCALE("REDSCALE", listOf("Intensity" to 0.5f, "Tone" to 0.45f, "Grain" to 0.3f, "" to 0f, "Depth" to 0f)),
    SOLARISED("SOLARISED", listOf("Intensity" to 0.5f, "Reversal" to 0.7f, "Threshold" to 0.45f, "Contour" to 0.35f, "Depth" to 0f)),
    INSTANT("INSTANT", listOf("Intensity" to 0.5f, "Warmth" to 0.55f, "Fade" to 0.5f, "Softness" to 0.4f, "Depth" to 0f));

    val defaults: FloatArray get() = FloatArray(6) { i -> if (i == 5) defaultVariation.toFloat() else sliders.getOrNull(i)?.second ?: 0f }
    /** Whether variation [i] is the random one (it gets a fresh, stable seed when chosen). */
    fun isRandom(i: Int) = variations.getOrNull(i) == "RANDOM"
    /** The sliders to show: the padding entries (no name) are skipped, but indexes stay put. */
    val shown: List<Int> get() = sliders.indices.filter { sliders[it].first.isNotEmpty() }
    val fileTag: String get() = name.lowercase().replace('_', '-')
    val hasDepth: Boolean get() = sliders.getOrNull(4)?.first == "Depth"
    /**
     * Follow guide is offered wherever there's a focus mask: Tilt Shift's band, and Depth on every other look.
     * It only decides where the sharp region sits, never how strong the effect is: Depth 0 stays Depth 0.
     */
    val follows: Boolean get() = this == TILT_SHIFT || hasDepth
    /** Sliders that shape the focus region (they bring up its outline while you drag). */
    fun isFocusSlider(i: Int) = when (this) { TILT_SHIFT -> i in 1..4; else -> i == 4 && hasDepth }
}


/** A mode's saved shooting preferences: its six values and whether it follows the guide. */
class ArtState(val values: FloatArray, var follow: Boolean)

/**
 * Where a look stays sharp. Positions are 0..1 across the frame the look is measured against;
 * size (half-width of a band, or radius) and feather are in frame heights; angle in radians.
 * type: 0 none, 1 band, 2 radial. strength is Depth's blur amount (Tilt Shift uses its intensity instead).
 */
data class FocusMask(val type: Int = 0, val cx: Float = 0.5f, val cy: Float = 0.5f, val angle: Float = 0f,
                     val size: Float = 0.08f, val feather: Float = 1f, val strength: Float = 0f) {
    fun encode() = "$type,$cx,$cy,$angle,$size,$feather,$strength"
    companion object {
        fun decode(s: String?): FocusMask? = runCatching {
            val f = s!!.split(",").map { it.toFloat() }
            FocusMask(f[0].toInt(), f[1], f[2], f[3], f[4], f[5], f[6])
        }.getOrNull()
    }
}

/** What a guide offers the focus mask, worked out on a frame. */
class GuideCtx(val guide: Guide, val quadrant: Int, val triFlip: Boolean, val anchor: Int, val frame: RectF, val guideFrame: RectF)

object Focus {
    private const val HALF_PI = 1.5707964f

    /**
     * The geometry a guide suggests, in frame uv: the spiral's eye, a chosen thirds or φ point, the centre
     * (radial); the dominant diagonal of the triangles or dynamic symmetry, or a chosen corner diagonal (band).
     * Null for guides with no meaningful focal geometry (Off, the crop marks).
     */
    fun linked(g: GuideCtx): FocusMask? {
        val fr = g.frame; val gf = g.guideFrame
        if (fr.isEmpty) return null
        fun uv(x: Float, y: Float) = PointF((x - fr.left) / fr.width(), (y - fr.top) / fr.height())
        fun radial(x: Float, y: Float) = uv(x, y).let { FocusMask(2, it.x, it.y, 0f) }
        fun band(x0: Float, y0: Float, x1: Float, y1: Float): FocusMask {
            val m = uv((x0 + x1) / 2, (y0 + y1) / 2)
            return FocusMask(1, m.x, m.y, kotlin.math.atan2(y1 - y0, x1 - x0))   // screen px angle = corrected-space angle
        }
        val corners = listOf(PointF(gf.left, gf.top), PointF(gf.right, gf.top), PointF(gf.left, gf.bottom), PointF(gf.right, gf.bottom))
        return when (g.guide) {
            Guide.SPIRAL -> Geo.goals(Guide.SPIRAL, gf, g.quadrant, g.triFlip, 1).firstOrNull()?.let { radial(it.x, it.y) }
            Guide.THIRDS, Guide.PHI_GRID -> {
                val a = if (g.guide == Guide.THIRDS) 1f / 3f else (1f - 1f / 1.618034f)
                val x = if (g.anchor % 2 == 0) gf.left + a * gf.width() else gf.right - a * gf.width()
                val y = if (g.anchor < 2) gf.top + a * gf.height() else gf.bottom - a * gf.height()
                radial(x, y)
            }
            Guide.CENTRE -> radial(gf.centerX(), gf.centerY())
            Guide.TRIANGLES, Guide.DYNAMIC ->
                if (!g.triFlip) band(gf.left, gf.top, gf.right, gf.bottom) else band(gf.right, gf.top, gf.left, gf.bottom)
            Guide.DIAGONALS -> {
                val c = corners[g.anchor.coerceIn(0, 3)]
                val dx = if (g.anchor % 2 == 0) 1f else -1f; val dy = if (g.anchor < 2) 1f else -1f
                val e = Geo.exit(c, dx, dy, gf)
                band(c.x, c.y, e.x, e.y)
            }
            else -> null
        }
    }

    /**
     * The mask a mode uses right now. Follow guide on (Tilt Shift, Dream Lens): the guide's geometry.
     * Off: Tilt Shift's own band (Focus, Width, Angle, Feather); for Depth, a soft circle on the subject
     * you tapped (or the middle before any tap).
     */
    fun resolve(mode: ArtMode, v: FloatArray, follow: Boolean, g: GuideCtx?, subject: PointF?): FocusMask {
        val link = if (follow && mode.follows && g != null) linked(g) else null
        return when {
            mode == ArtMode.TILT_SHIFT -> {
                val size = 0.02f + v[2] * 0.3f
                link?.copy(size = if (link.type == 2) size * 0.8f + 0.04f else size, feather = v[4])
                    ?: FocusMask(1, 0.5f, v[1], (v[3] - 0.5f) * HALF_PI, size, v[4], 0f)
            }
            mode.hasDepth -> link?.copy(size = if (link.type == 2) 0.1f else 0.05f, feather = 0.9f, strength = v[4])
                ?: FocusMask(2, subject?.x ?: 0.5f, subject?.y ?: 0.5f, 0f, 0.08f, 1f, v[4])
            else -> FocusMask()
        }
    }
}

/**
 * A photo's look: the mode and its values, the randomness it was shot with, whether it followed the guide,
 * and the focus mask in the photo's own coordinates (0..1 across the whole picture) — enough to redo it exactly.
 */
data class Recipe(val mode: ArtMode, val values: FloatArray, val seed: Float, val follow: Boolean = false, val mask: FocusMask = FocusMask()) {
    fun encode() = "${mode.name}|${values.joinToString(",")}|$seed|v2|${if (follow) 1 else 0}|${mask.encode()}"
    companion object {
        fun decode(s: String?): Recipe? = runCatching {
            val p = s!!.split("|")
            val m = ArtMode.valueOf(p[0])
            val v = Art.pad(m, p[1].split(",").map { it.toFloat() }.toFloatArray())
            if (p.getOrNull(3) == "v2") Recipe(m, v, p[2].toFloat(), p[4] == "1", FocusMask.decode(p[5]) ?: FocusMask())
            else {   // older photos: a focus point only
                val f = p.getOrNull(3)?.split(",")?.map { it.toFloat() }
                Recipe(m, v, p[2].toFloat(), false, Focus.resolve(m, v, false, null, PointF(f?.getOrNull(0) ?: 0.5f, f?.getOrNull(1) ?: 0.5f)))
            }
        }.getOrNull()
    }
}

object Art {
    /** AGSL needs Android 13. On older phones the Art layer explains that instead of crashing. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** A mode's own saved shooting preferences, or its calibrated factory defaults on first use. */
    fun loadState(prefs: SharedPreferences, mode: ArtMode): ArtState {
        val s = prefs.getString("art2:${mode.name}", null) ?: return migrate(prefs, mode)
        return runCatching {
            val p = s.split("|")
            ArtState(pad(mode, p[0].split(",").map { it.toFloat() }.toFloatArray()), p.getOrNull(1) == "1")
        }.getOrDefault(ArtState(mode.defaults, false))
    }

    /**
     * Settings from before per-look state (key "artv:"): the look's own sliders carry over; intensity
     * starts at the new calibrated 50%, because the intensity curves changed underneath it.
     */
    private fun migrate(prefs: SharedPreferences, mode: ArtMode): ArtState {
        val old = prefs.getString("artv:${mode.name}", null) ?: return ArtState(mode.defaults, false)
        val v = runCatching { old.split(",").map { it.toFloat() } }.getOrNull() ?: return ArtState(mode.defaults, false)
        val d = mode.defaults
        val out = FloatArray(6) { i -> if (i in 1..4 && i < v.size && mode.sliders.getOrNull(i)?.first?.isNotEmpty() == true) v[i] else d[i] }
        return ArtState(out, false)
    }

    fun saveState(prefs: SharedPreferences, mode: ArtMode, st: ArtState) {
        if (mode == ArtMode.OFF) return
        prefs.edit().putString("art2:${mode.name}", st.values.joinToString(",") + "|" + (if (st.follow) 1 else 0)).apply()
    }

    /** Back to this mode's factory defaults; other modes are untouched. */
    fun resetState(prefs: SharedPreferences, mode: ArtMode): ArtState {
        prefs.edit().remove("art2:${mode.name}").apply()
        return ArtState(mode.defaults, false)
    }

    fun pad(mode: ArtMode, v: FloatArray): FloatArray = FloatArray(6) { i -> v.getOrNull(i) ?: mode.defaults[i] }

    @RequiresApi(33)
    fun newShader(mode: ArtMode): RuntimeShader? =
        if (mode == ArtMode.OFF) null else runCatching { RuntimeShader(ArtShaders.source(mode)) }.getOrNull()

    @RequiresApi(33)
    fun setUniforms(sh: RuntimeShader, v: FloatArray, x: Float, y: Float, w: Float, h: Float, seed: Float, m: FocusMask) {
        fun set(name: String, vararg f: Float) { runCatching { sh.setFloatUniform(name, f) } }
        set("org", x, y); set("res", w, h)
        set("amt", v.getOrElse(0) { 0f }); set("p1", v.getOrElse(1) { 0f })
        set("p2", v.getOrElse(2) { 0f }); set("p3", v.getOrElse(3) { 0f })
        set("seed", seed); set("lks", v.getOrElse(5) { 0f })
        set("msk", m.cx, m.cy, m.angle, m.type.toFloat()); set("msz", m.size, m.feather, m.strength)
    }

    /**
     * A look's preview thumbnail: the look at its factory defaults on a small copy of a photo, with no
     * focus effects, so a picker can show what each look does to *this* picture.
     */
    @RequiresApi(33)
    fun thumbnail(src: Bitmap, mode: ArtMode, seed: Float = 0.37f): Bitmap =
        if (mode == ArtMode.OFF) src else {
            val v = mode.defaults.also { if (mode != ArtMode.TILT_SHIFT) it[4] = 0f }
            render(src, Recipe(mode, v, seed), if (mode == ArtMode.TILT_SHIFT) Focus.resolve(mode, v, false, null, null) else FocusMask())
        }

    /** Runs a mode over a whole bitmap on the GPU and returns a new software bitmap. */
    @RequiresApi(33)
    fun render(src: Bitmap, r: Recipe, m: FocusMask? = null): Bitmap {
        val sh = newShader(r.mode) ?: return src
        val w = src.width; val h = src.height
        setUniforms(sh, r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed, m ?: r.mask)
        sh.setInputShader("content", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("art").apply { setPosition(0, 0, w, h) }
        val c = node.beginRecording()
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh })
        node.endRecording()
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            val img = reader.acquireNextImage()
            val hb = img.hardwareBuffer ?: error("no buffer")
            val out = Bitmap.wrapHardwareBuffer(hb, ColorSpace.get(ColorSpace.Named.SRGB))!!.copy(Bitmap.Config.ARGB_8888, false)
            hb.close(); img.close()
            return out
        } finally {
            renderer.destroy(); reader.close(); node.discardDisplayList()
        }
    }

    /** Holga is a square camera: its copies come out 1:1. */
    fun finish(b: Bitmap, mode: ArtMode): Bitmap {
        if (mode != ArtMode.HOLGA || b.width == b.height) return b
        val s = minOf(b.width, b.height)
        return Bitmap.createBitmap(b, (b.width - s) / 2, (b.height - s) / 2, s, s)
    }
}
