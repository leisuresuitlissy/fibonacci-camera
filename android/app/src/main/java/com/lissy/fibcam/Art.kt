package com.lissy.fibcam

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.PixelFormat
import android.graphics.RenderEffect
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
/** The shared vignette, on every Look (slot 8). */
private val VIGNETTE = c("Vignette", 8)
/** What Vignette at 50% renders as. */
private const val VIG_50 = 0.45f
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
    /** Sliders that don't start at 50% (slot to starting position). */
    val start: Map<Int, Float> = emptyMap(),
    /** What this look's Vignette renders at 50% (most looks share [VIG_50]). */
    val vig50: Float = VIG_50,
) {
    OFF("OFF", emptyList()),
    TILT_SHIFT("TILT SHIFT", listOf("Intensity" to 0.5f, "Focus" to 0.5f, "Width" to 0.3f, "Angle" to 0.5f, "Feather" to 0.5f),
        listOf(INTENSITY, c("Focus", 1), c("Width", 2), c("Angle", 3, degrees = true), c("Feather", 4), VIGNETTE, c("Depth", 7)), extra = 0f to 0.5f, vig50 = 0.15f),
    HOLGA("PLASTIC", listOf("Intensity" to 0.5f, "Vignette" to 0.55f, "Softness" to 0.3f, "Leaks" to 0.45f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Softness", 2), VIGNETTE, c("Leaks", 3, choices = true), c("Saturation", 6), DEPTH),
        variations = listOf("OFF", "WARM", "COOL", "NEUTRAL", "MIXED", "RANDOM"), variationLabel = "LEAK", defaultVariation = 1, extra = 0.5f to 0f),
    PINHOLE("PINHOLE", listOf("Intensity" to 0.5f, "Softness" to 0.3f, "Vignette" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Softness", 1), VIGNETTE, DEPTH)),
    // Black and white by default: Saturation starts at 0 and brings colour back as you raise it.
    GRAINY_FILM("GRAINY FILM", listOf("Intensity" to 0.5f, "Grain" to 0.5f, "Contrast" to 0.55f, "Saturation" to 0.5f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Grain", 1), c("Contrast", 2), c("Saturation", 3), VIGNETTE, DEPTH), start = mapOf(3 to 0f)),
    CROSS_PROCESS("CROSS PROCESS", listOf("Intensity" to 0.5f, "Colour" to 0.5f, "Contrast" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Colour", 1), c("Contrast", 2), VIGNETTE, DEPTH)),
    DREAM_LENS("DREAM LENS", listOf("Intensity" to 0.5f, "Glow" to 0.5f, "Softness" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Glow", 1), c("Softness", 2), VIGNETTE, DEPTH)),
    // Halation stays at its calibrated 0.3; the camera shows Contrast and Depth only.
    INFRARED("INFRARED", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Halation" to 0.3f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Contrast", 1), VIGNETTE, DEPTH),
        about = "Infrared-inspired: a simulation from visible light, not real infrared."),
    CYANOTYPE("CYANOTYPE", listOf("Intensity" to 0.5f, "Contrast" to 0.5f, "Paper" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Contrast", 1), c("Paper", 2), VIGNETTE, DEPTH)),
    BLEACH_BYPASS("BLEACH BYPASS", listOf("Intensity" to 0.5f, "Saturation" to 0.35f, "Contrast" to 0.5f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Saturation", 1), c("Contrast", 2), VIGNETTE, DEPTH)),
    // The newer looks are colour and tone first: Depth starts at 0 and stays optional.
    KINO("KINO", listOf("Intensity" to 0.5f, "Halation" to 0.5f, "Coolness" to 0.5f, "Grain" to 0.35f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Halation", 1), c("Coolness", 2), c("Grain", 3), VIGNETTE, DEPTH)),
    EXPIRED("EXPIRED", listOf("Intensity" to 0.5f, "Age" to 0.5f, "Grain" to 0.45f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Age", 1), c("Colour shift", -1, choices = true), c("Grain", 2), VIGNETTE, DEPTH),
        variations = listOf("WARM", "MAGENTA", "COOL", "RANDOM"), variationLabel = "COLOUR", defaultVariation = 1),
    REDSCALE("REDSCALE", listOf("Intensity" to 0.5f, "Tone" to 0.45f, "Grain" to 0.3f, "" to 0f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Tone", 1), c("Grain", 2), VIGNETTE, DEPTH)),
    SOLARISED("SOLARISED", listOf("Intensity" to 0.5f, "Reversal" to 0.5f, "Threshold" to 0.45f, "Contour" to 0.35f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Reversal", 1), c("Threshold", 2), c("Contour", 3), VIGNETTE, DEPTH)),
    // SX-70: Warmth cool → creamy → honey, Fade lifts blacks and softens contrast, Texture is grain (values[6]);
    // the slight softness stays at its calibrated 0.4, and the vignette starts much lighter than other looks.
    INSTANT("INSTANT", listOf("Intensity" to 0.5f, "Warmth" to 0.62f, "Fade" to 0.5f, "Softness" to 0.4f, "Depth" to DEPTH_50),
        listOf(INTENSITY, c("Warmth", 1), c("Fade", 2), c("Texture", 6), VIGNETTE, DEPTH), extra = 0.4f to 0f, vig50 = 0.15f);

    /** Every slider starts at 50%; [calib] says what 50% looks like. */
    val defaults: FloatArray get() = FloatArray(Art.SLOTS) { i -> if (i == 5) defaultVariation.toFloat() else start[i] ?: 0.5f }
    /**
     * The calibrated value each slot renders at 50% (the numbers in [sliders] and [extra]). Values below
     * 50% scale down to 0, above it up to 1, so the slider still covers the whole range.
     */
    val calib: FloatArray get() = FloatArray(Art.SLOTS) { i ->
        when (i) { 0 -> 0.5f; 5 -> 0.5f; 6 -> extra.first; 7 -> extra.second; 8 -> vig50; else -> sliders.getOrNull(i)?.second ?: 0f }
    }
    /** Whether variation [i] is the random one (it gets a fresh, stable seed when chosen). */
    fun isRandom(i: Int) = variations.getOrNull(i) == "RANDOM"
    /** The value slots that have a slider, in display order (the editor uses these too). */
    val shown: List<Int> get() = controls.filter { it.slot >= 0 }.map { it.slot }
    fun labelOf(slot: Int): String = controls.firstOrNull { it.slot == slot }?.label ?: sliders.getOrNull(slot)?.first ?: ""
    /** Saved-file suffix: the look's display name (HOLGA shows and saves as Plastic; its id stays for stored settings). */
    val fileTag: String get() = label.lowercase().replace(' ', '-').replace('_', '-')
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
    fun resolve(mode: ArtMode, raw: FloatArray, follow: Boolean, g: GuideCtx?, subject: PointF?, tap: PointF? = null, aspect: Float = 0.75f): FocusMask {
        val link = if (follow && mode.follows && g != null) linked(g) else null
        val v = Art.eff(mode, raw)
        return when {
            // Tilt Shift is always a straight band from its own Focus and Angle. Follow guide and taps set those
            // values (see MainActivity.placeTilt): a guide anchor decides where the band runs, never its shape.
            mode == ArtMode.TILT_SHIFT -> tiltBand(v, aspect)
            // Point-based depth: a fully sharp disc on the target, then progressively more blur.
            tap != null && mode.hasDepth -> spot(tap.x, tap.y, v[4])
            mode.hasDepth -> if (link != null) spot(link.u, link.v, v[4]) else spot(subject?.x ?: 0.5f, subject?.y ?: 0.5f, v[4])
            else -> FocusMask()
        }
    }

    /** Radius of the fully sharp disc and the length of its fade, in frame heights. */
    private const val SPOT_R = 0.17f
    private const val SPOT_FEATHER = 0.4f      // fade = 0.03 + 0.4 × 0.55 = 0.25 frame heights
    private fun spot(u: Float, v: Float, strength: Float) = FocusMask(2, u, v, 0f, SPOT_R, SPOT_FEATHER, strength)

    /**
     * Tilt Shift's band. Focus moves it perpendicular to its Angle: 50% runs through the middle of the frame,
     * 0 and 100% sit half a frame height either side. Width is the fully sharp half-width; Depth (slot 7) the
     * blur strength outside. Type 3: Feather is a share of the room between the band and the frame's far
     * corner (see [featherLen]), so it only ever shapes the transition. [aspect] is the frame's width / height.
     */
    fun tiltBand(v: FloatArray, aspect: Float): FocusMask {
        val a = (v[3] - 0.5f) * HALF_PI
        val d = v[1] - 0.5f
        // Width: half-width 0.02 to 0.24 frame heights (eases in), so even the widest band leaves blurred room
        return FocusMask(3, 0.5f - kotlin.math.sin(a) * d / aspect, 0.5f + kotlin.math.cos(a) * d, a, 0.02f + 0.22f * Math.pow(v[2].toDouble(), 0.75).toFloat(), v[4], v.getOrElse(7) { 0f })
    }

    /** The mask's fade length in frame heights, as the shaders work it out (bandFeather / maskSoft). */
    fun featherLen(m: FocusMask, aspect: Float): Float {
        if (m.type != 3) return 0.03f + m.feather * 0.55f
        val nx = -kotlin.math.sin(m.angle); val ny = kotlin.math.cos(m.angle)
        val cx = (m.cx - 0.5f) * aspect; val cy = m.cy - 0.5f
        var far = 0f
        for (sx in floatArrayOf(-0.5f, 0.5f)) for (sy in floatArrayOf(-0.5f, 0.5f))
            far = maxOf(far, kotlin.math.abs((sx * aspect - cx) * nx + (sy - cy) * ny))
        return maxOf(far - m.size, 0.05f) * (0.2f + 0.52f * m.feather)
    }

    /** The Focus value whose band passes through (u, v) at angle [a] (radians). */
    fun tiltFocusThrough(u: Float, v: Float, a: Float, aspect: Float): Float =
        (0.5f - kotlin.math.sin(a) * (u - 0.5f) * aspect + kotlin.math.cos(a) * (v - 0.5f)).coerceIn(0f, 1f)


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
        val feather = if (m.type == 3) m.feather else ((f * k - 0.03f) / 0.55f).coerceAtLeast(0f)
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
    const val SLOTS = 9

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

    fun pad(mode: ArtMode, v: FloatArray): FloatArray = FloatArray(SLOTS) { i -> v.getOrNull(i) ?: if (i == 8) 0f else mode.defaults[i] }   // older photos had no shared vignette

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
        set("seed", seed); set("lks", v.getOrElse(5) { 0f }); set("p4", v.getOrElse(6) { 0f }); set("vgn", v.getOrElse(8) { 0f })
        set("msk", m.cx, m.cy, m.angle, m.type.toFloat()); set("msz", m.size, m.feather, m.strength)
    }

    /**
     * A look's preview thumbnail: the look at its factory defaults on a small copy of a photo, with no
     * focus effects, so a picker can show what each look does to *this* picture.
     */
    @RequiresApi(33)
    fun thumbnail(src: Bitmap, mode: ArtMode, seed: Float = 0.37f): Bitmap =
        if (mode == ArtMode.OFF) src else {
            val v = mode.defaults.also { if (mode != ArtMode.TILT_SHIFT) it[mode.depthSlot] = 0f }
            render(src, Recipe(mode, v, seed), if (mode == ArtMode.TILT_SHIFT) Focus.resolve(mode, v, false, null, null, aspect = src.width.toFloat() / src.height) else FocusMask())
        }

    /** Runs a mode over a whole bitmap on the GPU and returns a new software bitmap. */
    @RequiresApi(33)
    fun render(src: Bitmap, r: Recipe, m: FocusMask? = null): Bitmap {
        val w = src.width; val h = src.height
        val node = RenderNode("art").apply { setPosition(0, 0, w, h) }
        // Tilt Shift: the photo through exactly the effect the viewfinder uses (TiltFx), at full resolution
        val tilt = if (r.mode == ArtMode.TILT_SHIFT) TiltFx.create()?.effect(r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed, m ?: r.mask) else null
        if (tilt != null) {
            node.setRenderEffect(tilt)
            node.beginRecording().drawBitmap(src, 0f, 0f, null)
        } else {
            val sh = newShader(r.mode) ?: return src
            setUniforms(sh, r.mode, r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed, m ?: r.mask)
            sh.setInputShader("content", BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
            node.beginRecording().drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = sh })
        }
        node.endRecording()
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 1,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
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


/**
 * Tilt Shift as a layered GPU blur, built the same way for the viewfinder and for saved photos, so they match.
 * The picture's edges are clamped (TILT_CLAMP), then blurred to three strengths: 20%, 50% and 100% of the
 * radius ([radius]: Depth × Intensity, a share of the frame's short side, so it scales with resolution).
 * Each level fades into the next across the feather (TILT_LAYER), the untouched picture sits on top inside
 * the band, and the grade and vignette finish it (TILT_GRADE). The platform blur downsamples internally,
 * so even a very large radius stays smooth and cheap.
 */
@RequiresApi(33)
class TiltFx private constructor(
    private val clamp: RuntimeShader,
    private val layers: List<RuntimeShader>,
    private val grade: RuntimeShader,
) {
    fun effect(raw: FloatArray, x: Float, y: Float, w: Float, h: Float, seed: Float, m: FocusMask): RenderEffect {
        val mode = ArtMode.TILT_SHIFT
        (layers + clamp + grade).forEach { Art.setUniforms(it, mode, raw, x, y, w, h, seed, m) }
        layers.forEachIndexed { i, s -> s.setFloatUniform("lyr", i.toFloat()) }
        val gradeFx = RenderEffect.createRuntimeShaderEffect(grade, "content")
        val r = radius(Art.eff(mode, raw), minOf(w, h))
        if (r < 1f) return gradeFx
        val clamped = RenderEffect.createRuntimeShaderEffect(clamp, "content")
        fun blur(k: Float) = RenderEffect.createChainEffect(RenderEffect.createBlurEffect(r * k, r * k, Shader.TileMode.CLAMP), clamped)
        fun layer(i: Int, under: RenderEffect?): RenderEffect =
            RenderEffect.createRuntimeShaderEffect(layers[i], "content").let { if (under == null) it else RenderEffect.createChainEffect(it, under) }
        fun over(dst: RenderEffect, src: RenderEffect) = RenderEffect.createBlendModeEffect(dst, src, BlendMode.SRC_OVER)
        var comp = over(blur(1f), layer(2, blur(0.5f)))
        comp = over(comp, layer(1, blur(0.2f)))
        comp = over(comp, layer(0, null))
        return RenderEffect.createChainEffect(gradeFx, comp)
    }

    companion object {
        fun create(): TiltFx? = runCatching {
            TiltFx(RuntimeShader(ArtShaders.TILT_CLAMP), List(3) { RuntimeShader(ArtShaders.TILT_LAYER) }, RuntimeShader(ArtShaders.TILT_GRADE))
        }.getOrNull()

        /** Matches the shaders' tiltR(): 11% of the short side at full Depth, scaled by Intensity (full from 50%, +25% at 100%). */
        fun radius(v: FloatArray, s: Float): Float {
            val a = v[0]
            val ramp = if (a < 0.5f) a * 2f else 1f + (a - 0.5f) * 0.5f
            return s * 0.11f * v.getOrElse(7) { 0f } * ramp
        }
    }
}
