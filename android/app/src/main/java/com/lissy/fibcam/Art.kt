package com.lissy.fibcam

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.annotation.RequiresApi

/** Slider 0 is always intensity; sliders 1..3 feed the shader's p1..p3; slider 4, Depth, is shared by every look but Tilt Shift. */
private val DEPTH = "Depth" to 0.35f

enum class ArtMode(val label: String, val sliders: List<Pair<String, Float>>) {
    OFF("OFF", emptyList()),
    TILT_SHIFT("TILT SHIFT", listOf("Intensity" to 0.7f, "Focus" to 0.55f, "Width" to 0.25f, "Angle" to 0.5f)),
    HOLGA("HOLGA", listOf("Intensity" to 0.7f, "Vignette" to 0.6f, "Softness" to 0.4f, "Leaks" to 0.5f, DEPTH)),
    PINHOLE("PINHOLE", listOf("Intensity" to 0.85f, "Softness" to 0.4f, "Vignette" to 0.6f, "" to 0f, DEPTH)),
    GRAINY_FILM("GRAINY FILM", listOf("Intensity" to 0.9f, "Grain" to 0.5f, "Contrast" to 0.5f, "" to 0f, DEPTH)),
    CROSS_PROCESS("CROSS PROCESS", listOf("Intensity" to 0.8f, "Colour" to 0.5f, "" to 0f, "" to 0f, DEPTH)),
    DREAM_LENS("DREAM LENS", listOf("Intensity" to 0.45f, "Glow" to 0.45f, "Softness" to 0.35f, "" to 0f, DEPTH)),
    INFRARED("INFRARED", listOf("Intensity" to 0.9f, "Contrast" to 0.5f, "Halation" to 0.3f, "" to 0f, DEPTH)),
    CYANOTYPE("CYANOTYPE", listOf("Intensity" to 0.9f, "Contrast" to 0.5f, "Paper" to 0.5f, "" to 0f, DEPTH)),
    BLEACH_BYPASS("BLEACH BYPASS", listOf("Intensity" to 0.8f, "Saturation" to 0.35f, "Contrast" to 0.5f, "" to 0f, DEPTH));

    val defaults: FloatArray get() = FloatArray(5) { i -> sliders.getOrNull(i)?.second ?: 0f }
    /** The sliders to show: the padding entries (no name) are skipped, but indexes stay put. */
    val shown: List<Int> get() = sliders.indices.filter { sliders[it].first.isNotEmpty() }
    val fileTag: String get() = name.lowercase().replace('_', '-')
}

/**
 * A mode plus its slider values, the randomness it was shot with, and the focus point (0..1 across the
 * whole photo) that stays sharp — enough to redo the look.
 */
data class Recipe(val mode: ArtMode, val values: FloatArray, val seed: Float, val fx: Float = 0.5f, val fy: Float = 0.5f) {
    fun encode() = "${mode.name}|${values.joinToString(",")}|$seed|$fx,$fy"
    companion object {
        fun decode(s: String?): Recipe? = runCatching {
            val p = s!!.split("|")
            val m = ArtMode.valueOf(p[0])
            val v = Art.pad(m, p[1].split(",").map { it.toFloat() }.toFloatArray())
            val f = p.getOrNull(3)?.split(",")?.map { it.toFloat() }
            Recipe(m, v, p[2].toFloat(), f?.getOrNull(0) ?: 0.5f, f?.getOrNull(1) ?: 0.5f)
        }.getOrNull()
    }
}

object Art {
    /** AGSL needs Android 13. On older phones the Art layer explains that instead of crashing. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun loadValues(prefs: SharedPreferences, mode: ArtMode): FloatArray {
        val s = prefs.getString("artv:${mode.name}", null) ?: return mode.defaults
        return runCatching { pad(mode, s.split(",").map { it.toFloat() }.toFloatArray()) }.getOrDefault(mode.defaults)
    }

    /** Older saves have four values; the fifth (Depth) starts at its default. */
    fun pad(mode: ArtMode, v: FloatArray): FloatArray = FloatArray(5) { i -> v.getOrNull(i) ?: mode.defaults[i] }

    fun saveValues(prefs: SharedPreferences, mode: ArtMode, v: FloatArray) {
        prefs.edit().putString("artv:${mode.name}", v.joinToString(",")).apply()
    }

    @RequiresApi(33)
    fun newShader(mode: ArtMode): RuntimeShader? =
        if (mode == ArtMode.OFF) null else runCatching { RuntimeShader(ArtShaders.source(mode)) }.getOrNull()

    @RequiresApi(33)
    fun setUniforms(sh: RuntimeShader, v: FloatArray, x: Float, y: Float, w: Float, h: Float, seed: Float, fx: Float = 0.5f, fy: Float = 0.5f) {
        fun set(name: String, vararg f: Float) { runCatching { sh.setFloatUniform(name, f) } }
        set("org", x, y); set("res", w, h)
        set("amt", v.getOrElse(0) { 0f }); set("p1", v.getOrElse(1) { 0f })
        set("p2", v.getOrElse(2) { 0f }); set("p3", v.getOrElse(3) { 0f })
        set("seed", seed); set("dep", v.getOrElse(4) { 0f }); set("foc", fx, fy)
    }

    /** Runs a mode over a whole bitmap on the GPU and returns a new software bitmap. */
    @RequiresApi(33)
    fun render(src: Bitmap, r: Recipe): Bitmap {
        val sh = newShader(r.mode) ?: return src
        val w = src.width; val h = src.height
        setUniforms(sh, r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed, r.fx, r.fy)
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
