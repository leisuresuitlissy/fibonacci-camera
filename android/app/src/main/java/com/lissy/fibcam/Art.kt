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
 * Values per mode, always eight: 0 intensity, 1..3 the mode's own sliders (p1..p3), 4 Feather (Tilt Shift)
 * or Depth (the others), 5 the named variation (Holga's leak style, Expired's colour shift), 6 a fourth
 * parameter p4 (Holga grain, Instant texture), 7 Tilt Shift's Depth. Every mode starts at 50% intensity;
 * the shaders map that through each mode's own curve. [controls] is what the camera shows, in display order.
 */
class LookControl(
    val label: String,
    /** The value the slider edits, or -1 for a choice-only control. */
    val slot: Int,
    /** Choices kept in values[5] (shown above the slider when both exist). */
    val choices: Boolean = false,
    /** Shown as degrees (Tilt Shift's angle) instead of 0..100. */
    val degrees: Boolean = false,
)

private fun c(label: String, slot: Int, choices: Boolean = false, degrees: Boolean = false) = LookControl(label, slot, choices, degrees)
private val INTENSITY = c("Intensity", 0)
private val DEPTH = c("Depth", 4)
/** What Depth at 50% renders as: a gentle falloff (lower than the older looks' 30–35%). */
private const val DEPTH_50 = 0.25f

enum class ArtMode(
    val label: String,
    val sliders: List<Pair<String, Float>>,
    val controls: List<LookControl> = emptyList(),
    val about: String = "",
    /** A set of named variations kept in values[5] (Holga's leak styles, Expired's colour drift). */
    val variations: List<String> = emptyList(),
    val variationLabel: String = "",
    val defaultVariation: Int = 0,
    /** Defaults for values[6] and values[7]. */
    val extra: Pair<Float, Float> = 0f to 0f,
) {
    OFF("OFF", emptyList()),
    TILT_SHIFT("TILT SHIFT", listOf("Intensity" to 0.5f, "Focus" to 0.5f, "Width" to 0.3f, "Angle" to 0.5f, "Feather" to 0.5f),
        listOf(INTENSITY, c("Focus", 1), c("Width", 2), c("Angle", 3, degrees = true), c("Feather", 4), c("Depth", 7)), extra = 0f to DEPTH_50),
    HOLGA("HOLGA", listOf("Intensity" to 0.5f, "Vignette" to 0.55f, "Softness" to 0.3f, "Leaks" to 0.45f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Softness", 2), c("Vignette", 1), c("Leaks", 3, choices = true), c("Grain", 6), DEPTH),
        variations = listOf("OFF", "WARM", "COOL", "NEUTRAL", "MIXED", "RANDOM"), variationLabel = "LEAK", defaultVariation = 1, extra = 0.3f to 0f),
    PINHOLE("PINHOLE", listOf("Intensity" to 0.5f, "Softness" to 0.3f, "Vignette" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Softness", 1), c("Vignette", 2), DEPTH)),
    GRAINY_FILM("GRAINY FILM", listOf("Intensity" to 0.5f, "Grain" to 0.5f, "Contrast" to 0.55f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Grain", 1), c("Contrast", 2), DEPTH)),
    CROSS_PROCESS("CROSS PROCESS", listOf("Intensity" to 0.5f, "Colour" to 0.5f, "Contrast" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Colour", 1), c("Contrast", 2), DEPTH)),
    DREAM_LENS("DREAM LENS", listOf("Intensity" to 0.5f, "Glow" to 0.5f, "Softness" to 0.3f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Glow", 1), c("Softness", 2), DEPTH)),
    // Halation stays at its calibrated 0.3; the camera shows Contrast and Depth only.
    INFRARED("INFRARED", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Halation" to 0.3f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Contrast", 1), DEPTH),
        about = "Infrared-inspired: a simulation from visible light, not real infrared."),
    CYANOTYPE("CYANOTYPE", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Paper" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Contrast", 1), c("Paper", 2), DEPTH)),
    BLEACH_BYPASS("BLEACH BYPASS", listOf("Intensity" to 0.5f, "Saturation" to 0.35f, "Contrast" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Saturation", 1), c("Contrast", 2), DEPTH)),
    // The newer looks are colour and tone first: Depth starts at 0 and stays optional.
    KINO("KINO", listOf("Intensity" to 0.5f, "Halation" to 0.5f, "Coolness" to 0.5f, "Grain" to 0.35f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Halation", 1), c("Coolness", 2), c("Grain", 3), DEPTH)),
    EXPIRED("EXPIRED", listOf("Intensity" to 0.5f, "Age" to 0.5f, "Grain" to 0.45f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Age", 1), c("Colour shift", -1, choices = true), c("Grain", 2), DEPTH),
        variations = listOf("WARM", "MAGENTA", "COOL", "RANDOM"), variationLabel = "COLOUR", defaultVariation = 1),
    REDSCALE("REDSCALE", listOf("Intensity" to 0.5f, "Tone" to 0.45f, "Grain" to 0.3f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Tone", 1), c("Grain", 2), DEPTH)),
    SOLARISED("SOLARISED", listOf("Intensity" to 0.5f, "Reversal" to 0.7f, "Threshold" to 0.45f, "Contour" to 0.35f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Reversal", 1), c("Threshold", 2), c("Contour", 3), DEPTH)),
    // Texture is the film's grain (values[6]); the slight softness stays at its calibrated 0.4.
    INSTANT("INSTANT", listOf("Intensity" to 0.5f, "Warmth" to 0.55f, "Fade" to 0.5f, "Softness" to 0.4f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Warmth", 1), c("Fade", 2), c("Texture", 6), DEPTH), extra = 0.4f to 0f);

    /** Every slider starts at 50%; [calib] says what 50% looks like. */
    val defaults: FloatArray get() = FloatArray(Art.SLOTS) { i -> if (i == 5) defaultVariation.toFloat() else 0.5f }
    /**
     * The calibrated value each slot renders at 50% (the numbers in [sliders] and [extra]). Values below
     * 50% scale down to 0, above it up to 1, so the slider still covers the whole range.
     */
    val calib: FloatArray get() = FloatArray(Art.SLOTS) { i ->
        when (i) { 0 -> 0.5f; 5 -> 0.5f; 6 -> extra.first; 7 -> extra.second; else -> sliders.getOrNull(i)?.second ?: 0f }
    }
    /** Whether variation [i] is the random one (it gets a fresh, stable seed when chosen). */
    fun isRandom(i: Int) = variations.getOrNull(i) == "RANDOM"
    /** The value slots that have a slider, in display order (the editor uses these too). */
    val shown: List<Int> get() = controls.filter { it.slot >= 0 }.map { it.slot }
    fun labelOf(slot: Int): String = controls.firstOrNull { it.slot == slot }?.label ?: sliders.getOrNull(slot)?.first ?: ""
    val fileTag: String get() = name.lowercase().replace('_', '-')
    val hasDepth: Boolean get() = sliders.getOrNull(4)?.first == "Depth"
    /** The slot Depth lives in (Tilt Shift keeps Feather in 4). */
    val depthSlot: Int get() = if (this == TILT_SHIFT) 7 else 4
    /**
     * Follow guide is offered wherever there's a focus mask: Tilt Shift's band, and Depth on every other look.
     * It only decides where the sharp region sits, never how strong the effect is: Depth 0 stays Depth 0.
     */
    val follows: Boolean get() = this == TILT_SHIFT || hasDepth
    /** Sliders that shape the focus region (they bring up its outline while you drag). */
    fun isFocusSlider(i: Int) = when (this) { TILT_SHIFT -> i in 1..4 || i == 7; else -> i == 4 && hasDepth }
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

/**
 * What a guide offers the focus mask, worked out on a frame: [frame] is the visible camera image (the whole
 * photo), [guideFrame] the box the guide is drawn in (the same, or Holga's square inside it), [anchor]
 * which of the guide's anchors is chosen.
 */
class GuideCtx(val guide: Guide, val quadrant: Int, val triFlip: Boolean, val anchor: Int, val frame: RectF, val guideFrame: RectF, val trueSpiral: Boolean = false)

/** A guide anchor in frame uv, with the guide line through it if that line is unambiguous. */
class FocusAnchor(val u: Float, val v: Float, val angle: Float?)

/*
 * Follow guide moves the *simulated* sharp region of a Look (the Depth falloff, Tilt Shift's band). It never
 * drives the camera's optical autofocus or exposure: those stay on tap-to-focus, as before.
 */
object Focus {
    private const val HALF_PI = 1.5707964f

    /**
     * The chosen anchor of the active guide, from the same geometry that draws it (GuideShape), in frame uv.
     * Null when the guide has no focal point (Off, the crop marks).
     */
    fun linked(g: GuideCtx): FocusAnchor? {
        val fr = g.frame; val gf = g.guideFrame
        if (fr.isEmpty || !GuideShape.hasAnchors(g.guide)) return null
        val sh = GuideShape.build(g.guide, Box(gf.left, gf.top, gf.right, gf.bottom), g.quadrant, g.triFlip, g.trueSpiral, 1)
        val a = sh.anchors.getOrNull(g.anchor.coerceIn(0, (sh.anchors.size - 1).coerceAtLeast(0))) ?: return null
        return FocusAnchor((a.p.x - fr.left) / fr.width(), (a.p.y - fr.top) / fr.height(), a.angle)
    }

    /**
     * The mask a Look uses right now. [follow] is whether Follow guide is on *and* the guide has an anchor.
     * It only places the spatial part: Tilt Shift's band goes through the anchor (along the guide's line
     * where that is unambiguous, otherwise at your Angle); Depth's sharp circle centres on it. Width,
     * Feather, Depth and Intensity keep their own jobs, and a Look with Depth at 0 gains no blur.
     * Follow guide off: Tilt Shift's own Focus and Angle; Depth on the tapped subject (or the middle).
     * [tap]: a point tapped in the viewfinder (frame uv), which takes over from the guide until the guide changes.
     */
    fun resolve(mode: ArtMode, raw: FloatArray, follow: Boolean, g: GuideCtx?, subject: PointF?, tap: PointF? = null): FocusMask {
        val link = if (follow && mode.follows && g != null) linked(g) else null
        val v = Art.eff(mode, raw)
        return when {
            // a point you tapped wins: the sharp region goes exactly there (the band through it, at your Angle)
            tap != null && mode.hasDepth -> FocusMask(2, tap.x, tap.y, 0f, 0.1f, 0.9f, v[4])
            // Tilt Shift's band always comes from its own Focus and Angle. Follow guide and taps *write* those
            // (see MainActivity.placeTilt), so the band can still be dragged, tapped or slid afterwards.
            mode == ArtMode.TILT_SHIFT ->
                FocusMask(1, 0.5f, v[1], (v[3] - 0.5f) * HALF_PI, 0.02f + v[2] * 0.3f, v[4], v.getOrElse(7) { 0f })
            mode.hasDepth -> if (link != null) FocusMask(2, link.u, link.v, 0f, 0.1f, 0.9f, v[4])
                else FocusMask(2, subject?.x ?: 0.5f, subject?.y ?: 0.5f, 0f, 0.08f, 1f, v[4])
            else -> FocusMask()
        }
    }

    /**
     * The viewfinder's mask (frame uv, portrait) in the saved photo's own coordinates. CameraX records the
     * phone's rotation and the photo is decoded upright, so a shot taken sideways is the frame turned by
     * [deviceRot] (the orientation listener's 0/90/180/270, clockwise). The mask's size is in units of the
     * image height, which becomes the short side when turned, so it is rescaled to stay the same on the
     * picture. [aspect] is the frame's width / height. Front-camera photos are saved mirrored like the
     * preview, so no extra flip is needed.
     */
    fun toPhoto(m: FocusMask, deviceRot: Int, aspect: Float): FocusMask {
        if (m.type == 0 || deviceRot == 0) return m
        if (deviceRot == 180) return m.copy(cx = 1f - m.cx, cy = 1f - m.cy)
        val k = 1f / aspect                 // portrait height / landscape height
        val f = 0.03f + m.feather * 0.55f
        val feather = ((f * k - 0.03f) / 0.55f).coerceAtLeast(0f)
        return if (deviceRot == 90) m.copy(cx = 1f - m.cy, cy = m.cx, angle = m.angle + HALF_PI, size = m.size * k, feather = feather)
        else m.copy(cx = m.cy, cy = 1f - m.cx, angle = m.angle - HALF_PI, size = m.size * k, feather = feather)
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
    /** How many values each look keeps (see [ArtMode]). */
    const val SLOTS = 8

    /** AGSL needs Android 13. On older phones the Art layer explains that instead of crashing. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** A mode's own saved shooting preferences, or its calibrated factory defaults on first use. */
    fun loadState(prefs: SharedPreferences, mode: ArtMode): ArtState {
        val s = prefs.getString("art3:${mode.name}", null) ?: return ArtState(mode.defaults, false)
        return runCatching {
            val p = s.split("|")
            val raw = p[0].split(",").map { it.toFloat() }.toFloatArray()
            val v = pad(mode, raw)
            // saved before Cross Process had a Contrast slider: that slot held an unused 0, start it at neutral
            if (raw.size <= 6 && mode == ArtMode.CROSS_PROCESS) v[2] = mode.defaults[2]
            ArtState(v, p.getOrNull(1) == "1")
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
        val out = FloatArray(SLOTS) { i -> if (i in 1..4 && i < v.size && mode.sliders.getOrNull(i)?.first?.isNotEmpty() == true) v[i] else d[i] }
        return ArtState(out, false)
    }

    fun saveState(prefs: SharedPreferences, mode: ArtMode, st: ArtState) {
        if (mode == ArtMode.OFF) return
        prefs.edit().putString("art3:${mode.name}", st.values.joinToString(",") + "|" + (if (st.follow) 1 else 0)).apply()
    }

    /** Back to this mode's factory defaults; other modes are untouched. */
    fun resetState(prefs: SharedPreferences, mode: ArtMode): ArtState {
        prefs.edit().remove("art3:${mode.name}").apply()
        return ArtState(mode.defaults, false)
    }

    fun pad(mode: ArtMode, v: FloatArray): FloatArray = FloatArray(SLOTS) { i -> v.getOrNull(i) ?: mode.defaults[i] }

    @RequiresApi(33)
    fun newShader(mode: ArtMode): RuntimeShader? =
        if (mode == ArtMode.OFF) null else runCatching { RuntimeShader(ArtShaders.source(mode)) }.getOrNull()

    @RequiresApi(33)
    /** Slider position (0..1, 50% = calibrated) to the value the shader uses. */
    fun eff(mode: ArtMode, v: FloatArray): FloatArray {
        val c = mode.calib
        return FloatArray(SLOTS) { i ->
            val p = v.getOrElse(i) { 0.5f }
            if (i == 0 || i == 5) p else if (p <= 0.5f) p / 0.5f * c[i] else c[i] + (p - 0.5f) / 0.5f * (1f - c[i])
        }
    }

    fun setUniforms(sh: RuntimeShader, mode: ArtMode, raw: FloatArray, x: Float, y: Float, w: Float, h: Float, seed: Float, m: FocusMask) {
        val v = eff(mode, raw)
        fun set(name: String, vararg f: Float) { runCatching { sh.setFloatUniform(name, f) } }
        set("org", x, y); set("res", w, h)
        set("amt", v.getOrElse(0) { 0f }); set("p1", v.getOrElse(1) { 0f })
        set("p2", v.getOrElse(2) { 0f }); set("p3", v.getOrElse(3) { 0f })
        set("seed", seed); set("lks", v.getOrElse(5) { 0f }); set("p4", v.getOrElse(6) { 0f })
        set("msk", m.cx, m.cy, m.angle, m.type.toFloat()); set("msz", m.size, m.feather, m.strength)
    }

    /**
     * A look's preview thumbnail: the look at its factory defaults on a small copy of a photo, with no
     * focus effects, so a picker can show what each look does to *this* picture.
     */
    @RequiresApi(33)
    fun thumbnail(src: Bitmap, mode: ArtMode, seed: Float = 0.37f): Bitmap =
        if (mode == ArtMode.OFF) src else {
            val v = mode.defaults.also { it[mode.depthSlot] = 0f }
            render(src, Recipe(mode, v, seed), if (mode == ArtMode.TILT_SHIFT) Focus.resolve(mode, v, false, null, null) else FocusMask())
        }

    /** Runs a mode over a whole bitmap on the GPU and returns a new software bitmap. */
    @RequiresApi(33)
    fun render(src: Bitmap, r: Recipe, m: FocusMask? = null): Bitmap {
        val sh = newShader(r.mode) ?: return src
        val w = src.width; val h = src.height
        setUniforms(sh, r.mode, r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed, m ?: r.mask)
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
