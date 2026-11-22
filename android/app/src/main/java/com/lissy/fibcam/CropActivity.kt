package com.lissy.fibcam

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.os.Build
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
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
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Edit a copy. The original is only ever read: every save writes a new file beside it, so you can crop,
 * compose and style the same photo as many ways as you like. The last edit is remembered per photo.
 *
 * It is the camera's own screen adapted for a still photo: the same tokens (T), tiles (Catalog, TileView),
 * Look controls (LookControls, ArtMode.controls) and shaders (Art), with the crop as the frame.
 */
/**
 * The editor's saved copy, from a full-size photo: crop and straighten in one step, then the look through
 * Art.render, the same function the camera uses for its look photos. Shared by Save a copy and the CI
 * check that compares it with the camera (EditParity).
 */
object EditRender {
    /** [fh] and [mask]: CropView.renderFactor and renderMask (Plastic renders on the 3:4 frame around a square crop). */
    fun copy(full: Bitmap, cx: Float, cy: Float, cw: Float, ch: Float, deg: Float, f: Float, r: Recipe, mask: FocusMask, fh: Float = 1f): Bitmap {
        val ow = (cw * f).roundToInt().coerceIn(16, max(16, full.width)); val oh = (ch * f).roundToInt().coerceIn(16, max(16, full.height))
        val styling = r.mode != ArtMode.OFF && Build.VERSION.SDK_INT >= 33
        val rh = if (styling && fh > 1f) (oh * fh).roundToInt() else oh       // the render frame's height
        var out = Bitmap.createBitmap(ow, rh, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            translate(ow / 2f, rh / 2f); rotate(-deg); translate(-cx * f, -cy * f)   // crop and straighten in one step
            if (rh == oh) drawBitmap(full, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            // Plastic's taller frame can reach past the photo: edge pixels carry on, as in the preview
            else drawPaint(Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = android.graphics.BitmapShader(full, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP) })
        }
        if (styling) {
            val styled = Art.render(out, r, mask)         // the look, measured against its frame
            if (styled !== out) { out.recycle(); out = styled }
            if (rh != oh) {                                // keep the crop, as Art.finish keeps Plastic's square
                val kept = Bitmap.createBitmap(out, 0, (rh - oh) / 2, ow, oh)
                if (kept !== out) { out.recycle(); out = kept }
            }
        }
        return out
    }
}

class CropActivity : ComponentActivity() {

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private val prefs by lazy { getSharedPreferences("fibcam", Context.MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var src: Uri
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var crop: CropView
    private lateinit var header: FrameLayout
    private lateinit var cropPill: LinearLayout
    private lateinit var doubleBtn: ImageView
    private lateinit var hudStack: LinearLayout
    private lateinit var noteView: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var selector: FrameLayout
    private lateinit var composeScroller: FadeScroller
    private lateinit var lookScroller: FadeScroller
    private lateinit var ctxArea: FrameLayout
    private lateinit var composeSub: LinearLayout
    private lateinit var lookControls: LookControls
    // Crop, from Compose: on, the whole photo with the crop box; off, the crop fills the frame like the viewfinder.
    private lateinit var cropPanel: LinearLayout
    private lateinit var ratioRow: LinearLayout
    private lateinit var readL: TextView
    private lateinit var dial: DialView
    private lateinit var bottomRow: FrameLayout
    private val composeTiles = HashMap<Guide, TileView>()
    private val lookTiles = HashMap<ArtMode, TileView>()
    private var insetTop = 0
    private var insetBottom = 0
    private var secondUri: Uri? = null
    private var tab = "COMPOSE"          // COMPOSE or ART, as in the camera; framing first
    private var cropMode = false
    /** This photo's editing state. Starts from your shooting settings for a look, but never writes back to them. */
    private var recipe = Recipe(ArtMode.OFF, FloatArray(Art.SLOTS), (Math.random() * 1000).toFloat())
    /** Each look's settings while you edit this photo, so switching away and back keeps them (as in the camera). */
    private val lookValues = HashMap<ArtMode, FloatArray>()
    private var focusHinted = false
    /** The active control: an index into the look's controls, as in the camera. */
    private var artParam = 0
    private val artSlot get() = recipe.mode.controls.getOrNull(artParam)?.slot ?: 0

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
        T.init(this)
        val u = intent.data ?: intent.getStringExtra("editFile")?.let { Uri.fromFile(java.io.File(it)) }   // editFile: CI only
        if (u == null) { finish(); return }
        src = u
        if (intent.hasExtra("editFile")) prefs.edit().remove("crop:$src").remove("art:$src").remove("dbl:$src").apply()   // CI: start clean
        if (intent.getBooleanExtra("editParity", false)) Thread {
            // CI: every look through the camera's save path and the editor's, compared pixel by pixel
            android.os.Looper.prepare()
            val d = runCatching { EditParity.run(this) }.onFailure { android.util.Log.e("fibParity", "failed", it) }.getOrNull()
            android.util.Log.i("fibParity", "done ${d?.path}")
        }.start()
        setContentView(buildUi())
        // Back steps out one level at a time: Crop → off; a Look's setting → its first; then the camera
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    cropMode -> setCropMode(false)
                    tab == "ART" && recipe.mode != ArtMode.OFF && artParam != 0 -> { artParam = 0; applyArtUi() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
        note("Loading…")
        io.execute {
            val b = runCatching { decode(src, display = true) }.getOrNull()
            runOnUiThread {
                if (b == null) { note("That photo couldn’t be opened."); return@runOnUiThread }
                crop.bmp = b
                portrait = b.height >= b.width
                Recipe.decode(prefs.getString("art:$src", null))?.let { if (Art.supported) { recipe = it; recipeRestored = true } }
                if (recipe.mode != ArtMode.OFF) lookValues[recipe.mode] = recipe.values
                crop.recipe = recipe
                crop.post {
                    if (!restore()) applyRatio(reset = true)
                    // CI hook (MainActivity › openEditTest): a ratio and straightening, a look, a tab, and optionally a save
                    if (intent.hasExtra("editFile")) {
                        intent.getStringExtra("editRatio")?.let { r -> if (r != "-") { ratio = r.replace('x', ':'); applyRatio(reset = true) } }
                        intent.getFloatExtra("editTheta", 0f).takeIf { it != 0f }?.let { t -> dial.value = t; crop.theta = Math.toRadians(t.toDouble()).toFloat(); crop.refit() }
                        intent.getStringExtra("editMode")?.let { m -> runCatching { ArtMode.valueOf(m) }.getOrNull()?.let { if (it != ArtMode.OFF) selectLook(it) } }
                        intent.getStringExtra("editTab")?.let { t -> when (t) { "CROP" -> { tab = "COMPOSE"; cropMode = true }; "COMPOSE" -> tab = "COMPOSE"; "DOUBLE" -> crop.post { showDoubleSheet() }; else -> tab = "ART" } }
                    }
                    applyTab(); applyGuideUi(); applyArtUi(); note(null); restoreSecond()
                    if (intent.getBooleanExtra("editSave", false)) crop.postDelayed({ save() }, 800)
                    if (intent.hasExtra("editFile")) crop.postDelayed({
                        // CI: where the crop sits on screen, so the preview can be compared with the saved copy
                        val loc = IntArray(2); crop.getLocationOnScreen(loc)
                        android.util.Log.i("fibEdit", "corners " + crop.screenCorners().joinToString(" ") { "${it.x + loc[0]},${it.y + loc[1]}" })
                    }, 1500)
                }
            }
        }
    }

    override fun onDestroy() { super.onDestroy(); io.shutdown() }

    // ------------------------------------------------------------- UI (the camera's components and tokens)
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun bmp(id: Int): Bitmap? = android.graphics.BitmapFactory.decodeResource(resources, id, android.graphics.BitmapFactory.Options().apply { inScaled = false })

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
    private fun at(v: View, x: Int, y: Int, w: Int, h: Int) {
        val l = v.layoutParams as FrameLayout.LayoutParams
        if (l.leftMargin != x || l.topMargin != y || l.width != w || l.height != h) { l.leftMargin = x; l.topMargin = y; l.width = w; l.height = h; l.gravity = Gravity.NO_GRAVITY; v.layoutParams = l }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    /** The camera's round Carbon icon button (MainActivity.roundIcon). */
    private fun roundIcon(icon: Int, size: Float, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER
        background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(T.CARBON) }
        contentDescription = desc; setOnClickListener { onClick() }
        layoutParams = lp(dpi(size), dpi(size))
    }
    /** The camera's header toggle state (MainActivity.setActive): a 1dp Amber ring and an Amber icon. */
    private fun setActive(v: ImageView, on: Boolean) {
        (v.background as android.graphics.drawable.GradientDrawable).setStroke(if (on) dpi(1f).coerceAtLeast(1) else 0, T.AMBER)
        v.setColorFilter(if (on) T.AMBER else T.IVORY)
    }
    /** The camera's sheet (MainActivity.sheet and sheetButton). */
    private fun sheet(build: (LinearLayout, android.app.Dialog) -> Unit): android.app.Dialog {
        val dlg = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(20f), dpi(20f), dpi(16f))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(T.GRAPHITE); cornerRadius = dp(16f) }
        }
        build(box, dlg)
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        return dlg
    }
    private fun label(t: String, size: Float, color: Int, face: android.graphics.Typeface = T.regular, spacing: Float = 0f) = TextView(this).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face; letterSpacing = spacing; includeFontPadding = false
    }
    private fun sheetButton(t: String, primary: Boolean, onClick: () -> Unit) = label(t, 14f, if (primary) T.CARBON else T.IVORY, T.medium).apply {
        gravity = Gravity.CENTER; setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        background = android.graphics.drawable.GradientDrawable().apply { setColor(if (primary) T.AMBER else T.CARBON); cornerRadius = dp(12f); if (!primary) setStroke(dpi(1f).coerceAtLeast(1), T.BORDER) }
        setOnClickListener { onClick() }
    }

    /**
     * The camera's screen, with the photo as the viewfinder: the same header, tabs, selector row, contextual
     * area and bottom row, at the same places (see [relayout]). Coach's place holds Crop; Double exposure
     * sits where it does in the camera; the shutter row holds the editor's own actions.
     */
    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(T.CARBON) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (b.top != insetTop || b.bottom != insetBottom) { insetTop = b.top; insetBottom = b.bottom; root.post { relayout() } }
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) root.post { relayout() } }

        // the photo, where the viewfinder is
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        crop = CropView(this).apply {
            onChange = { syncReadout() }; onSecondChange = { rememberSecond() }
            onFocusTap = { u, v ->
                // a tap pins what stays sharp to this spot of the photo (and turns Follow guide off: you chose)
                val cur = this@CropActivity.recipe
                val vals = cur.values.copyOf()
                if (cur.mode == ArtMode.TILT_SHIFT) imageToCrop(u, v).let { p -> vals[1] = Focus.tiltFocusThrough(p.x, p.y, (vals[3] - 0.5f) * 1.5707964f, cw / ch) }
                val r = cur.copy(values = vals, follow = false, mask = cur.mask.copy(cx = u, cy = v))
                this@CropActivity.recipe = r; recipe = r; lookValues[r.mode] = r.values; flashMask(); rememberArt(); applyArtUi(); syncFollow()
            }
        }
        stage.addView(crop, lp(match, match))
        root.addView(stage, lp(0, 0))

        // messages under the header, as in the camera
        hudStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        noteView = text("", 12f, T.AMBER, T.medium).apply {
            background = pill(T.alpha(T.CARBON, 0.85f), dp(16f), T.AMBER, dpi(1f)); setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f)); visibility = View.GONE
        }
        hudStack.addView(noteView, LinearLayout.LayoutParams(wrap, wrap))
        root.addView(hudStack, lp(0, 0))

        // header: Double exposure where it is in the camera (Coach and flash are camera-only)
        header = FrameLayout(this)
        doubleBtn = roundIcon(R.drawable.ic_double, 34f, "Double exposure") { onDoubleBtn() }
        header.addView(doubleBtn, lp(dpi(34f), dpi(34f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(48f) })
        root.addView(header, lp(0, 0))

        // tabs: the camera's
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)); background = pill(T.GRAPHITE, dp(16f)) }
        for ((key, name) in listOf("COMPOSE" to "Compose", "ART" to "Look")) {
            tabRow.addView(text(name, 13f, T.IVORY, T.medium).apply {
                gravity = Gravity.CENTER; tag = key
                setOnClickListener { if (tab != key || cropMode) { tab = key; cropMode = false; remember(); applyTab(); syncReadout() } }
            }, LinearLayout.LayoutParams(0, match, 1f).apply { if (key != "COMPOSE") leftMargin = dpi(8f) })
        }
        root.addView(tabRow, lp(0, 0))

        // selector row: the camera's guides or looks
        selector = FrameLayout(this)
        composeScroller = FadeScroller(this, dp(36f))
        val cRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        Catalog.guides.forEachIndexed { i, (g, name, res) ->
            val tile = TileView(this, bmp(res), 60f, 50f, RectF(0f, 0f, 60f, 50f), 4f, T.GRAPHITE, name, 11f, crop = false).apply {
                contentDescription = "$name guide"; setOnClickListener { if (crop.guide != g) { crop.guide = g; crop.anchor = prefs.getInt("anchor:${g.name}", 0); onGuideChanged() } }
            }
            composeTiles[g] = tile
            cRow.addView(tile, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        composeScroller.addView(cRow, FrameLayout.LayoutParams(wrap, match))
        selector.addView(composeScroller, lp(match, match))
        lookScroller = FadeScroller(this, dp(36f))
        val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        Catalog.looks.forEachIndexed { i, (m, name, res) ->
            val tile = TileView(this, bmp(res), 60f, 50f, RectF(0f, 0f, 60f, 50f), 8f, null, name, 11f, crop = true).apply {
                contentDescription = "$name look"; setOnClickListener { selectLook(m) }
            }
            lookTiles[m] = tile
            lRow.addView(tile, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        lookScroller.addView(lRow, FrameLayout.LayoutParams(wrap, match))
        selector.addView(lookScroller, lp(match, match))
        root.addView(selector, lp(0, 0))

        // contextual area: the camera's guide variants with Crop where the camera has Follow guide, or the Look controls
        ctxArea = FrameLayout(this)
        composeSub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctxArea.addView(composeSub, lp(wrap, wrap))
        cropPill = cropToggle()
        ctxArea.addView(cropPill, lp(wrap, dpi(44f), Gravity.END or Gravity.TOP).apply { topMargin = -dpi(6f) })
        lookControls = LookControls(this).apply {
            onSelect = { i -> artParam = i; applyArtUi(); if (recipe.mode.isFocusSlider(artSlot)) crop.flashMask() }
            onChoice = { i ->
                recipe.values[5] = i.toFloat()
                if (recipe.mode.isRandom(i)) recipe = recipe.copy(seed = (Math.random() * 1000).toFloat())
                crop.recipe = recipe; rememberArt(); applyArtUi()
            }
            onReset = {
                // this photo's look back to the look's starting settings; your shooting settings are untouched
                recipe = crop.pinTilt(recipe.copy(values = recipe.mode.defaults, follow = false)); lookValues[recipe.mode] = recipe.values
                crop.recipe = recipe; rememberArt(); applyArtUi(); syncFollow()
            }
            slider.onChange = { v -> onArtSlider(v) }
        }
        ctxArea.addView(lookControls, lp(match, wrap))
        root.addView(ctxArea, lp(0, 0))

        // Crop's own controls take the tabs' and selector's place while it's on
        cropPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        ratioRow = row()
        cropPanel.addView(ratioRow, LinearLayout.LayoutParams(match, dpi(31f)).apply { topMargin = dpi(17f) })
        val read = row()
        read.addView(text("Straighten", 11f, T.IVORY, T.medium, caps = true))
        readL = text("", 12f, T.SUBTLE, T.medium).apply { gravity = Gravity.CENTER; maxLines = 1 }
        read.addView(readL, LinearLayout.LayoutParams(0, wrap, 1f))
        read.addView(text("Done", 11f, T.GRAPHITE, T.semibold, caps = true).apply {
            gravity = Gravity.CENTER
            background = android.graphics.drawable.InsetDrawable(pill(T.AMBER, dp(13f)), 0, dpi(9f), 0, dpi(9f))
            setPadding(dpi(14f), 0, dpi(14f), 0)   // after the background, which resets padding
            contentDescription = "Done cropping"; setOnClickListener { setCropMode(false) }
        }, LinearLayout.LayoutParams(wrap, dpi(44f)))
        cropPanel.addView(read, LinearLayout.LayoutParams(match, dpi(44f)).apply { topMargin = dpi(17f) })
        dial = DialView(this).apply { onChange = { v -> crop.theta = Math.toRadians(v.toDouble()).toFloat(); crop.refit() } }
        cropPanel.addView(dial, LinearLayout.LayoutParams(match, dpi(58f)))
        root.addView(cropPanel, lp(0, 0))

        // the shutter row's places: back to the camera, another photo, save, reset
        bottomRow = FrameLayout(this).apply { setBackgroundColor(T.CARBON) }
        bottomRow.addView(roundIcon(R.drawable.ic_back, 44f, "Back to the camera") { finish() })
        bottomRow.addView(roundIcon(R.drawable.ic_import, 44f, "Edit another photo") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
        bottomRow.addView(text("Save", 14f, T.GRAPHITE, T.semibold).apply {
            gravity = Gravity.CENTER; background = pill(T.AMBER, dp(24f)); contentDescription = "Save a copy. The original is never changed."
            setOnClickListener { save() }
        }, lp(0, 0))
        bottomRow.addView(roundIcon(R.drawable.ic_reset_cam, 44f, "Reset the crop and straightening") { dial.value = 0f; crop.theta = 0f; applyRatio(reset = true); remember() })
        root.addView(bottomRow, lp(0, 0))
        return root
    }

    /** The camera's layout (MainActivity.relayout), with the photo in the viewfinder's place. */
    private fun relayout() {
        val w = root.width; val h = root.height
        if (w <= 0 || h <= 0) return
        val rowTop = h - insetBottom - dpi(81f)
        val maxBottom = rowTop - dpi(200f)
        val fullH = w * 4 / 3
        var vfTop = insetTop
        if (vfTop + fullH > maxBottom) vfTop = max(0, maxBottom - fullH)
        val vfH = max(dpi(200f), min(fullH, maxBottom - vfTop))
        val vfBottom = vfTop + vfH
        at(stage, 0, vfTop, w, vfH)
        val headTop = max(vfTop, insetTop) + dpi(12f)
        at(header, dpi(16f), headTop, w - dpi(32f), dpi(34f))
        at(hudStack, 0, headTop + dpi(46f), w, wrap)
        crop.inset = (headTop + dpi(46f) - vfTop).toFloat()   // the whole photo sits clear of the header while cropping
        at(tabRow, dpi(16f), vfBottom + dpi(12f), w - dpi(32f), dpi(31f))
        at(selector, 0, vfBottom + dpi(53f), w, dpi(65f))
        val ctxTop = vfBottom + dpi(128f)
        val ctxH = max(0, rowTop + dpi(12f) - ctxTop)
        at(ctxArea, dpi(16f), ctxTop, w - dpi(32f), ctxH)
        lookControls.available = ctxH
        at(cropPanel, dpi(16f), vfBottom + dpi(53f), w - dpi(32f), max(0, rowTop + dpi(12f) - vfBottom - dpi(53f)))   // the selector's and contextual area's place
        at(bottomRow, 0, rowTop, w, h - rowTop)
        val cx = w / 2
        val kids = (0 until bottomRow.childCount).map { bottomRow.getChildAt(it) }
        at(kids[0], dpi(16f), dpi(26f), dpi(44f), dpi(44f))
        at(kids[1], cx - dpi(84f) - dpi(22f), dpi(26f), dpi(44f), dpi(44f))
        at(kids[2], cx - dpi(54f), dpi(24f), dpi(108f), dpi(48f))
        at(kids[3], cx + dpi(84f) - dpi(22f), dpi(26f), dpi(44f), dpi(44f))
    }

    private fun applyTab() {
        for (i in 0 until tabRow.childCount) {
            val v = tabRow.getChildAt(i) as TextView; val on = v.tag == tab
            v.setTextColor(if (on) T.GRAPHITE else T.IVORY)
            v.typeface = if (on) T.semibold else T.medium
            v.background = if (on) pill(T.AMBER, dp(24f)) else null
        }
        val c = tab == "COMPOSE" && !cropMode; val a = tab == "ART" && !cropMode
        val main = if (cropMode) View.GONE else View.VISIBLE
        selector.visibility = main; ctxArea.visibility = main
        cropPanel.visibility = if (cropMode) View.VISIBLE else View.GONE
        composeScroller.visibility = if (c) View.VISIBLE else View.GONE
        lookScroller.visibility = if (a) View.VISIBLE else View.GONE
        composeSub.visibility = if (c) View.VISIBLE else View.GONE
        cropPill.visibility = if (c) View.VISIBLE else View.GONE
        lookControls.visibility = if (a) View.VISIBLE else View.GONE
        if (c) composeTiles[crop.guide]?.let { composeScroller.reveal(it, smooth = false) }
        if (a) lookTiles[recipe.mode]?.let { lookScroller.reveal(it, smooth = false) }
        // Crop on: the whole photo and the crop box, and your fingers move the box. Off: the crop fills the
        // frame like the viewfinder; with a second photo, your fingers move that instead
        crop.framed = !cropMode
        crop.editingSecond = !cropMode && secondUri != null
        crop.focusMode = a
        setActive(doubleBtn, secondUri != null)
        doubleBtn.contentDescription = if (secondUri != null) "Double exposure on" else "Double exposure off"
    }

    /** Compose's Crop pill: the camera's Follow guide pill (FollowToggle), with a crop mark. */
    private fun cropToggle() = row().apply {
        gravity = Gravity.CENTER
        addView(ImageView(this@CropActivity).apply { setImageResource(R.drawable.ic_crop); setColorFilter(T.IVORY) }, LinearLayout.LayoutParams(dpi(14f), dpi(14f)))
        addView(text("Crop", 11f, T.IVORY, T.medium, caps = true), LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(5f) })
        background = android.graphics.drawable.InsetDrawable(pill(T.CARBON, dp(13f), T.IVORY, dpi(1f).coerceAtLeast(1)), 0, dpi(9f), 0, dpi(9f))
        setPadding(dpi(10f), 0, dpi(10f), 0)
        contentDescription = "Crop and straighten"
        setOnClickListener { setCropMode(true) }
    }

    private fun setCropMode(on: Boolean) {
        if (cropMode == on) return
        cropMode = on
        if (on) tab = "COMPOSE"
        if (!on) remember()
        applyTab(); syncReadout()
    }
    // ------------------------------------------------------------- composition (the camera's guides, on the crop)
    private fun onGuideChanged() {
        placeTiltOnGuide(); crop.recipe = recipe
        rememberGuide(); applyGuideUi(); applyArtUi()
        if (recipe.follow && recipe.mode != ArtMode.OFF) crop.flashMask()
    }

    private fun applyGuideUi() {
        for ((g, tile) in composeTiles) tile.chosen = g == crop.guide
        composeSub.removeAllViews()
        Catalog.variants(crop.guide).forEachIndexed { i, (name, res) ->
            val sel = if (crop.guide == Guide.SPIRAL) i == crop.quadrant else (i == 1) == crop.triFlip
            composeSub.addView(TileView(this, bmp(res), 48f, 35f, RectF(3f, 1f, 43f, 34f), 4f, T.GRAPHITE, name, 11f, crop = false).apply {
                chosen = sel; contentDescription = name
                setOnClickListener { if (crop.guide == Guide.SPIRAL) crop.quadrant = i else crop.triFlip = i == 1; crop.invalidate(); onGuideChanged() }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        syncFollow(); crop.invalidate(); syncReadout()
    }

    /**
     * Follow guide has no switch here: a photo starts with your camera setting (the look's sharp area on the
     * guide's focal point), and tapping the photo in Look pins it to the spot you tap instead.
     */
    private fun syncFollow() {
        val available = GuideShape.hasAnchors(crop.guide)
        crop.selectedAnchor = if (recipe.follow && available && recipe.mode != ArtMode.OFF) crop.anchor else -1
        crop.invalidate()
    }

    // ------------------------------------------------------------- double exposure
    /** Off: pick the second photo. On: its options, in the camera's sheet. */
    private fun onDoubleBtn() {
        if (secondUri == null) pickSecond.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else showDoubleSheet()
    }

    private fun showDoubleSheet() {
        sheet { box, dlg ->
            box.addView(label("Double exposure", 17f, T.IVORY, T.semibold))
            box.addView(label("The second photo lies over this one. Drag, pinch or twist it on the photo; turn Crop on to frame the pair.", 13f, T.SUBTLE).apply { setPadding(0, dpi(6f), 0, dpi(16f)) })
            box.addView(label("BLEND", 10f, T.AMBER, T.medium, 0.08f))
            val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8f), 0, dpi(14f)) }
            fun paint() { for (i in 0 until modes.childCount) { val t = modes.getChildAt(i) as TextView; val on = t.tag == crop.blend
                t.setTextColor(if (on) T.AMBER else T.IVORY); t.background = pill(T.CARBON, dp(13f), if (on) T.AMBER else T.BORDER, dpi(1f)) } }
            for (b in Blend.values()) modes.addView(text(b.label, 11f, T.IVORY, T.medium, caps = true).apply {
                tag = b; gravity = Gravity.CENTER; setPadding(dpi(10f), 0, dpi(10f), 0)
                setOnClickListener { crop.blend = b; rememberSecond(); paint() }
            }, LinearLayout.LayoutParams(wrap, dpi(26f)).apply { rightMargin = dpi(8f) })
            paint()
            box.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(modes) })
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(label("STRENGTH", 10f, T.AMBER, T.medium, 0.08f)); head.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            val sv = label("${(crop.strength * 100).roundToInt()}%", 11f, T.AMBER, T.medium); head.addView(sv)
            box.addView(head)
            box.addView(ValueSlider(this).apply { value = crop.strength
                onChange = { v -> crop.strength = v; sv.text = "${(v * 100).roundToInt()}%" }
                onStop = { rememberSecond() } }, LinearLayout.LayoutParams(match, dpi(18f)).apply { topMargin = dpi(10f); bottomMargin = dpi(18f) })
            box.addView(sheetButton(if (secondUri == null) "Choose the second photo" else "Replace the second photo", false) { dlg.dismiss(); pickSecond.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
            if (secondUri != null) {
                box.addView(sheetButton("Fit it to the photo", false) { crop.resetSecond(); rememberSecond(); dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
                box.addView(sheetButton("Remove the second photo", false) { removeSecond(); dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
            }
            box.addView(sheetButton("Done", true) { dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap))
        }
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
                rememberSecond(); applyTab(); note(null)
            }
        }
    }

    private fun removeSecond() {
        secondUri = null; crop.second = null
        prefs.edit().remove("dbl:$src").apply()
        applyTab()
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

    // ------------------------------------------------------------- looks (the camera's picker and controls)
    /**
     * Chooses a look for this copy. The first time a look is picked here it starts from your shooting settings for
     * it (Art.loadState, read only); after that it keeps whatever you set while editing this photo.
     */
    private fun selectLook(m: ArtMode) {
        if (!Art.supported && m != ArtMode.OFF) { note("Looks need Android 13 or newer."); crop.postDelayed({ note(null) }, 2600); return }
        if (m == recipe.mode) return
        if (recipe.mode != ArtMode.OFF) lookValues[recipe.mode] = recipe.values
        val v = lookValues.getOrPut(m) { Art.loadState(prefs, m).values }
        recipe = recipe.copy(mode = m, values = v); artParam = 0
        if (m == ArtMode.HOLGA && ratio != "1:1") { ratio = "1:1"; applyRatio(reset = false) }   // Plastic is a square camera
        if (!recipe.follow) recipe = crop.pinTilt(recipe)    // the band where its Focus and Angle put it, as in the camera
        placeTiltOnGuide()
        crop.recipe = recipe; rememberArt(); applyArtUi(); syncFollow()
        lookTiles[m]?.let { lookScroller.reveal(it) }
        if (!focusHinted && m.follows) {
            focusHinted = true
            note("Tap the photo to choose what stays sharp"); crop.postDelayed({ note(null) }, 2600)
        }
    }

    private fun applyArtUi() {
        for ((m, t) in lookTiles) t.chosen = m == recipe.mode
        val mode = recipe.mode
        artParam = artParam.coerceIn(0, (mode.controls.size - 1).coerceAtLeast(0))
        lookControls.bind(mode, recipe.values, artParam)
    }

    /** Tilt Shift follows only line-friendly guides, as in the camera (Catalog.tiltGuides). */
    private val tiltFollows get() = recipe.mode == ArtMode.TILT_SHIFT && recipe.follow && crop.guide in Catalog.tiltGuides && GuideShape.hasAnchors(crop.guide)

    /** Follow guide on: Tilt Shift's Angle takes the guide's line where it has one, as MainActivity.placeTilt does. */
    private fun placeTiltOnGuide() {
        if (!tiltFollows) return
        val a = crop.guideAnchor() ?: return
        a.angle?.let { t0 ->
            var t = t0; while (t > 1.5707964f) t -= 3.1415927f; while (t < -1.5707964f) t += 3.1415927f
            recipe.values[3] = (t / 1.5707964f + 0.5f).coerceIn(0f, 1f)
        }
        recipe.values[1] = Focus.tiltFocusThrough(a.u, a.v, (recipe.values[3] - 0.5f) * 1.5707964f, crop.cw / crop.ch)
    }

    private fun onArtSlider(v: Float) {
        val slot = artSlot
        if (recipe.mode == ArtMode.TILT_SHIFT && slot == 1 && recipe.follow) {
            // moving Focus by hand takes the band off the guide, as in the camera
            recipe = recipe.copy(follow = false); syncFollow()
        }
        recipe.values[slot] = v
        if (recipe.mode == ArtMode.TILT_SHIFT && !recipe.follow && (slot == 1 || slot == 3)) {
            // Focus and Angle move the band across the crop; it stays pinned to the photo from there
            val m = Focus.tiltBand(Art.eff(recipe.mode, recipe.values), crop.cw / crop.ch)
            val ip = crop.cropToImage(m.cx, m.cy)
            recipe = recipe.copy(mask = recipe.mask.copy(cx = ip.x, cy = ip.y))
        }
        if (tiltFollows && slot == 3) placeTiltOnGuide()
        lookValues[recipe.mode] = recipe.values
        crop.recipe = recipe
        lookControls.setValue(v)
        if (recipe.mode.isFocusSlider(slot)) crop.flashMask()
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

    /** The camera's guide choices, read once as this photo's starting point. Editing never writes them back. */
    private fun cameraGuide(): Guide = runCatching { Guide.valueOf(prefs.getString("guide", Guide.THIRDS.name)!!) }.getOrDefault(Guide.THIRDS)

    private fun loadCameraGuide() {
        crop.guide = cameraGuide()
        crop.quadrant = prefs.getInt("quadrant", 0)
        crop.triFlip = prefs.getBoolean("triFlip", false)
        crop.anchor = prefs.getInt("anchor:${crop.guide.name}", 0)
        if (!recipeRestored) recipe = recipe.copy(follow = prefs.getBoolean("follow2", true))
    }
    private var recipeRestored = false

    private fun applyRatio(reset: Boolean) {
        val los = longOverShort(ratio)
        crop.aspect = if (portrait) 1f / los else los
        if (ratio == "PHI" && crop.guide != Guide.SPIRAL) { crop.guide = Guide.SPIRAL; crop.anchor = prefs.getInt("anchor:SPIRAL", 0); applyGuideUi() }   // φ is the spiral's own frame
        if (reset) crop.reset() else crop.refit()
        placeTiltOnGuide(); crop.recipe = recipe
        buildRows()
    }

    private fun buildRows() {
        // the camera's zoom pill: a Graphite pill of 44×27dp chips, Amber when chosen
        ratioRow.removeAllViews()
        val pillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)); background = pill(T.GRAPHITE, dp(16f)) }
        for ((key, lab) in listOf("PHI" to "φ", "ORIGINAL" to "Original", "4:5" to "4:5", "1:1" to "1:1", "3:2" to "3:2")) {
            val on = key == ratio
            pillRow.addView(text(lab, 12f, if (on) T.GRAPHITE else T.IVORY, if (on) T.semibold else T.medium).apply {
                gravity = Gravity.CENTER; background = if (on) pill(T.AMBER, dp(14f)) else null
                setPadding(dpi(10f), 0, dpi(10f), 0); minWidth = dpi(44f); contentDescription = "Crop ratio $lab"
                setOnClickListener { if (ratio != key) { ratio = key; applyRatio(reset = false); remember() } }
            }, LinearLayout.LayoutParams(wrap, dpi(27f)))
        }
        ratioRow.addView(pillRow, LinearLayout.LayoutParams(wrap, dpi(31f)))
        ratioRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        ratioRow.addView(text(if (portrait) "↕ Portrait" else "↔ Landscape", 11f, T.IVORY, T.medium, caps = true).apply {
            gravity = Gravity.CENTER; background = pill(T.GRAPHITE, dp(16f)); setPadding(dpi(12f), 0, dpi(12f), 0)
            contentDescription = if (portrait) "Portrait crop, switch to landscape" else "Landscape crop, switch to portrait"
            setOnClickListener { portrait = !portrait; applyRatio(reset = false); remember() }
        }, LinearLayout.LayoutParams(wrap, dpi(31f)))
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
        readL.text = "$w × $h px · " + (if (ratio == "PHI") "φ " else "") + ratioText()
        if (tiltFollows) { placeTiltOnGuide(); crop.recipe = recipe }
        else if (recipe.mode == ArtMode.TILT_SHIFT && crop.cw > 0f) {
            // the band stays on its spot of the photo; Focus shows where that now is in the crop
            val p = crop.imageToCrop(recipe.mask.cx, recipe.mask.cy)
            recipe.values[1] = Focus.tiltFocusThrough(p.x, p.y, (recipe.values[3] - 0.5f) * 1.5707964f, crop.cw / crop.ch)
        }
    }

    // ------------------------------------------------------------- remember per photo
    private fun key() = "crop:$src"

    /** This photo's crop and guide (v2 adds the guide, its variant and anchor). */
    private fun remember() {
        val c = crop
        prefs.edit().putString(key(), listOf(ratio, portrait, c.cx / c.bw, c.cy / c.bh, c.cw / c.bw, c.theta, c.quadrant, "v2", c.guide.name, c.triFlip, c.anchor).joinToString("|")).apply()
    }
    private fun rememberGuide() = remember()

    private fun restore(): Boolean {
        loadCameraGuide()
        val p = prefs.getString(key(), null)?.split("|")
        if (p == null) { ratio = if (crop.guide == Guide.SPIRAL) "PHI" else "ORIGINAL"; return false }
        return runCatching {
            ratio = p[0]; portrait = p[1].toBoolean()
            crop.quadrant = p[6].toInt()
            if (p.getOrNull(7) == "v2") {
                crop.guide = Guide.valueOf(p[8]); crop.triFlip = p[9].toBoolean(); crop.anchor = p[10].toInt()
            }
            val los = longOverShort(ratio); crop.aspect = if (portrait) 1f / los else los
            crop.cx = p[2].toFloat() * crop.bw; crop.cy = p[3].toFloat() * crop.bh; crop.cw = p[4].toFloat() * crop.bw
            crop.theta = p[5].toFloat(); dial.value = Math.toDegrees(crop.theta.toDouble()).toFloat()
            crop.refit(); placeTiltOnGuide(); crop.recipe = recipe; buildRows(); true
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
        // the copy is rendered on the cropped, straightened picture, so it uses the crop-space mask
        val r = recipe.copy(values = recipe.values.copyOf())
        val saveMask = crop.renderMask(recipe); val saveFh = crop.renderFactor(recipe)
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
                val out = EditRender.copy(full, cx, cy, cw, ch, deg, f, r, saveMask, saveFh)
                full.recycle()
                val ow = out.width; val oh = out.height
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
                copyExif(dest)
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

    /**
     * The original's capture details on the copy (date, camera, exposure, lens, place), so it sorts and reads
     * like the photo it came from. Orientation is reset to upright: the copy is written already turned.
     * Best effort: a photo without EXIF, or one that can't be read, just saves without it.
     */
    private fun copyExif(dest: Uri) = runCatching {
        val tags = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF, ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP)
        val from = contentResolver.openInputStream(src)?.use { ExifInterface(it) } ?: return@runCatching
        val vals = tags.mapNotNull { t -> from.getAttribute(t)?.let { t to it } }
        if (vals.isEmpty()) return@runCatching
        contentResolver.openFileDescriptor(dest, "rw")?.use { pfd ->
            val to = ExifInterface(pfd.fileDescriptor)
            for ((t, v) in vals) to.setAttribute(t, v)
            to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            to.setAttribute(ExifInterface.TAG_SOFTWARE, "Crooked Light · Edit a copy")
            to.saveAttributes()
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
