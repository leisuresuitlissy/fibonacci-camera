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
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import kotlin.math.max
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

    private var ratio = "PHI"            // PHI, ORIGINAL, 4:5, 1:1, 3:2
    private var portrait = true
    private var sample = 1
    private var busy = false

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
                crop.post { if (!restore()) applyRatio(reset = true); note(null) }
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
        t.addView(label("Crop a copy", 15f, cText, Typeface.DEFAULT_BOLD))
        t.addView(label("ORIGINAL IS NEVER CHANGED", 9.5f, cMuted, mono, 0.1f).apply { setPadding(0, dpi(3f), 0, 0) })
        top.addView(t, LinearLayout.LayoutParams(0, wrap, 1f))
        top.addView(chip("OTHER PHOTO", false) { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        crop = CropView(this).apply { onChange = { syncReadout() } }
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

        ratioRow = row(); root.addView(ratioRow, LinearLayout.LayoutParams(match, dpi(48f)))
        eyeRow = row(); root.addView(eyeRow, LinearLayout.LayoutParams(match, dpi(48f)))
        dial = DialView(this).apply { onChange = { v -> crop.theta = Math.toRadians(v.toDouble()).toFloat(); crop.refit() } }
        root.addView(dial, LinearLayout.LayoutParams(match, dpi(64f)))

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
    private fun decode(uri: Uri, display: Boolean, forceSample: Int = 0): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
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
        val tag = if (ratio == "PHI") "phi" else ratio.replace(":", "x").lowercase(Locale.US)
        io.execute {
            val result = runCatching {
                val full = try { decode(src, display = false) } catch (e: OutOfMemoryError) { decode(src, display = false, forceSample = 2) }
                val f = full.width.toFloat() / view.width
                val ow = (cw * f).roundToInt().coerceAtLeast(16); val oh = (ch * f).roundToInt().coerceAtLeast(16)
                val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                Canvas(out).apply {
                    drawColor(Color.BLACK)
                    translate(ow / 2f, oh / 2f); rotate(-deg); translate(-cx * f, -cy * f)   // crop and straighten in one step
                    drawBitmap(full, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
                }
                full.recycle()
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
                result.onSuccess { note("Saved a $it copy · original untouched"); crop.postDelayed({ note(null) }, 2600) }
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
