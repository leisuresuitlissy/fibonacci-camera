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

/** Slider 0 is always intensity; sliders 1..3 feed the shader's p1..p3. */
enum class ArtMode(val label: String, val sliders: List<Pair<String, Float>>) {
    OFF("OFF", emptyList()),
    TILT_SHIFT("TILT SHIFT", listOf("Intensity" to 0.7f, "Focus" to 0.55f, "Width" to 0.25f, "Angle" to 0.5f)),
    HOLGA("HOLGA", listOf("Intensity" to 0.8f, "Vignette" to 0.6f, "Softness" to 0.4f, "Leaks" to 0.5f)),
    PINHOLE("PINHOLE", listOf("Intensity" to 0.85f, "Softness" to 0.4f, "Vignette" to 0.6f)),
    GRAINY_FILM("GRAINY FILM", listOf("Intensity" to 0.9f, "Grain" to 0.5f, "Contrast" to 0.5f)),
    CROSS_PROCESS("CROSS PROCESS", listOf("Intensity" to 0.8f, "Colour" to 0.5f)),
    DREAM_LENS("DREAM LENS", listOf("Intensity" to 0.7f, "Glow" to 0.5f, "Softness" to 0.4f)),
    INFRARED("INFRARED", listOf("Intensity" to 0.85f, "Contrast" to 0.5f)),
    CYANOTYPE("CYANOTYPE", listOf("Intensity" to 0.9f, "Contrast" to 0.5f, "Paper" to 0.5f)),
    BLEACH_BYPASS("BLEACH BYPASS", listOf("Intensity" to 0.8f, "Saturation" to 0.35f, "Contrast" to 0.5f));

    val defaults: FloatArray get() = FloatArray(4) { i -> sliders.getOrNull(i)?.second ?: 0f }
    val fileTag: String get() = name.lowercase().replace('_', '-')
}

/** A mode plus its slider values and the randomness it was shot with — enough to redo the look. */
data class Recipe(val mode: ArtMode, val values: FloatArray, val seed: Float) {
    fun encode() = "${mode.name}|${values.joinToString(",")}|$seed"
    companion object {
        fun decode(s: String?): Recipe? = runCatching {
            val p = s!!.split("|")
            Recipe(ArtMode.valueOf(p[0]), p[1].split(",").map { it.toFloat() }.toFloatArray(), p[2].toFloat())
        }.getOrNull()
    }
}

object Art {
    /** AGSL needs Android 13. On older phones the Art layer explains that instead of crashing. */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun loadValues(prefs: SharedPreferences, mode: ArtMode): FloatArray {
        val s = prefs.getString("artv:${mode.name}", null) ?: return mode.defaults
        return runCatching { s.split(",").map { it.toFloat() }.toFloatArray().copyOf(4) }.getOrDefault(mode.defaults)
    }

    fun saveValues(prefs: SharedPreferences, mode: ArtMode, v: FloatArray) {
        prefs.edit().putString("artv:${mode.name}", v.joinToString(",")).apply()
    }

    @RequiresApi(33)
    fun newShader(mode: ArtMode): RuntimeShader? =
        if (mode == ArtMode.OFF) null else runCatching { RuntimeShader(ArtShaders.source(mode)) }.getOrNull()

    @RequiresApi(33)
    fun setUniforms(sh: RuntimeShader, v: FloatArray, x: Float, y: Float, w: Float, h: Float, seed: Float) {
        fun set(name: String, vararg f: Float) { runCatching { sh.setFloatUniform(name, f) } }
        set("org", x, y); set("res", w, h)
        set("amt", v.getOrElse(0) { 0f }); set("p1", v.getOrElse(1) { 0f })
        set("p2", v.getOrElse(2) { 0f }); set("p3", v.getOrElse(3) { 0f })
        set("seed", seed)
    }

    /** Runs a mode over a whole bitmap on the GPU and returns a new software bitmap. */
    @RequiresApi(33)
    fun render(src: Bitmap, r: Recipe): Bitmap {
        val sh = newShader(r.mode) ?: return src
        val w = src.width; val h = src.height
        setUniforms(sh, r.values, 0f, 0f, w.toFloat(), h.toFloat(), r.seed)
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
