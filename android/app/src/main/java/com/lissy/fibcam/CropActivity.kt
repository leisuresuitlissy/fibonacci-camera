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
class CropActivity : ComponentActivity() {

    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private val prefs by lazy { getSharedPreferences("fibcam", Context.MODE_PRIVATE) }
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var src: Uri
    private lateinit var crop: CropView
    private lateinit var readL: TextView
    private lateinit var readR: TextView
    private lateinit var dial: DialView
    private lateinit var noteView: TextView
    private lateinit var tabRow: LinearLayout
    // selector row (the camera's 65dp tile row) and the contextual area under it, one pair per tab
    private lateinit var composeScroller: FadeScroller
    private lateinit var lookScroller: FadeScroller
    private lateinit var ratioScroller: FadeScroller
    private lateinit var ratioRow: LinearLayout
    private lateinit var doubleRow: LinearLayout
    private lateinit var composeSub: LinearLayout
    private lateinit var followBtn: FollowToggle
    private lateinit var lookControls: LookControls
    private lateinit var cropCtx: LinearLayout
    private lateinit var doubleCtx: LinearLayout
    private lateinit var blendNames: LinearLayout
    private lateinit var blendSlider: ValueSlider
    private lateinit var blendValue: TextView
    private lateinit var blendHint: TextView
    private lateinit var ctxArea: FrameLayout
    private val composeTiles = HashMap<Guide, TileView>()
    private val lookTiles = HashMap<ArtMode, TileView>()
    private var secondUri: Uri? = null
    private var tab = "CROP"
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
        setContentView(buildUi())
        // Back steps out one level at a time: a Look's setting → its first; any other tab → Crop; Crop → camera
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    tab == "ART" && recipe.mode != ArtMode.OFF && artParam != 0 -> { artParam = 0; applyArtUi() }
                    tab != "CROP" -> { tab = "CROP"; applyTab() }
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
                        intent.getStringExtra("editTab")?.let { t -> tab = when (t) { "LOOK" -> "ART"; "DOUBLE" -> "BLEND"; else -> t } }
                    }
                    applyTab(); applyGuideUi(); applyArtUi(); applyBlendUi(); note(null); restoreSecond()
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

    /** The camera's header pill (Coach style): Ivory caps on Graphite, 34dp. */
    private fun headerPill(t: String, click: () -> Unit) = text(t, 11f, T.IVORY, T.medium, caps = true).apply {
        gravity = Gravity.CENTER; background = pill(T.GRAPHITE, dp(17f)); setPadding(dpi(14f), 0, dpi(14f), 0)
        setOnClickListener { click() }
    }
    /** An outlined action pill, like the Look controls' Reset all: Ivory outline on Carbon, 26dp inside a 44dp target. */
    private fun outlinePill(t: String, click: () -> Unit) = text(t, 11f, T.IVORY, T.medium, caps = true).apply {
        gravity = Gravity.CENTER
        background = android.graphics.drawable.InsetDrawable(pill(T.CARBON, dp(13f), T.IVORY, dpi(1f).coerceAtLeast(1)), 0, dpi(9f), 0, dpi(9f))
        setPadding(dpi(12f), 0, dpi(12f), 0); setOnClickListener { click() }
    }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(T.CARBON) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars()); v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }
        // header: back to the camera, what this screen is, another photo
        val top = FrameLayout(this).apply { setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(10f)) }
        top.addView(headerPill("‹ Camera") { finish() }, FrameLayout.LayoutParams(wrap, dpi(34f), Gravity.START or Gravity.CENTER_VERTICAL))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        t.addView(text("Edit a copy", 15f, T.IVORY, T.semibold))
        t.addView(text("Original is never changed", 11f, T.SUBTLE, T.medium).apply { setPadding(0, dpi(3f), 0, 0) })
        top.addView(t, FrameLayout.LayoutParams(wrap, wrap, Gravity.CENTER))
        top.addView(headerPill("Other photo") { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            FrameLayout.LayoutParams(wrap, dpi(34f), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(top, LinearLayout.LayoutParams(match, dpi(58f)))

        val stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
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
        stage.addView(crop, FrameLayout.LayoutParams(match, match))
        noteView = text("", 12f, T.AMBER, T.medium).apply {
            background = pill(T.alpha(T.CARBON, 0.9f), dp(16f), T.alpha(T.AMBER, 0.8f)); setPadding(dpi(14f), dpi(9f), dpi(14f), dpi(9f)); visibility = View.GONE
        }
        stage.addView(noteView, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(16f) })
        root.addView(stage, LinearLayout.LayoutParams(match, 0, 1f))

        // the crop's size and ratio
        val read = row().apply { setPadding(dpi(16f), 0, dpi(16f), 0) }
        readL = text("", 12f, T.SUBTLE, T.medium)
        read.addView(readL, LinearLayout.LayoutParams(0, wrap, 1f))
        readR = text("", 12f, T.AMBER, T.medium).apply { gravity = Gravity.CENTER; background = pill(T.GRAPHITE, dp(12f)); setPadding(dpi(10f), 0, dpi(10f), 0) }
        read.addView(readR, LinearLayout.LayoutParams(wrap, dpi(24f)))
        root.addView(read, LinearLayout.LayoutParams(match, dpi(40f)))

        // tabs: the camera's pill, with the editor's own Crop and Double alongside Compose and Look
        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f)); background = pill(T.GRAPHITE, dp(16f)) }
        for ((key, name) in listOf("CROP" to "Crop", "COMPOSE" to "Compose", "ART" to "Look", "BLEND" to "Double")) {
            tabRow.addView(text(name, 13f, T.IVORY, T.medium).apply {
                gravity = Gravity.CENTER; tag = key
                setOnClickListener { if (tab != key) { tab = key; applyTab() } }
            }, LinearLayout.LayoutParams(0, match, 1f).apply { if (key != "CROP") leftMargin = dpi(8f) })
        }
        root.addView(tabRow, LinearLayout.LayoutParams(match, dpi(31f)).apply { leftMargin = dpi(16f); rightMargin = dpi(16f) })

        // selector row: guides, looks, ratios, or the second photo's actions
        val selector = FrameLayout(this)
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
        selector.addView(composeScroller, FrameLayout.LayoutParams(match, match))
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
        selector.addView(lookScroller, FrameLayout.LayoutParams(match, match))
        ratioScroller = FadeScroller(this, dp(24f))
        ratioRow = row().apply { setPadding(dpi(16f), 0, dpi(16f), 0) }
        ratioScroller.addView(ratioRow, FrameLayout.LayoutParams(wrap, match))
        selector.addView(ratioScroller, FrameLayout.LayoutParams(match, match))
        doubleRow = row().apply { setPadding(dpi(16f), 0, dpi(16f), 0) }
        selector.addView(doubleRow, FrameLayout.LayoutParams(match, match))
        root.addView(selector, LinearLayout.LayoutParams(match, dpi(65f)).apply { topMargin = dpi(10f) })

        // contextual area
        ctxArea = FrameLayout(this)
        composeSub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctxArea.addView(composeSub, FrameLayout.LayoutParams(wrap, wrap))
        followBtn = FollowToggle(this).apply {
            setOnClickListener { toggleFollow() }
        }
        ctxArea.addView(followBtn, FrameLayout.LayoutParams(wrap, dpi(44f), Gravity.END or Gravity.TOP).apply { topMargin = -dpi(6f) })
        lookControls = LookControls(this).apply {
            onSelect = { i -> artParam = i; applyArtUi(); if (recipe.mode.isFocusSlider(artSlot)) crop.flashMask() }
            onChoice = { i ->
                recipe.values[5] = i.toFloat()
                if (recipe.mode.isRandom(i)) recipe = recipe.copy(seed = (Math.random() * 1000).toFloat())
                crop.recipe = recipe; rememberArt(); applyArtUi()
            }
            onReset = {
                // this photo's look back to the look's starting settings; your shooting settings are untouched
                recipe = recipe.copy(values = recipe.mode.defaults, follow = false); lookValues[recipe.mode] = recipe.values
                crop.recipe = recipe; rememberArt(); applyArtUi(); syncFollow()
            }
            slider.onChange = { v -> onArtSlider(v) }
        }
        ctxArea.addView(lookControls, FrameLayout.LayoutParams(match, wrap))
        cropCtx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        cropCtx.addView(text("Straighten", 11f, T.IVORY, T.medium, caps = true))
        dial = DialView(this).apply { onChange = { v -> crop.theta = Math.toRadians(v.toDouble()).toFloat(); crop.refit() } }
        cropCtx.addView(dial, LinearLayout.LayoutParams(match, dpi(58f)))
        ctxArea.addView(cropCtx, FrameLayout.LayoutParams(match, wrap))
        doubleCtx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blendHint = text("Lay another photo over this one, then crop and style the pair.", 12f, T.SUBTLE, T.medium).apply { maxLines = 2 }
        doubleCtx.addView(blendHint, LinearLayout.LayoutParams(match, wrap))
        val bn = FadeScroller(this, dp(24f))
        blendNames = row(); bn.addView(blendNames, FrameLayout.LayoutParams(wrap, match))
        doubleCtx.addView(bn, LinearLayout.LayoutParams(match, dpi(32f)))
        val bs = row()
        blendSlider = ValueSlider(this).apply {
            onChange = { v -> crop.strength = v; blendValue.text = "${(v * 100).roundToInt()}"; rememberSecond() }
        }
        bs.addView(blendSlider, LinearLayout.LayoutParams(0, dpi(18f), 1f))
        blendValue = text("60", 12f, T.AMBER, T.medium, 0.08f).apply { gravity = Gravity.END }
        bs.addView(blendValue, LinearLayout.LayoutParams(dpi(30f), wrap).apply { leftMargin = dpi(10f) })
        doubleCtx.addView(bs, LinearLayout.LayoutParams(match, dpi(18f)).apply { topMargin = dpi(6f) })
        ctxArea.addView(doubleCtx, FrameLayout.LayoutParams(match, wrap))
        root.addView(ctxArea, LinearLayout.LayoutParams(match, dpi(84f)).apply { leftMargin = dpi(16f); rightMargin = dpi(16f); topMargin = dpi(10f) })
        lookControls.available = dpi(84f)

        // two equal buttons
        val bar = row().apply { setPadding(dpi(16f), dpi(4f), dpi(16f), dpi(12f)) }
        bar.addView(text("Reset", 14f, T.IVORY, T.medium).apply {
            gravity = Gravity.CENTER; background = pill(T.GRAPHITE, dp(24f)); contentDescription = "Reset the crop and straightening"
            setOnClickListener { dial.value = 0f; crop.theta = 0f; applyRatio(reset = true) }
        }, LinearLayout.LayoutParams(0, dpi(48f), 1f))
        bar.addView(text("Save a copy", 14f, T.GRAPHITE, T.semibold).apply {
            gravity = Gravity.CENTER; background = pill(T.AMBER, dp(24f))
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(0, dpi(48f), 1f).apply { leftMargin = dpi(10f) })
        root.addView(bar, LinearLayout.LayoutParams(match, wrap))
        return root
    }

    private fun applyTab() {
        for (i in 0 until tabRow.childCount) {
            val v = tabRow.getChildAt(i) as TextView; val on = v.tag == tab
            v.setTextColor(if (on) T.GRAPHITE else T.IVORY)
            v.typeface = if (on) T.semibold else T.medium
            v.background = if (on) pill(T.AMBER, dp(24f)) else null
        }
        val c = tab == "COMPOSE"; val a = tab == "ART"; val r = tab == "CROP"; val b = tab == "BLEND"
        composeScroller.visibility = if (c) View.VISIBLE else View.GONE
        lookScroller.visibility = if (a) View.VISIBLE else View.GONE
        ratioScroller.visibility = if (r) View.VISIBLE else View.GONE
        doubleRow.visibility = if (b) View.VISIBLE else View.GONE
        composeSub.visibility = if (c) View.VISIBLE else View.GONE
        followBtn.visibility = if (c) View.VISIBLE else View.GONE
        lookControls.visibility = if (a) View.VISIBLE else View.GONE
        cropCtx.visibility = if (r) View.VISIBLE else View.GONE
        doubleCtx.visibility = if (b) View.VISIBLE else View.GONE
        if (c) composeTiles[crop.guide]?.let { composeScroller.reveal(it, smooth = false) }
        if (a) lookTiles[recipe.mode]?.let { lookScroller.reveal(it, smooth = false) }
        crop.editingSecond = b && secondUri != null   // in Double, your fingers move the second photo; elsewhere, the crop
        crop.focusMode = a                            // in Look, a tap picks what stays sharp
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

    /** Follow guide, as in the camera: the look's sharp area goes to the guide's focal point. Dimmed for guides without one. */
    private fun syncFollow() {
        val available = GuideShape.hasAnchors(crop.guide)
        followBtn.set(recipe.follow, available)
        crop.selectedAnchor = if (recipe.follow && available && recipe.mode != ArtMode.OFF) crop.anchor else -1
        crop.invalidate()
    }

    private fun toggleFollow() {
        // switching off keeps the region where the guide had put it, now pinned to the photo
        val m = crop.cropMask(recipe)
        val ip = crop.cropToImage(m.cx, m.cy)
        recipe = recipe.copy(follow = !recipe.follow, mask = recipe.mask.copy(cx = ip.x, cy = ip.y))
        if (!recipe.follow && recipe.mode == ArtMode.TILT_SHIFT && (m.type == 1 || m.type == 3)) { recipe.values[1] = m.cy.coerceIn(0f, 1f); recipe.values[3] = (m.angle / 1.5707964f + 0.5f).coerceIn(0f, 1f) }
        placeTiltOnGuide()
        crop.recipe = recipe; crop.flashMask(); rememberArt(); syncFollow(); applyArtUi()
    }

    // ------------------------------------------------------------- double exposure
    private fun applyBlendUi() {
        doubleRow.removeAllViews(); blendNames.removeAllViews()
        val add = { pickSecond.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        if (secondUri == null) {
            doubleRow.addView(outlinePill("+ Add second photo") { add() }, LinearLayout.LayoutParams(wrap, dpi(44f)))
            blendHint.visibility = View.VISIBLE
            (blendNames.parent as View).visibility = View.GONE
            (blendSlider.parent as View).visibility = View.GONE
            applyTab(); return
        }
        doubleRow.addView(outlinePill("Replace") { add() }, LinearLayout.LayoutParams(wrap, dpi(44f)))
        doubleRow.addView(outlinePill("Fit") { crop.resetSecond() }, LinearLayout.LayoutParams(wrap, dpi(44f)).apply { leftMargin = dpi(8f) })
        doubleRow.addView(outlinePill("Remove") { removeSecond() }, LinearLayout.LayoutParams(wrap, dpi(44f)).apply { leftMargin = dpi(8f) })
        doubleRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        doubleRow.addView(text("Drag · pinch · twist", 11f, T.SUBTLE, T.medium))
        // the blend names: the Look controls' name row (Ivory, Amber when chosen)
        Blend.values().forEachIndexed { i, bl ->
            val on = bl == crop.blend
            blendNames.addView(text(bl.label, 11f, if (on) T.AMBER else T.IVORY, if (on) T.semibold else T.medium, caps = true).apply {
                gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, dpi(16f), 0)
                setOnClickListener { crop.blend = bl; rememberSecond(); applyBlendUi() }
            }, LinearLayout.LayoutParams(wrap, match))
        }
        blendHint.visibility = View.GONE
        (blendNames.parent as View).visibility = View.VISIBLE
        (blendSlider.parent as View).visibility = View.VISIBLE
        blendSlider.value = crop.strength
        blendValue.text = "${(crop.strength * 100).roundToInt()}"
        applyTab()
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
                rememberSecond(); applyBlendUi(); note(null)
            }
        }
    }

    private fun removeSecond() {
        secondUri = null; crop.second = null
        prefs.edit().remove("dbl:$src").apply()
        applyBlendUi()
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
        ratioRow.addView(text(if (portrait) "↕  Portrait" else "↔  Landscape", 11f, T.IVORY, T.medium, caps = true).apply {
            gravity = Gravity.CENTER; background = pill(T.GRAPHITE, dp(16f)); setPadding(dpi(12f), 0, dpi(12f), 0)
            contentDescription = if (portrait) "Portrait crop, switch to landscape" else "Landscape crop, switch to portrait"
            setOnClickListener { portrait = !portrait; applyRatio(reset = false); remember() }
        }, LinearLayout.LayoutParams(wrap, dpi(31f)).apply { leftMargin = dpi(10f) })
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
        readL.text = "$w × $h px"
        readR.text = (if (ratio == "PHI") "φ  " else "") + ratioText()
        if (tiltFollows) { placeTiltOnGuide(); crop.recipe = recipe }
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
        val saveMask = crop.cropMask(recipe)
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
                    val styled = Art.render(out, r, saveMask)         // the look, measured against the crop
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
