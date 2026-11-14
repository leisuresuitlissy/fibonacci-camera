package com.lissy.fibcam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.content.res.ColorStateList
import android.os.Build
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Crop as a copy. The original is only ever read: every save writes a new file beside it,
 * so you can crop the same photo as many ways as you like. The last crop is remembered per
 * photo, so coming back picks up where you left off.
 */
class CropActivity : ComponentActivity() {

    private val cBase = 0xFF0B0B0C.toInt()
    private val cElev = 0xFF1F1F25.toInt()
    private val cLine = 0xFF2E2E36.toInt()
    private val cText = 0xFFF5F3EF.toInt()
    private val cMuted = 0xFFA3A09A.toInt()
    private val cGold = 0xFFE0B252.toInt()
    private val mono: Typeface = Typeface.MONOSPACE
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private val prefs by lazy { getSharedPreferences("fibcam", Context.MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var src: Uri
    private lateinit var crop: CropView
    private lateinit var readL: TextView
    private lateinit var readR: TextView
    private lateinit var ratioRow: LinearLayout
    private lateinit var eyeRow: LinearLayout
    private lateinit var dial: DialView
    private lateinit var saveBtn: TextView
    private lateinit var noteView: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var cropLayer: LinearLayout
    private lateinit var artLayer: LinearLayout
    private lateinit var artRow: LinearLayout
    private lateinit var paramRow: LinearLayout
    private lateinit var artSlider: SeekBar
    private lateinit var blendLayer: LinearLayout
    private lateinit var blendActions: LinearLayout
    private lateinit var blendRow: LinearLayout
    private lateinit var blendValue: TextView
    private lateinit var blendSlider: SeekBar
    private var secondUri: Uri? = null
    private var tab = "CROP"
    private var recipe = Recipe(ArtMode.OFF, FloatArray(5), (Math.random() * 1000).toFloat())
    private var focusHinted = false
    private var artParam = 0

    private var ratio = "PHI"            // PHI, ORIGINAL, 4:5, 1:1, 3:2
    private var portrait = true
    private var sample = 1
    private var busy = false

    private val pickSecond = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) loadSecond(uri, restoring = null)
    }

    private val pick = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { startActivity(Intent(this, CropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val u = intent.data
        if (u == null) { finish(); return }
        src = u
        setContentView(buildUi())
        note("Loading…")
        io.execute {
            val b = runCatching { decode(src, display = true) }.getOrNull()
            runOnUiThread {
                if (b == null) { note("That photo couldn’t be opened."); return@runOnUiThread }
                crop.bmp = b
                portrait = b.height >= b.width
                Recipe.decode(prefs.getString("art:$src", null))?.let { if (Art.supported) recipe = it }
                crop.recipe = recipe
                crop.post { if (!restore()) applyRatio(reset = true); applyTab(); applyArtUi(); applyBlendUi(); note(null); restoreSecond() }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    // ------------------------------------------------------------- UI helpers
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun alpha(c: Int, a: Float) = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))
    private fun rounded(fill: Int, stroke: Int? = null, radius: Float = 999f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius); if (stroke != null) setStroke(dpi(1f).coerceAtLeast(1), stroke)
    }
    private fun label(t: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face; letterSpacing = spacing; includeFontPadding = false
    }
    private fun chip(t: String, on: Boolean, click: () -> Unit) = label(t, 12f, if (on) cGold else cMuted, medium, 0.04f).apply {
        background = if (on) rounded(alpha(cGold, 0.18f), alpha(cGold, 0.85f)) else rounded(cElev)
        setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)); setOnClickListener { click() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), 0, dpi(16f), 0) }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBase) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        val top = row().apply { setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f)) }
        top.addView(chip("‹ CAMERA", false) { finish() })
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(12f), 0, 0, 0) }
        t.addView(label("Edit a copy", 15f, cText, Typeface.DEFAULT_BOLD))
        t.addView(label("ORIGINAL IS NEVER CHANGED", 9.5f, cMuted, mono, 0.1f).apply { setPadding(0, dpi(3f), 0, 0) })
        top.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(chip("OTHER PHOTO", false) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        crop = CropView(this).apply {
            onChange = { syncReadout() }; onSecondChange = { rememberSecond() }
            onFocusTap = { u, v -> recipe = recipe.copy(fx = u, fy = v); crop.recipe = recipe; crop.invalidate(); rememberArt() }
        }
        stage.addView(crop, FrameLayout.LayoutParams(match, match))
        noteView = label("", 12f, cGold, medium).apply {
            background = rounded(alpha(cBase, 0.85f), alpha(cGold, 0.8f)); setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f)); visibility = View.GONE
        }
        stage.addView(noteView, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(16f) })
        root.addView(stage, LinearLayout.LayoutParams(match, 0, 1f))

        val read = row()
        readL = label("", 10f, cMuted, mono, 0.05f)
        read.addView(readL, LinearLayout.LayoutParams(0, wrap, 1f))
        readR = label("", 9f, cGold, mono, 0.07f).apply { background = rounded(alpha(cGold, 0.12f), alpha(cGold, 0.38f)); setPadding(dpi(10f), dpi(5f), dpi(10f), dpi(5f)) }
        read.addView(readR)
        root.addView(read, LinearLayout.LayoutParams(match, dpi(40f)))

        tabRow = row().apply { setPadding(dpi(16f), dpi(6f), dpi(16f), dpi(2f)) }
        root.addView(tabRow, LinearLayout.LayoutParams(match, wrap))
        val layers = FrameLayout(this)
        cropLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ratioRow = row(); cropLayer.addView(ratioRow, LinearLayout.LayoutParams(match, dpi(48f)))
        eyeRow = row(); cropLayer.addView(eyeRow, LinearLayout.LayoutParams(match, dpi(48f)))
        dial = DialView(this).apply { onChange = { v -> crop.theta = Math.toRadians(v.toDouble()).toFloat(); crop.refit() } }
        cropLayer.addView(dial, LinearLayout.LayoutParams(match, dpi(64f)))
        layers.addView(cropLayer, FrameLayout.LayoutParams(match, match))
        artLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(10f), 0, 0) }
        val ascroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        artRow = row(); ascroll.addView(artRow)
        artLayer.addView(ascroll, LinearLayout.LayoutParams(match, dpi(48f)))
        paramRow = row(); artLayer.addView(paramRow, LinearLayout.LayoutParams(match, dpi(32f)))
        artSlider = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cGold); progressBackgroundTintList = ColorStateList.valueOf(cLine); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) onArtSlider(v / 100f) }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        artLayer.addView(artSlider, LinearLayout.LayoutParams(match, dpi(36f)).apply { leftMargin = dpi(4f); rightMargin = dpi(4f) })
        layers.addView(artLayer, FrameLayout.LayoutParams(match, match))

        blendLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dpi(6f), 0, 0) }
        blendActions = row(); blendLayer.addView(blendActions, LinearLayout.LayoutParams(match, dpi(48f)))
        blendRow = row(); blendLayer.addView(blendRow, LinearLayout.LayoutParams(match, dpi(48f)))
        val bHead = row()
        bHead.addView(label("BLEND STRENGTH", 10f, cMuted, mono, 0.13f)); bHead.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        blendValue = label("60%", 11f, cGold, mono); bHead.addView(blendValue)
        blendLayer.addView(bHead, LinearLayout.LayoutParams(match, dpi(22f)))
        blendSlider = SeekBar(this).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(cGold); progressBackgroundTintList = ColorStateList.valueOf(cLine); thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) { crop.strength = v / 100f; blendValue.text = "$v%"; rememberSecond() } }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        blendLayer.addView(blendSlider, LinearLayout.LayoutParams(match, dpi(32f)).apply { leftMargin = dpi(4f); rightMargin = dpi(4f) })
        layers.addView(blendLayer, FrameLayout.LayoutParams(match, match))
        root.addView(layers, LinearLayout.LayoutParams(match, dpi(160f)))

        val bar = row().apply { setPadding(dpi(16f), dpi(8f), dpi(16f), dpi(12f)) }
        bar.addView(label("Reset", 13f, cText, medium).apply {
            gravity = Gravity.CENTER; background = rounded(cElev, cLine, 14f); setPadding(dpi(18f), dpi(15f), dpi(18f), dpi(15f))
            setOnClickListener { dial.value = 0f; crop.theta = 0f; applyRatio(reset = true) }
        })
        saveBtn = label("Save φ copy", 14f, cBase, Typeface.DEFAULT_BOLD).apply {
            gravity = Gravity.CENTER; background = rounded(cGold, null, 14f); setPadding(0, dpi(15f), 0, dpi(15f))
            setOnClickListener { save() }
        }
        bar.addView(saveBtn, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = dpi(10f) })
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        return root
    }

    private fun applyTab() {
        tabRow.removeAllViews()
        val tabs = listOf("CROP" to "CROP", "ART" to ("ART" + if (recipe.mode != ArtMode.OFF) " · " + recipe.mode.label else ""),
            "BLEND" to (if (secondUri != null) "BLEND · " + crop.blend.label.uppercase(Locale.US) else "DOUBLE"))
        for ((key, text) in tabs) {
            val on = key == tab
            tabRow.addView(label(text, 11f, if (on) cGold else cMuted, medium, 0.06f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                background = if (on) rounded(alpha(cGold, 0.14f), alpha(cGold, 0.7f), 10f) else rounded(Color.TRANSPARENT, cLine, 10f)
                setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(8f))
                setOnClickListener { tab = key; applyTab() }
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { if (key != "CROP") leftMargin = dpi(6f) })
        }
        cropLayer.visibility = if (tab == "CROP") View.VISIBLE else View.GONE
        artLayer.visibility = if (tab == "ART") View.VISIBLE else View.GONE
        blendLayer.visibility = if (tab == "BLEND") View.VISIBLE else View.GONE
        crop.editingSecond = tab == "BLEND"          // in Blend, your fingers move the second photo; elsewhere, the crop
        crop.focusMode = tab == "ART"                // in Art, a tap picks what stays sharp
    }

    // ------------------------------------------------------------- double exposure
    private fun applyBlendUi() {
        blendActions.removeAllViews(); blendRow.removeAllViews()
        if (secondUri == null) {
            blendActions.addView(chip("+ ADD SECOND PHOTO", true) { pickSecond.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
            blendRow.addView(label("Lay another photo over this one, then crop and style the pair.", 11.5f, cMuted))
            blendSlider.visibility = View.INVISIBLE
            (blendValue.parent as View).visibility = View.INVISIBLE
            return
        }
        blendActions.addView(chip("REPLACE", false) { pickSecond.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        blendActions.addView(chip("FIT", false) { crop.resetSecond() }, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(8f) })
        blendActions.addView(chip("REMOVE", false) { removeSecond() }, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(8f) })
        blendActions.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        blendActions.addView(label("DRAG · PINCH · TWIST", 9f, cMuted, mono, 0.1f))
        for (b in Blend.values()) {
            blendRow.addView(chip(b.label.uppercase(Locale.US), b == crop.blend) { crop.blend = b; rememberSecond(); applyBlendUi(); applyTab() },
                LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        blendSlider.visibility = View.VISIBLE
        (blendValue.parent as View).visibility = View.VISIBLE
        blendSlider.progress = (crop.strength * 100).roundToInt()
        blendValue.text = "${(crop.strength * 100).roundToInt()}%"
    }

    /** [restoring] carries a remembered placement; null means fit it fresh. */
    private fun loadSecond(uri: Uri, restoring: List<String>?) {
        note("Adding the second photo…")
        io.execute {
            val t = runCatching { Exposure.decode(contentResolver, uri, maxLong = 1800) }.getOrNull()
            runOnUiThread {
                if (t == null) { note(if (restoring != null) null else "That photo couldn’t be opened."); if (restoring != null) prefs.edit().remove("dbl:$src").apply(); return@runOnUiThread }
                secondUri = uri
                crop.second = t
                if (restoring != null && restoring.size >= 7) {
                    crop.blend = runCatching { Blend.valueOf(restoring[1]) }.getOrDefault(Blend.SCREEN)
                    crop.strength = restoring[2].toFloat()
                    crop.s2x = restoring[3].toFloat() * crop.bw; crop.s2y = restoring[4].toFloat() * crop.bh
                    crop.s2w = restoring[5].toFloat(); crop.s2r = restoring[6].toFloat()
                    crop.second = t
                } else crop.resetSecond()
                rememberSecond(); applyBlendUi(); applyTab(); note(null)
            }
        }
    }

    private fun removeSecond() {
        secondUri = null; crop.second = null
        prefs.edit().remove("dbl:$src").apply()
        applyBlendUi(); applyTab()
    }

    private fun rememberSecond() {
        val u = secondUri ?: return
        val c = crop
        prefs.edit().putString("dbl:$src", listOf(u, c.blend.name, c.strength, c.s2x / c.bw, c.s2y / c.bh, c.s2w, c.s2r).joinToString("|")).apply()
    }

    private fun restoreSecond() {
        val p = prefs.getString("dbl:$src", null)?.split("|") ?: return
        runCatching { loadSecond(Uri.parse(p[0]), p) }
    }

    private fun applyArtUi() {
        artRow.removeAllViews(); paramRow.removeAllViews()
        if (!Art.supported) { paramRow.addView(label("Art modes need Android 13 or newer.", 12f, cMuted)); artSlider.visibility = View.INVISIBLE; return }
        for (m in ArtMode.values()) {
            artRow.addView(chip(m.label, m == recipe.mode) {
                recipe = Recipe(m, Art.loadValues(prefs, m), recipe.seed, recipe.fx, recipe.fy); artParam = 0
                if (m == ArtMode.HOLGA && ratio != "1:1") { ratio = "1:1"; applyRatio(reset = false) }   // Holga is a square camera
                crop.recipe = recipe; rememberArt(); applyArtUi(); applyTab()
            }, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        if (recipe.mode == ArtMode.OFF) {
            paramRow.addView(label("Pick a look. It's saved as a copy, like the crop.", 11.5f, cMuted))
            artSlider.visibility = View.INVISIBLE; return
        }
        recipe.mode.shown.forEach { i ->
            val on = i == artParam
            paramRow.addView(label(recipe.mode.sliders[i].first.uppercase(Locale.US), 10f, if (on) cGold else cMuted, mono, 0.1f).apply {
                setPadding(0, 0, dpi(12f), 0); setOnClickListener { artParam = i; applyArtUi() }
            })
        }
        if (!focusHinted && recipe.mode != ArtMode.TILT_SHIFT) {
            focusHinted = true
            note("Tap the photo to choose what stays sharp"); crop.postDelayed({ note(null) }, 2600)
        }
        paramRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        paramRow.addView(label("${(recipe.values[artParam] * 100).roundToInt()}", 11f, cGold, mono))
        artSlider.visibility = View.VISIBLE
        artSlider.progress = (recipe.values[artParam] * 100).roundToInt()
    }

    private fun onArtSlider(v: Float) {
        recipe.values[artParam] = v
        (paramRow.getChildAt(paramRow.childCount - 1) as? TextView)?.text = "${(v * 100).roundToInt()}"
        crop.invalidate(); rememberArt()
    }

    private fun rememberArt() { prefs.edit().putString("art:$src", recipe.encode()).apply() }

    private fun note(text: String?) {
        if (text == null) { noteView.visibility = View.GONE; return }
        noteView.text = text; noteView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- ratio + guide
    private fun longOverShort(r: String): Float = when (r) {
        "PHI" -> PHI.toFloat(); "4:5" -> 1.25f; "1:1" -> 1f; "3:2" -> 1.5f
        else -> max(crop.bw, crop.bh) / min(crop.bw, crop.bh)
    }

    private fun frameGuide(): Guide {
        val g = runCatching { Guide.valueOf(prefs.getString("guide", Guide.THIRDS.name)!!) }.getOrDefault(Guide.THIRDS)
        return if (g == Guide.SPIRAL || g == Guide.OFF) Guide.THIRDS else g
    }

    private fun applyRatio(reset: Boolean) {
        val los = longOverShort(ratio)
        crop.aspect = if (portrait) 1f / los else los
        crop.guide = if (ratio == "PHI") Guide.SPIRAL else frameGuide()
        crop.quadrant = prefs.getInt("quadrant", 0).let { if (crop.quadrant in 0..3 && !reset) crop.quadrant else it }
        crop.triFlip = prefs.getBoolean("triFlip", false)
        if (reset) crop.reset() else crop.refit()
        buildRows()
    }

    private fun buildRows() {
        ratioRow.removeAllViews()
        for ((key, lab) in listOf("PHI" to "φ", "ORIGINAL" to "ORIGINAL", "4:5" to "4:5", "1:1" to "1:1", "3:2" to "3:2")) {
            ratioRow.addView(chip(lab, key == ratio) { ratio = key; applyRatio(reset = false) }, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        ratioRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        ratioRow.addView(chip(if (portrait) "↕" else "↔", false) { portrait = !portrait; applyRatio(reset = false) })
        eyeRow.removeAllViews()
        if (ratio == "PHI") {
            eyeRow.addView(label("EYE", 10f, cMuted, mono, 0.13f))
            eyeRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val names = listOf("↖", "↗", "↙", "↘")
            for (q in 0..3) eyeRow.addView(chip(names[q], q == crop.quadrant) { crop.quadrant = q; crop.invalidate(); buildRows() },
                LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(8f) })
        } else {
            eyeRow.addView(label("GUIDE · " + crop.guide.label, 10f, cMuted, mono, 0.13f))
        }
        saveBtn.text = if (ratio == "PHI") "Save φ copy" else "Save copy"
        syncReadout()
    }

    private fun ratioText(): String = when (ratio) {
        "PHI" -> if (portrait) "1 : 1.618" else "1.618 : 1"
        "4:5" -> if (portrait) "4 : 5" else "5 : 4"
        "3:2" -> if (portrait) "2 : 3" else "3 : 2"
        "1:1" -> "1 : 1"
        else -> String.format(Locale.US, if (portrait) "1 : %.3f" else "%.3f : 1", longOverShort("ORIGINAL"))
    }

    private fun syncReadout() {
        if (!::readL.isInitialized || crop.bmp == null) return
        val w = (crop.cw * sample).roundToInt(); val h = (crop.ch * sample).roundToInt()
        readL.text = (if (ratio == "PHI") "φ CROP · " else "CROP · ") + "$w × $h px"
        readR.text = ratioText()
    }

    // ------------------------------------------------------------- remember per photo
    private fun key() = "crop:$src"

    private fun remember() {
        val c = crop
        prefs.edit().putString(key(), listOf(ratio, portrait, c.cx / c.bw, c.cy / c.bh, c.cw / c.bw, c.theta, c.quadrant).joinToString("|")).apply()
    }

    private fun restore(): Boolean {
        val p = prefs.getString(key(), null)?.split("|") ?: return false
        return runCatching {
            ratio = p[0]; portrait = p[1].toBoolean()
            crop.quadrant = p[6].toInt()
            applyRatio(reset = false)
            crop.cx = p[2].toFloat() * crop.bw; crop.cy = p[3].toFloat() * crop.bh; crop.cw = p[4].toFloat() * crop.bw
            crop.theta = p[5].toFloat(); dial.value = Math.toDegrees(crop.theta.toDouble()).toFloat()
            crop.refit(); buildRows(); true
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------- decode + save
    private fun decode(uri: Uri, display: Boolean, forceSample: Int = 0, mutable: Boolean = false): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = mutable
            if (display) {
                val long = max(info.size.width, info.size.height)
                var s = 1; while (long / (s * 2) >= 1800) s *= 2
                sample = s; decoder.setTargetSampleSize(s)
            } else if (forceSample > 1) decoder.setTargetSampleSize(forceSample)
        }
    }

    private fun save() {
        val view = crop.bmp ?: return
        if (busy) return
        busy = true; note("Saving a copy…"); remember()
        val cx = crop.cx; val cy = crop.cy; val cw = crop.cw; val ch = crop.ch; val deg = Math.toDegrees(crop.theta.toDouble()).toFloat()
        // the focus point travels with the crop: from across the photo to across the cropped, straightened copy
        val ffx = recipe.fx * crop.bw; val ffy = recipe.fy * crop.bh
        val ct = cos(crop.theta); val stt = sin(crop.theta)
        val fdx = ffx - cx; val fdy = ffy - cy
        val r = recipe.copy(values = recipe.values.copyOf(),
            fx = ((fdx * ct + fdy * stt) + cw / 2) / cw, fy = ((-fdx * stt + fdy * ct) + ch / 2) / ch)
        val dbl = secondUri?.let { u -> arrayOf(u.toString(), crop.blend.name, crop.strength, crop.s2x, crop.s2y, crop.s2w, crop.s2r) }
        val tag = (if (dbl != null) "double_" else "") + (if (ratio == "PHI") "phi" else ratio.replace(":", "x").lowercase(Locale.US)) +
            (if (r.mode != ArtMode.OFF && Art.supported) "_" + r.mode.fileTag else "")
        io.execute {
            val result = runCatching {
                var full = try { decode(src, display = false, mutable = dbl != null) } catch (e: OutOfMemoryError) { decode(src, display = false, forceSample = 2, mutable = dbl != null) }
                val f = full.width.toFloat() / view.width
                if (dbl != null) {
                    // the pair first, at full size, exactly as placed on screen; then crop, then art
                    val top = Exposure.decode(contentResolver, Uri.parse(dbl[0] as String), maxLong = max(full.width, full.height))
                    full = Exposure.placeOnto(full, top, (dbl[3] as Float) * f, (dbl[4] as Float) * f, dbl[5] as Float, dbl[6] as Float,
                        Blend.valueOf(dbl[1] as String), dbl[2] as Float)
                    top.recycle()
                }
                val ow = (cw * f).roundToInt().coerceAtLeast(16); val oh = (ch * f).roundToInt().coerceAtLeast(16)
                var out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                Canvas(out).apply {
                    drawColor(Color.BLACK)
                    translate(ow / 2f, oh / 2f); rotate(-deg); translate(-cx * f, -cy * f)   // crop and straighten in one step
                    drawBitmap(full, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                full.recycle()
                if (r.mode != ArtMode.OFF && Build.VERSION.SDK_INT >= 33) {
                    val styled = Art.render(out, r)                   // the look, measured against the crop
                    if (styled !== out) { out.recycle(); out = styled }
                }
                val (name, taken) = originalInfo()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}_${tag}_" + SimpleDateFormat("HHmmss", Locale.US).format(Date()) + ".jpg")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fibonacci Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                    if (taken != null) put(MediaStore.Images.Media.DATE_TAKEN, taken)   // sorts next to the original
                }
                val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
                contentResolver.openOutputStream(dest).use { os -> out.compress(Bitmap.CompressFormat.JPEG, 95, os!!) }
                contentResolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                out.recycle()
                "$ow × $oh"
            }
            runOnUiThread {
                busy = false
                result.onSuccess { note("Saved a $it copy · original untouched"); rememberArt(); crop.postDelayed({ note(null) }, 2600) }
                    .onFailure { note("Couldn’t save: ${it.message}") }
            }
        }
    }

    private fun originalInfo(): Pair<String, Long?> = runCatching {
        contentResolver.query(src, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.Images.Media.DATE_TAKEN), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val n = c.getString(0)?.substringBeforeLast('.') ?: "photo"
            val t = if (c.isNull(1)) null else c.getLong(1)
            n to t
        }
    }.getOrNull() ?: ("photo" to null)
}
