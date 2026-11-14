package com.lissy.fibcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : ComponentActivity(), OverlayView.Listener, SensorEventListener {

    // ---- palette
    private val cBase = 0xFF0B0B0C.toInt()
    private val cElev = 0xFF1F1F25.toInt()
    private val cLine = 0xFF2E2E36.toInt()
    private val cText = 0xFFF5F3EF.toInt()
    private val cMuted = 0xFFA3A09A.toInt()
    private val cGold = 0xFFE0B252.toInt()
    private val mono: Typeface = Typeface.MONOSPACE
    private val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    // ---- views
    private lateinit var stage: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hud: FrameLayout
    private lateinit var pill: TextView
    private lateinit var nice: TextView
    private lateinit var sourceTxt: TextView
    private lateinit var coachBtn: IconButton
    private lateinit var flashBtn: IconButton
    private lateinit var updateBtn: TextView
    private lateinit var thumb: ImageView
    private lateinit var gateMsg: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var composeLayer: LinearLayout
    private lateinit var artLayer: LinearLayout
    private lateinit var exposureLayer: LinearLayout
    private lateinit var guideRow: LinearLayout
    private lateinit var secondRow: LinearLayout
    private lateinit var artRow: LinearLayout
    private lateinit var paramRow: LinearLayout
    private lateinit var artSlider: SeekBar
    private lateinit var evValue: TextView
    private lateinit var evSlider: SeekBar
    private lateinit var zoomRow: LinearLayout
    private lateinit var camLayer: FrameLayout
    private lateinit var ghost: ImageView
    private lateinit var exposureTag: TextView
    private lateinit var doubleBtn: IconButton
    private lateinit var blendLayer: LinearLayout
    private lateinit var blendRow: LinearLayout
    private lateinit var blendValue: TextView
    private lateinit var blendSlider: SeekBar

    // ---- state
    private val prefs by lazy { getSharedPreferences("fibcam", Context.MODE_PRIVATE) }
    private var guide = Guide.SPIRAL
    private var quadrant = 0
    private var triFlip = false
    private val align = 1                 // the spiral is always centred
    private var coach = false
    private var front = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF
    private var layer = "COMPOSE"         // COMPOSE, ART, EXPOSURE — switching never clears the others
    private var art = ArtMode.OFF
    private var artValues = FloatArray(5)
    private var artParam = 0
    private var liveSeed = (Math.random() * 1000).toFloat()
    private var liveShader: RuntimeShader? = null
    // double exposure
    private var double = false
    private var firstUri: Uri? = null
    private var ghostBmp: Bitmap? = null
    private var blend = Blend.SCREEN
    private var blendStrength = 0.6f
    private var evIndex = 0
    private var evMin = 0
    private var evMax = 0
    private var evStep = 1f

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracker = Tracker()
    private var trackState: Tracker.State? = null
    private var aligned = false
    private var hintUntil = 0L
    private var lastUri: Uri? = null

    private var zoomRatio = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var chipsFor = ""
    /** Zoom ratios where the phone has a real lens (or a full-quality sensor crop), measured from the hardware. */
    private var lensStops: List<Float>? = null

    private var level: Float? = null
    private var deviceRot = 0
    private lateinit var sensors: SensorManager
    private lateinit var orientation: OrientationEventListener

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openCrop(uri)
    }

    private val pickFirst = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setFirst(uri)
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else gate("Camera access was refused. Allow it in Settings → Apps → Fibonacci → Permissions.")
    }

    // =========================================================== lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guide = runCatching { Guide.valueOf(prefs.getString("guide", Guide.SPIRAL.name)!!) }.getOrDefault(Guide.SPIRAL)
        quadrant = prefs.getInt("quadrant", 0)
        triFlip = prefs.getBoolean("triFlip", false)
        coach = prefs.getBoolean("coach", false)
        // A fresh launch always opens on Compose with no look. Only a restore (Android rebuilt the screen
        // behind your back) brings back the tab and look you had. Slider positions per look are kept either way.
        val restoring = savedInstanceState != null
        layer = if (restoring) prefs.getString("layer", "COMPOSE") ?: "COMPOSE" else "COMPOSE"
        art = if (restoring) runCatching { ArtMode.valueOf(prefs.getString("art", ArtMode.OFF.name)!!) }.getOrDefault(ArtMode.OFF) else ArtMode.OFF
        if (!Art.supported) art = ArtMode.OFF
        artValues = Art.loadValues(prefs, art)
        lastUri = prefs.getString("lastUri", null)?.let { Uri.parse(it) }
        double = prefs.getBoolean("double", false)
        firstUri = prefs.getString("firstUri", null)?.let { Uri.parse(it) }
        blend = runCatching { Blend.valueOf(prefs.getString("blend", Blend.SCREEN.name)!!) }.getOrDefault(Blend.SCREEN)
        blendStrength = prefs.getFloat("blendStrength", 0.6f)
        if (layer == "BLEND" && !double) layer = "COMPOSE"
        setContentView(buildUi())
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceRot = when (o) { in 45..134 -> 90; in 135..224 -> 180; in 225..314 -> 270; else -> 0 }
                imageCapture?.targetRotation = when (deviceRot) { 90 -> Surface.ROTATION_270; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            }
        }
        overlay.squareGuide = art == ArtMode.HOLGA
        applyGuide(); applyArtUi(); applyBlendUi(); applyLayer(); syncCoachBtn(); syncFlashBtn(); syncDoubleBtn()
        firstUri?.let { if (double) loadFirst(it) }
        loadThumb()
        checkForUpdate()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_UI)
        if (orientation.canDetectOrientation()) orientation.enable()
        loadThumb()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        orientation.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown(); saveExecutor.shutdown()
    }

    private fun save() {
        prefs.edit().putString("guide", guide.name).putInt("quadrant", quadrant)
            .putBoolean("triFlip", triFlip).putBoolean("coach", coach)
            .putString("layer", layer).putString("art", art.name)
            .putBoolean("double", double).putString("firstUri", firstUri?.toString())
            .putString("blend", blend.name).putFloat("blendStrength", blendStrength).apply()
    }

    // =========================================================== UI helpers
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun rounded(fill: Int, stroke: Int? = null, radius: Float = 999f, strokeW: Float = 1f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius); if (stroke != null) setStroke(dpi(strokeW).coerceAtLeast(1), stroke)
    }
    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    private fun label(text: String, size: Float, color: Int, face: Typeface = Typeface.DEFAULT, spacing: Float = 0f) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face; letterSpacing = spacing
        includeFontPadding = false
    }

    private fun chip(text: String, on: Boolean, onClick: () -> Unit) = label(text, 12f, if (on) cGold else cMuted, medium, 0.04f).apply {
        background = if (on) rounded(withAlpha(cGold, 0.18f), withAlpha(cGold, 0.85f)) else rounded(cElev)
        setPadding(dpi(12f), dpi(8f), dpi(12f), dpi(8f))
        setOnClickListener { onClick() }
    }

    private fun iconButton(icon: IconButton.Icon, size: Float, onClick: () -> Unit) = IconButton(this, icon, cMuted).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cElev) }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size))
    }

    private fun slider(onMove: (Int) -> Unit) = SeekBar(this).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(cGold)
        progressBackgroundTintList = ColorStateList.valueOf(cLine)
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) onMove(v) }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private fun hrow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }

    // =========================================================== layout
    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBase) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        // top bar
        val top = hrow().apply { setPadding(dpi(16f), dpi(8f), dpi(12f), dpi(8f)) }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Fibonacci Camera", 15f, cText, Typeface.DEFAULT_BOLD))
        sourceTxt = label("LIVE · REAR", 10f, cMuted, mono, 0.1f).apply { setPadding(0, dpi(3f), 0, 0) }
        titles.addView(sourceTxt)
        top.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))
        updateBtn = chip("UPDATE", true) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/leisuresuitlissy/fibonacci-camera/releases/download/android-latest/fibonacci-camera.apk")))
        }.apply { visibility = View.GONE }
        top.addView(updateBtn, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        coachBtn = iconButton(IconButton.Icon.COACH, 40f) {
            coach = !coach
            if (coach) hintUntil = SystemClock.uptimeMillis() + 4000
            save(); syncCoachBtn(); updateHud(); overlay.invalidate()
        }.apply { contentDescription = "Coaching" }
        doubleBtn = iconButton(IconButton.Icon.DOUBLE, 40f) { toggleDouble() }.apply { contentDescription = "Double exposure" }
        top.addView(doubleBtn, (doubleBtn.layoutParams as LinearLayout.LayoutParams).apply { rightMargin = dpi(8f) })
        top.addView(coachBtn)
        flashBtn = iconButton(IconButton.Icon.FLASH_OFF, 40f) { cycleFlash() }.apply { contentDescription = "Flash" }
        top.addView(flashBtn, (flashBtn.layoutParams as LinearLayout.LayoutParams).apply { leftMargin = dpi(8f) })
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        // viewfinder
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // a TextureView, so art modes can be applied live
        }
        camLayer = FrameLayout(this)
        camLayer.addView(preview, lp(match, match))
        ghost = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; visibility = View.GONE }
        camLayer.addView(ghost, lp(0, 0))
        stage.addView(camLayer, lp(match, match))
        overlay = OverlayView(this).also { it.listener = this }
        stage.addView(overlay, lp(match, match))
        hud = FrameLayout(this)
        stage.addView(hud, lp(0, 0))
        pill = label("", 14f, cText, medium).apply { setPadding(dpi(16f), dpi(9f), dpi(16f), dpi(9f)); visibility = View.GONE }
        hud.addView(pill, lp(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(12f) })
        nice = label("Nice", 13f, cGold, medium, 0.04f).apply {
            setPadding(dpi(14f), dpi(7f), dpi(14f), dpi(7f)); visibility = View.GONE
            background = rounded(0xD93C2E14.toInt(), withAlpha(cGold, 0.85f))
        }
        hud.addView(nice, lp(wrap, wrap, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(12f) })
        exposureTag = label("", 10.5f, cGold, mono, 0.1f).apply {
            setPadding(dpi(10f), dpi(6f), dpi(10f), dpi(6f)); visibility = View.GONE
            background = rounded(withAlpha(cBase, 0.7f), withAlpha(cGold, 0.6f))
            setOnClickListener { discardFirst() }
        }
        hud.addView(exposureTag, lp(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dpi(12f); bottomMargin = dpi(12f) })
        gateMsg = label("", 14f, cMuted).apply { gravity = Gravity.CENTER; setPadding(dpi(32f), 0, dpi(32f), 0); visibility = View.GONE }
        stage.addView(gateMsg, lp(match, match))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFrame() }
        root.addView(stage, LinearLayout.LayoutParams(match, 0, 1f))

        // layer switch
        tabRow = hrow().apply { setPadding(dpi(16f), dpi(10f), dpi(16f), dpi(4f)) }
        root.addView(tabRow, LinearLayout.LayoutParams(match, wrap))

        // the three layers share one space
        val layers = FrameLayout(this)
        composeLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val gscroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        guideRow = hrow(); gscroll.addView(guideRow)
        composeLayer.addView(gscroll, LinearLayout.LayoutParams(match, dpi(52f)))
        secondRow = hrow(); composeLayer.addView(secondRow, LinearLayout.LayoutParams(match, dpi(52f)))
        layers.addView(composeLayer, lp(match, match))

        artLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val ascroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        artRow = hrow(); ascroll.addView(artRow)
        artLayer.addView(ascroll, LinearLayout.LayoutParams(match, dpi(48f)))
        paramRow = hrow(); artLayer.addView(paramRow, LinearLayout.LayoutParams(match, dpi(28f)))
        artSlider = slider { v -> onArtSlider(v / 100f) }
        artLayer.addView(artSlider, LinearLayout.LayoutParams(match, dpi(28f)).apply { leftMargin = dpi(4f); rightMargin = dpi(4f) })
        layers.addView(artLayer, lp(match, match))

        exposureLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val evHead = hrow()
        evHead.addView(label("EXPOSURE", 10f, cMuted, mono, 0.13f)); evHead.addView(spacer())
        evValue = label("0.0 EV", 13f, cGold, mono)
        evHead.addView(evValue)
        exposureLayer.addView(evHead, LinearLayout.LayoutParams(match, dpi(34f)))
        evSlider = slider { v -> onEvSlider(v) }
        exposureLayer.addView(evSlider, LinearLayout.LayoutParams(match, dpi(36f)).apply { leftMargin = dpi(4f); rightMargin = dpi(4f) })
        val evFoot = hrow()
        evFoot.addView(label("−2", 11f, cMuted, mono)); evFoot.addView(spacer())
        evFoot.addView(label("Reset", 12f, cText, medium).apply { setPadding(dpi(12f), dpi(6f), dpi(12f), dpi(6f)); setOnClickListener { setEv(0) } })
        evFoot.addView(spacer()); evFoot.addView(label("+2", 11f, cMuted, mono))
        exposureLayer.addView(evFoot, LinearLayout.LayoutParams(match, dpi(34f)))
        layers.addView(exposureLayer, lp(match, match))

        blendLayer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        blendRow = hrow(); blendLayer.addView(blendRow, LinearLayout.LayoutParams(match, dpi(48f)))
        val bHead = hrow()
        bHead.addView(label("STRENGTH", 10f, cMuted, mono, 0.13f)); bHead.addView(spacer())
        blendValue = label("60%", 12f, cGold, mono); bHead.addView(blendValue)
        blendLayer.addView(bHead, LinearLayout.LayoutParams(match, dpi(24f)))
        blendSlider = slider { v -> blendStrength = v / 100f; blendValue.text = "$v%"; applyGhost(); save() }
        blendLayer.addView(blendSlider, LinearLayout.LayoutParams(match, dpi(32f)).apply { leftMargin = dpi(4f); rightMargin = dpi(4f) })
        layers.addView(blendLayer, lp(match, match))
        root.addView(layers, LinearLayout.LayoutParams(match, dpi(104f)))

        // zoom, then the shutter bar
        zoomRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        root.addView(zoomRow, LinearLayout.LayoutParams(match, dpi(44f)))

        val bar = FrameLayout(this)
        thumb = ImageView(this).apply {
            background = rounded(cElev, cLine, 12f); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f)) } }
            clipToOutline = true
            contentDescription = "Edit the last photo"
            setOnClickListener { val u = lastUri; if (u != null) openCrop(u) else pickFromLibrary() }
            setOnLongClickListener { pickFromLibrary(); true }
        }
        bar.addView(thumb, lp(dpi(54f), dpi(54f), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = dpi(24f) })
        val shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dpi(3f), withAlpha(Color.WHITE, 0.95f)) }
            val inner = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) } }
            addView(inner, lp(match, match).apply { setMargins(dpi(7f), dpi(7f), dpi(7f), dpi(7f)) })
            setOnClickListener { capture(this) }
            contentDescription = "Take photo"
        }
        bar.addView(shutter, lp(dpi(78f), dpi(78f), Gravity.CENTER))
        val importBtn = IconButton(this, IconButton.Icon.PHOTO, cText).apply {
            glyphFraction = 0.5f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cElev); setStroke(dpi(1f), cLine) }
            contentDescription = "Import a photo"
            translationX = -dp(84f)
            setOnClickListener { if (double) pickFirst.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else pickFromLibrary() }
        }
        bar.addView(importBtn, lp(dpi(48f), dpi(48f), Gravity.CENTER))
        val flip = IconButton(this, IconButton.Icon.FLIP, cText).apply {
            glyphFraction = 0.56f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cElev); setStroke(dpi(1f), cLine) }
            contentDescription = "Switch camera"
            setOnClickListener { front = !front; tracker.clear(); trackState = null; this@MainActivity.overlay.target = null; bindCamera() }
        }
        bar.addView(flip, lp(dpi(60f), dpi(60f), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dpi(22f) })
        root.addView(bar, LinearLayout.LayoutParams(match, dpi(104f)))
        return root
    }

    /** The camera feed is 4:3, letterboxed exactly like the preview's FIT_CENTER. */
    private fun layoutFrame() {
        val w = stage.width.toFloat(); val h = stage.height.toFloat()
        if (w <= 0f || h <= 0f) return
        val aspect = 3f / 4f
        val fw: Float; val fh: Float
        if (w / h > aspect) { fh = h; fw = h * aspect } else { fw = w; fh = w / aspect }
        val l = (w - fw) / 2; val t = (h - fh) / 2
        if (overlay.frame.left == l && overlay.frame.top == t && overlay.frame.width() == fw && overlay.frame.height() == fh) return
        overlay.frame.set(l, t, l + fw, t + fh)
        (hud.layoutParams as FrameLayout.LayoutParams).apply { width = fw.roundToInt(); height = fh.roundToInt(); leftMargin = l.roundToInt(); topMargin = t.roundToInt() }
        (ghost.layoutParams as FrameLayout.LayoutParams).apply { width = fw.roundToInt(); height = fh.roundToInt(); leftMargin = l.roundToInt(); topMargin = t.roundToInt() }
        hud.post { hud.requestLayout(); ghost.requestLayout() }
        overlay.invalidate()
        applyLiveArt()
    }

    // =========================================================== layers
    private fun applyLayer() {
        tabRow.removeAllViews()
        val evText = if (evIndex == 0) "" else " · " + String.format(Locale.US, "%+.1f", evIndex * evStep)
        val tabs = mutableListOf("COMPOSE" to "COMPOSE", "ART" to ("ART" + if (art != ArtMode.OFF) " · " + art.label else ""), "EXPOSURE" to ("EXPOSURE$evText"))
        if (double) tabs.add("BLEND" to "BLEND")
        for ((key, text) in tabs) {
            val on = key == layer
            val t = label(text, 11f, if (on) cGold else cMuted, medium, 0.06f).apply {
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                background = if (on) rounded(withAlpha(cGold, 0.14f), withAlpha(cGold, 0.7f), 10f) else rounded(Color.TRANSPARENT, cLine, 10f)
                setPadding(dpi(6f), dpi(8f), dpi(6f), dpi(8f))
                setOnClickListener { layer = key; save(); applyLayer() }
            }
            tabRow.addView(t, LinearLayout.LayoutParams(0, wrap, if (key == "COMPOSE") 0.9f else 1.2f).apply { if (key != "COMPOSE") leftMargin = dpi(6f) })
        }
        composeLayer.visibility = if (layer == "COMPOSE") View.VISIBLE else View.GONE
        artLayer.visibility = if (layer == "ART") View.VISIBLE else View.GONE
        exposureLayer.visibility = if (layer == "EXPOSURE") View.VISIBLE else View.GONE
        blendLayer.visibility = if (layer == "BLEND") View.VISIBLE else View.GONE
    }

    // ---- double exposure
    private fun syncDoubleBtn() {
        doubleBtn.tint = if (double) cGold else cMuted
        (doubleBtn.background as GradientDrawable).setColor(if (double) withAlpha(cGold, 0.18f) else cElev)
        doubleBtn.invalidate()
        exposureTag.visibility = if (double) View.VISIBLE else View.GONE
        exposureTag.text = if (firstUri == null) "EXPOSURE 1 OF 2" else "EXPOSURE 2 OF 2  ✕"
    }

    private fun toggleDouble() {
        double = !double
        if (double) { layer = "BLEND"; firstUri?.let { loadFirst(it) } }
        else { if (layer == "BLEND") layer = "COMPOSE"; ghostBmp = null }
        save(); syncDoubleBtn(); applyGhost(); applyLayer()
    }

    private fun applyBlendUi() {
        blendRow.removeAllViews()
        for (b in Blend.values()) {
            blendRow.addView(chip(b.label.uppercase(Locale.US), b == blend) { blend = b; save(); applyBlendUi(); applyGhost() },
                LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        blendSlider.progress = (blendStrength * 100).roundToInt()
        blendValue.text = "${(blendStrength * 100).roundToInt()}%"
    }

    /** The first exposure floats over the live picture with the chosen blend, so you compose the pair. */
    private fun applyGhost() {
        val b = ghostBmp
        if (!double || b == null) { ghost.visibility = View.GONE; ghost.setImageDrawable(null); return }
        ghost.setImageBitmap(b)
        ghost.setLayerType(View.LAYER_TYPE_HARDWARE, blend.paint(blendStrength))
        ghost.visibility = View.VISIBLE
    }

    private fun setFirst(uri: Uri) {
        firstUri = uri
        prefs.edit().putString("firstUri", uri.toString()).apply()
        syncDoubleBtn(); loadFirst(uri)
    }

    private fun loadFirst(uri: Uri) {
        saveExecutor.execute {
            val b = runCatching { Exposure.decode(contentResolver, uri, maxLong = 2048) }.getOrNull()
            runOnUiThread {
                if (b == null) { Toast.makeText(this, "Couldn't open that photo.", Toast.LENGTH_SHORT).show(); discardFirst(); return@runOnUiThread }
                ghostBmp = b; applyGhost()
            }
        }
    }

    private fun discardFirst() {
        if (firstUri == null) return
        firstUri = null; ghostBmp = null
        prefs.edit().remove("firstUri").apply()
        syncDoubleBtn(); applyGhost()
    }

    private fun syncCoachBtn() {
        coachBtn.tint = if (coach) cGold else cMuted
        (coachBtn.background as GradientDrawable).setColor(if (coach) withAlpha(cGold, 0.18f) else cElev)
        coachBtn.invalidate()
    }

    private fun syncFlashBtn() {
        flashBtn.icon = when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> IconButton.Icon.FLASH_AUTO; ImageCapture.FLASH_MODE_ON -> IconButton.Icon.FLASH_ON; else -> IconButton.Icon.FLASH_OFF }
        val on = flashMode != ImageCapture.FLASH_MODE_OFF
        flashBtn.tint = if (on) cGold else cMuted
        (flashBtn.background as GradientDrawable).setColor(if (on) withAlpha(cGold, 0.18f) else cElev)
        flashBtn.contentDescription = when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "Flash auto"; ImageCapture.FLASH_MODE_ON -> "Flash on"; else -> "Flash off" }
        flashBtn.invalidate()
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
        imageCapture?.flashMode = flashMode
        syncFlashBtn()
    }

    // ---- compose
    private fun applyGuide() {
        overlay.guide = guide; overlay.quadrant = quadrant; overlay.triFlip = triFlip; overlay.align = align
        if (::preview.isInitialized) overlay.post { applyLiveArt() }
        guideRow.removeAllViews()
        for (g in Guide.values()) {
            guideRow.addView(chip(g.label, g == guide) { guide = g; aligned = false; save(); applyGuide() },
                LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        secondRow.removeAllViews()
        when (guide) {
            Guide.SPIRAL -> {
                for (q in 0..3) secondRow.addView(tile(q == quadrant, { c, p, on -> drawMiniSpiral(c, p, q, on) }) { quadrant = q; aligned = false; save(); applyGuide() },
                    LinearLayout.LayoutParams(dpi(52f), dpi(40f)).apply { rightMargin = dpi(8f) })
            }
            Guide.TRIANGLES -> {
                for (f in listOf(false, true)) secondRow.addView(tile(f == triFlip, { c, p, on -> drawDir(c, p, f, on) }) { triFlip = f; aligned = false; save(); applyGuide() },
                    LinearLayout.LayoutParams(dpi(52f), dpi(40f)).apply { rightMargin = dpi(8f) })
            }
            else -> {}
        }
        overlay.invalidate(); updateHud()
    }

    private fun tile(on: Boolean, painter: View.(Canvas, Paint, Boolean) -> Unit, onClick: () -> Unit) = object : View(this) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) { painter.invoke(this, c, p, on) }
    }.apply {
        background = if (on) rounded(withAlpha(cGold, 0.14f), cGold, 10f) else rounded(cElev, cLine, 10f)
        setOnClickListener { onClick() }
    }

    private fun View.drawMiniSpiral(c: Canvas, p: Paint, q: Int, on: Boolean) {
        val s = Spiral.of(q)
        val h = height * 0.62f; val u = h / s.bh
        val left = (width - s.bw * u) / 2; val top = (height - h) / 2
        val ex = left - s.ox * u; val ey = top - s.oy * u
        val path = android.graphics.Path()
        val pts = s.pts
        path.moveTo(ex + pts[0] * u, ey + pts[1] * u)
        var i = 6
        while (i < pts.size) { path.lineTo(ex + pts[i] * u, ey + pts[i + 1] * u); i += 6 }
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.4f); p.strokeCap = Paint.Cap.ROUND; p.color = if (on) cGold else withAlpha(cText, 0.7f)
        c.drawPath(path, p)
        p.style = Paint.Style.FILL; c.drawCircle(ex, ey, dp(1.8f), p)
    }

    private fun View.drawAlign(c: Canvas, p: Paint, a: Int, on: Boolean) {
        val fw = dp(20f); val fh = dp(24f); val x0 = (width - fw) / 2; val y0 = (height - fh) / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = if (on) cGold else cMuted; p.alpha = 120
        c.drawRect(x0, y0, x0 + fw, y0 + fh, p)
        val bw = fh * 0.639f; val bx = when (a) { 0 -> x0; 2 -> x0 + fw - bw; else -> x0 + (fw - bw) / 2 }
        p.style = Paint.Style.FILL; p.alpha = if (on) 200 else 150
        c.drawRect(bx, y0, bx + bw, y0 + fh, p)
    }

    private fun View.drawDir(c: Canvas, p: Paint, flip: Boolean, on: Boolean) {
        val s = dp(20f); val x0 = (width - s) / 2; val y0 = (height - s) / 2
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f); p.color = if (on) cGold else cMuted
        p.alpha = 110; c.drawRect(x0, y0, x0 + s, y0 + s, p); p.alpha = 255
        val a = if (!flip) PointF(x0, y0) else PointF(x0 + s, y0)
        val b = if (!flip) PointF(x0 + s, y0 + s) else PointF(x0, y0 + s)
        val c1 = if (!flip) PointF(x0 + s, y0) else PointF(x0, y0)
        val c2 = if (!flip) PointF(x0, y0 + s) else PointF(x0 + s, y0 + s)
        c.drawLine(a.x, a.y, b.x, b.y, p)
        val f1 = Geo.footOf(a, b, c1); val f2 = Geo.footOf(a, b, c2)
        c.drawLine(c1.x, c1.y, f1.x, f1.y, p); c.drawLine(c2.x, c2.y, f2.x, f2.y, p)
    }

    // ---- art
    private fun applyArtUi() {
        artRow.removeAllViews(); paramRow.removeAllViews()
        if (!Art.supported) {
            paramRow.addView(label("Art modes need Android 13 or newer.", 12f, cMuted))
            artSlider.visibility = View.INVISIBLE
            return
        }
        for (m in ArtMode.values()) {
            artRow.addView(chip(m.label, m == art) {
                art = m; artValues = Art.loadValues(prefs, m); artParam = 0
                overlay.squareGuide = art == ArtMode.HOLGA; overlay.invalidate(); updateHud()
                save(); applyArtUi(); applyLiveArt(); applyLayer()
            }, LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        if (art == ArtMode.OFF) {
            paramRow.addView(label("Pick a look. The untouched original is always saved too.", 11.5f, cMuted))
            artSlider.visibility = View.INVISIBLE
            return
        }
        art.shown.forEach { i ->
            val on = i == artParam
            paramRow.addView(label(art.sliders[i].first.uppercase(Locale.US), 10f, if (on) cGold else cMuted, mono, 0.1f).apply {
                setPadding(0, 0, dpi(12f), 0); setOnClickListener { artParam = i; applyArtUi() }
            })
        }
        paramRow.addView(spacer())
        paramRow.addView(label("${(artValues[artParam] * 100).roundToInt()}", 11f, cGold, mono))
        artSlider.visibility = View.VISIBLE
        artSlider.progress = (artValues[artParam] * 100).roundToInt()
    }

    private fun onArtSlider(v: Float) {
        artValues[artParam] = v
        Art.saveValues(prefs, art, artValues)
        (paramRow.getChildAt(paramRow.childCount - 1) as? TextView)?.text = "${(v * 100).roundToInt()}"
        applyLiveArt()
    }

    /** The art mode runs on the preview itself, so the viewfinder shows the finished look. */
    private var lastFocus = PointF(0.5f, 0.5f)

    /**
     * What stays sharp under Depth (0..1 across the frame): the subject you tapped, followed as you move;
     * before any tap, the spiral's eye, or the middle for other guides.
     */
    private fun liveFocus(): PointF {
        val fr = overlay.frame
        if (fr.isEmpty) return PointF(0.5f, 0.5f)
        val t = overlay.target; val st = trackState
        if (t != null && st != null && !st.lost) return PointF((t.x - fr.left) / fr.width(), (t.y - fr.top) / fr.height())
        if (guide == Guide.SPIRAL) Geo.goals(Guide.SPIRAL, overlay.guideFrame, quadrant, triFlip, align).firstOrNull()?.let {
            return PointF((it.x - fr.left) / fr.width(), (it.y - fr.top) / fr.height())
        }
        return PointF(0.5f, 0.5f)
    }

    private fun applyLiveArt() {
        if (!::preview.isInitialized) return
        if (!Art.supported || art == ArtMode.OFF || overlay.frame.isEmpty) { if (Build.VERSION.SDK_INT >= 31) camLayer.setRenderEffect(null); liveShader = null; return }
        if (Build.VERSION.SDK_INT < 33) return
        val sh = liveShader?.takeIf { it === liveFor(art) } ?: Art.newShader(art).also { liveShader = it; liveMode = art }
        if (sh == null) { camLayer.setRenderEffect(null); return }
        val f = overlay.frame
        val fp = liveFocus(); lastFocus = fp
        Art.setUniforms(sh, artValues, f.left, f.top, f.width(), f.height(), liveSeed, fp.x, fp.y)
        camLayer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"))   // live picture and first exposure together
    }
    private var liveMode: ArtMode? = null
    private fun liveFor(m: ArtMode): RuntimeShader? = if (liveMode == m) liveShader else null

    // ---- exposure
    private fun refreshExposure() {
        val st = camera?.cameraInfo?.exposureState ?: return
        if (!st.isExposureCompensationSupported) { evValue.text = "not adjustable"; evSlider.isEnabled = false; return }
        evStep = st.exposureCompensationStep.toFloat()
        val r = st.exposureCompensationRange
        // offer up to ±2 EV, or the camera's own limit if that's smaller
        evMin = max(r.lower, (-2f / evStep).roundToInt()); evMax = min(r.upper, (2f / evStep).roundToInt())
        evIndex = st.exposureCompensationIndex.coerceIn(evMin, evMax)
        evSlider.isEnabled = true
        evSlider.max = evMax - evMin
        evSlider.progress = evIndex - evMin
        evValue.text = String.format(Locale.US, "%+.1f EV", evIndex * evStep).replace("+0.0", "0.0")
    }

    private fun onEvSlider(v: Int) = setEv(v + evMin)

    private fun setEv(index: Int) {
        val cam = camera ?: return
        evIndex = index.coerceIn(evMin, evMax)
        cam.cameraControl.setExposureCompensationIndex(evIndex)
        evSlider.progress = evIndex - evMin
        evValue.text = String.format(Locale.US, "%+.1f EV", evIndex * evStep).replace("+0.0", "0.0")
        applyLayer()
    }

    private fun gate(msg: String) { gateMsg.text = msg; gateMsg.visibility = View.VISIBLE }

    // =========================================================== camera
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val p = provider ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()
        val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val prev = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build()).build()
            .also { it.setSurfaceProvider(preview.surfaceProvider) }
        val cap = ImageCapture.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .build()
        imageCapture = cap
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { img ->
            val st = try { tracker.process(img) } finally { img.close() }
            if (st != null || trackState != null) runOnUiThread { onTrack(st) }
        }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            camera = p.bindToLifecycle(this, selector, prev, cap, analysis)
        } catch (e: Exception) {
            gate("The camera could not start: ${e.message}"); return
        }
        gateMsg.visibility = View.GONE
        sourceTxt.text = if (front) "LIVE · FRONT" else "LIVE · REAR"
        chipsFor = ""
        lensStops = camera?.let { measureLenses(it) }
        camera?.cameraInfo?.zoomState?.observe(this) { z -> onZoom(z) }
        refreshExposure()
        if (evIndex != 0) camera?.cameraControl?.setExposureCompensationIndex(evIndex)
        hintUntil = SystemClock.uptimeMillis() + 4000
        updateHud(); applyLiveArt()
    }

    private fun onZoom(z: ZoomState) {
        zoomRatio = z.zoomRatio; zoomMin = z.minZoomRatio; zoomMax = z.maxZoomRatio
        val key = "$zoomMin-$zoomMax"
        if (key != chipsFor) { chipsFor = key; buildZoomChips() }
        syncZoomChips()
    }

    /**
     * Reads the physical cameras behind the logical one and works out each lens's field of view
     * relative to the main camera. Ultra-wide < 1, telephoto > 1. Null if the phone doesn't say.
     */
    @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
    private fun measureLenses(cam: Camera): List<Float>? = runCatching {
        val mgr = getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
        val id = androidx.camera.camera2.interop.Camera2CameraInfo.from(cam.cameraInfo).cameraId
        fun reach(ch: android.hardware.camera2.CameraCharacteristics): Float? {
            val f = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
            val sz = ch.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            return f / kotlin.math.hypot(sz.width, sz.height)            // focal length per sensor diagonal = angle of view
        }
        val phys = mgr.getCameraCharacteristics(id).physicalCameraIds
        if (phys.isEmpty()) return@runCatching null
        val reaches = phys.mapNotNull { reach(mgr.getCameraCharacteristics(it)) }.sorted()
        if (reaches.isEmpty()) return@runCatching null
        // 1× is the main camera: the widest lens, unless the camera offers an ultra-wide below 1×
        val ultra = zoomMinOf(cam) < 0.95f
        val main = if (ultra && reaches.size > 1) reaches[1] else reaches[0]
        reaches.map { it / main }
    }.getOrNull()

    private fun zoomMinOf(cam: Camera) = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f

    private fun zoomStops(): List<Float> {
        val out = ArrayList<Float>()
        if (zoomMin < 0.95f) out.add(zoomMin)                         // the ultra-wide, where the camera offers it
        out.add(1f)
        if (zoomMax >= 2f) out.add(2f)                                // main-sensor crop: full quality on modern phones
        val lenses = lensStops
        if (lenses != null) {
            // only telephotos the phone actually has; no digital-only stops
            for (r in lenses) if (r > 2.5f && r <= zoomMax + 0.01f) out.add(if (abs(r - r.roundToInt()) < 0.35f) r.roundToInt().toFloat() else r)
        } else if (zoomMax >= 4.9f) out.add(5f)                      // the phone won't say; 5× is the safest guess
        return out.distinct()
    }

    private fun zoomLabel(z: Float) = when {
        z < 1f -> String.format(Locale.US, "%.1f×", z).removePrefix("0")
        abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}×"
        else -> String.format(Locale.US, "%.1f×", z)
    }

    private fun buildZoomChips() {
        zoomRow.removeAllViews()
        val stops = zoomStops()
        if (stops.size < 2) return
        for (s in stops) {
            val b = label(zoomLabel(s), 12f, cText, mono).apply {
                gravity = Gravity.CENTER; setPadding(0, 0, 0, 0); tag = s; maxLines = 1
                contentDescription = "Zoom ${zoomLabel(s)}"
                setOnClickListener { camera?.cameraControl?.setZoomRatio(s) }
            }
            zoomRow.addView(b, LinearLayout.LayoutParams(dpi(42f), dpi(42f)).apply { leftMargin = dpi(5f); rightMargin = dpi(5f) })
        }
    }

    /**
     * The chip you're on, or the one just below where a pinch has landed, lights up and shows the
     * live value ("1.4×"), so pinching tells you where you are. Tapping any chip snaps back to it.
     */
    private fun syncZoomChips() {
        var active: TextView? = null
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView
            if ((v.tag as Float) <= zoomRatio + 0.02f) active = v
        }
        if (active == null && zoomRow.childCount > 0) active = zoomRow.getChildAt(0) as TextView
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView; val on = v === active
            val stop = v.tag as Float
            v.text = if (on && abs(stop - zoomRatio) > 0.04f) zoomLabel(zoomRatio) else zoomLabel(stop)
            v.setTextColor(if (on) cGold else cMuted)
            v.background = if (on) rounded(withAlpha(cGold, 0.16f), withAlpha(cGold, 0.5f)) else rounded(withAlpha(cElev, 0.9f))
        }
    }

    // =========================================================== gestures
    override fun onSubjectTap(sx: Float, sy: Float) {
        val fr = overlay.frame
        if (!fr.contains(sx, sy)) return
        overlay.showFocus(sx, sy)
        run {
            // The tap names the subject to follow, so the phone can buzz when it lands on the guide.
            // In Coach, tapping the marked subject again lets it go; without Coach there's nothing to tap, so a tap always re-aims.
            val cur = overlay.target
            if (coach && cur != null && trackState?.lost != true && Geo.dist(cur, PointF(sx, sy)) < dp(28f)) {
                tracker.clear(); trackState = null; overlay.target = null; aligned = false; overlay.invalidate(); updateHud()
            } else {
                var nx = (sx - fr.left) / fr.width(); val ny = (sy - fr.top) / fr.height()
                if (front) nx = 1f - nx                                   // the preview is mirrored, the analysis frames aren't
                tracker.target(nx, ny)
                aligned = false
            }
        }
        camera?.let { cam ->
            val pt = preview.meteringPointFactory.createPoint(sx, sy)
            cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).setAutoCancelDuration(5, TimeUnit.SECONDS).build())
        }
    }

    override fun onDoubleTap() {
        when (guide) {
            Guide.SPIRAL -> { quadrant = (quadrant + 1) % 4; aligned = false; save(); applyGuide() }
            Guide.TRIANGLES -> { triFlip = !triFlip; aligned = false; save(); applyGuide() }
            else -> {}
        }
    }

    override fun onPinch(factor: Float) {
        val cam = camera ?: return
        cam.cameraControl.setZoomRatio((zoomRatio * factor).coerceIn(zoomMin, zoomMax))
    }

    // =========================================================== tracking + coaching
    private fun onTrack(st: Tracker.State?) {
        trackState = st
        val fr = overlay.frame
        if (st == null || fr.isEmpty) { overlay.target = null }
        else {
            val nx = if (front) 1f - st.nx else st.nx
            overlay.target = PointF(fr.left + nx * fr.width(), fr.top + st.ny * fr.height())
            overlay.targetLost = st.lost
        }
        updateHud()
        overlay.invalidate()
        // Depth follows the subject: refresh the look only when the sharp spot has actually moved
        if (art != ArtMode.OFF) { val fp = liveFocus(); if (Geo.dist(fp, lastFocus) > 0.004f) applyLiveArt() }
    }

    /** A brief "Nice" when the subject lands; never shown while the subject is lost. */
    private fun showNice() {
        nice.animate().cancel()
        nice.alpha = 1f; nice.visibility = View.VISIBLE
        nice.animate().alpha(0f).setStartDelay(900).setDuration(350).withEndAction { nice.visibility = View.GONE }.start()
    }

    private fun buzz() {
        val fb = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        overlay.performHapticFeedback(fb)
    }

    /** Screen offset (subject minus goal) → the move that fixes it, in the world's terms. */
    private fun coachLine(dx: Float, dy: Float): String {
        val (wx, wy) = when (deviceRot) { 90 -> -dy to dx; 180 -> -dx to -dy; 270 -> dy to -dx; else -> dx to dy }
        val out = ArrayList<String>(2)
        // Subject right of the goal: aim the camera right, which slides the scene left.
        if (abs(wx) > 0.015f) out.add((if (abs(wx) < 0.08f) "slightly " else "") + if (wx > 0) "right" else "left")
        if (abs(wy) > 0.015f) out.add((if (abs(wy) < 0.08f) "slightly " else "") + if (wy > 0) "down" else "up")
        return if (out.isEmpty()) "Hold there" else "Aim " + out.joinToString(" and ")
    }

    private fun levelBit(): String {
        val l = level ?: return ""
        return if (l < 1.5f) " · level" else " · ${l.roundToInt()}° off level"
    }

    private fun updateHud() {
        if (!::hud.isInitialized) return
        overlay.coach = coach
        if (!coach) {
            // Normal shooting: just the guide on screen. The tapped subject is followed silently, and
            // the phone buzzes once when it lands on a guide point (eye, power point, intersection).
            pill.visibility = View.GONE; overlay.goal = null; overlay.aligned = false
            val fr = overlay.guideFrame; val st = trackState; val p = overlay.target
            if (st == null || p == null || st.lost || fr.isEmpty) {
                aligned = false
                if (st?.lost == true && nice.visibility == View.VISIBLE) { nice.animate().cancel(); nice.visibility = View.GONE }
                return
            }
            val best = Geo.nearestGoal(guide, fr, quadrant, triFlip, p, align) ?: run { aligned = false; return }
            val d = Geo.dist(p, best)
            val r = max(dp(14f), 0.045f * min(fr.width(), fr.height()))
            if (!aligned && d < r) { aligned = true; buzz(); showNice() } else if (aligned && d > r * 1.8f) aligned = false
            return
        }
        val fr = overlay.guideFrame
        val st = trackState
        val text: String
        var on = false
        val p = overlay.target
        if (st == null || p == null) { text = "Tap your subject"; overlay.goal = null }
        else if (st.lost) { text = "Tap your subject again"; overlay.goal = null }
        else {
            val best = Geo.nearestGoal(guide, fr, quadrant, triFlip, p, align)
            overlay.goal = best
            if (best == null) { text = "Pick a guide to aim for"; aligned = false }
            else {
                val d = Geo.dist(p, best)
                val r = max(dp(14f), 0.045f * min(fr.width(), fr.height()))
                if (!aligned && d < r) { aligned = true; buzz() } else if (aligned && d > r * 1.8f) aligned = false
                on = aligned
                text = if (aligned) "Aligned" + levelBit() else coachLine((p.x - best.x) / fr.width(), (p.y - best.y) / fr.height())
            }
        }
        overlay.aligned = aligned
        pill.visibility = View.VISIBLE
        pill.text = text
        pill.setTextColor(if (on) cGold else cText)
        pill.background = if (on) rounded(0xD93C2E14.toInt(), withAlpha(cGold, 0.85f)) else rounded(withAlpha(cBase, 0.72f), withAlpha(cText, 0.18f))
    }

    // =========================================================== level
    override fun onSensorChanged(e: SensorEvent) {
        val dev = Geo.levelOff(e.values[0], e.values[1], e.values[2])
        level = if (dev == null) null else level?.let { it * 0.8f + dev * 0.2f } ?: dev
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // =========================================================== capture
    private fun capture(btn: View) {
        val cap = imageCapture ?: return
        btn.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70).withEndAction { btn.animate().scaleX(1f).scaleY(1f).setDuration(90).start() }.start()
        stage.animate().alpha(0.35f).setDuration(60).withEndAction { stage.animate().alpha(1f).setDuration(160).start() }.start()
        val name = "FIB_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fibonacci Camera")
        }
        // The art look at the moment of the shot. The original is saved as-is; the look becomes a copy.
        val fp = liveFocus()
        val recipe = if (Art.supported && art != ArtMode.OFF) Recipe(art, artValues.copyOf(), liveSeed, fp.x, fp.y) else null
        val first = if (double) firstUri else null
        val takingFirst = double && first == null
        val blendNow = blend; val strengthNow = blendStrength
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                val uri = out.savedUri ?: return
                lastUri = uri
                prefs.edit().putString("lastUri", uri.toString()).putString("thumbUri", uri.toString()).apply()
                if (takingFirst) { setFirst(uri); loadThumb(); return }           // exposure 1: kept as-is, now floats over the live view
                if (first != null) {                                               // exposure 2: original kept, the pair saved as a copy
                    saveDouble(first, uri, name, blendNow, strengthNow, recipe)
                    firstUri = null; ghostBmp = null; prefs.edit().remove("firstUri").apply(); syncDoubleBtn(); applyGhost()
                    loadThumb(); return
                }
                if (recipe != null) {
                    prefs.edit().putString("art:$uri", recipe.encode()).apply()   // the editor reopens with this look
                    saveArtCopy(uri, name, recipe)
                    liveSeed = (Math.random() * 1000).toFloat(); applyLiveArt()   // fresh leaks and grain for the next frame
                }
                loadThumb()
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun saveDouble(first: Uri, second: Uri, name: String, b: Blend, strength: Float, r: Recipe?) {
        saveExecutor.execute {
            val res = runCatching {
                val base = Exposure.decode(contentResolver, second, mutable = true)
                val top = Exposure.decode(contentResolver, first, maxLong = maxOf(base.width, base.height))
                var out = Exposure.coverOnto(base, top, b, strength)
                top.recycle()
                if (r != null && Build.VERSION.SDK_INT >= 33) { val styled = Art.finish(Art.render(out, r), r.mode); if (styled !== out) { out.recycle(); out = styled } }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}_double" + (if (r != null) "_${r.mode.fileTag}" else ""))
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fibonacci Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
                contentResolver.openOutputStream(dest).use { os -> out.compress(Bitmap.CompressFormat.JPEG, 95, os!!) }
                contentResolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                out.recycle()
                dest
            }
            runOnUiThread {
                res.onSuccess { dest ->
                    lastUri = dest
                    prefs.edit().putString("lastUri", dest.toString()).putString("thumbUri", dest.toString()).apply(); loadThumb()
                    Toast.makeText(this, "Double exposure saved · both originals kept", Toast.LENGTH_SHORT).show()
                }.onFailure { Toast.makeText(this, "Saved both originals; combining them failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun saveArtCopy(original: Uri, name: String, r: Recipe) {
        if (Build.VERSION.SDK_INT < 33) return
        saveExecutor.execute {
            val res = runCatching {
                val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, original)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
                val out = Art.finish(Art.render(src, r), r.mode)
                src.recycle()
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "${name}_${r.mode.fileTag}")
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Fibonacci Camera")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val dest = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("no destination")
                contentResolver.openOutputStream(dest).use { os -> out.compress(Bitmap.CompressFormat.JPEG, 95, os!!) }
                contentResolver.update(dest, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                out.recycle()
                dest
            }
            runOnUiThread {
                res.onSuccess { dest -> prefs.edit().putString("thumbUri", dest.toString()).apply(); loadThumb() }
                    .onFailure { Toast.makeText(this, "Saved the original; the art copy failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadThumb() {
        if (!::thumb.isInitialized) return
        val uri = prefs.getString("thumbUri", null)?.let { Uri.parse(it) } ?: lastUri
        if (uri == null) { thumb.setImageDrawable(null); return }
        saveExecutor.execute {
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
            runOnUiThread { if (bmp != null) thumb.setImageBitmap(bmp) }
        }
    }

    // The original from the shutter is already saved and is never touched again; editing makes copies.
    private fun openCrop(uri: Uri) {
        startActivity(Intent(this, CropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun pickFromLibrary() {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    // ---- updates: compare our build number with the latest one GitHub built
    private fun checkForUpdate() {
        analysisExecutor.execute {
            val latest = runCatching {
                val c = java.net.URL("https://api.github.com/repos/leisuresuitlissy/fibonacci-camera/releases/tags/android-latest").openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 6000; c.readTimeout = 6000; c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                Regex("Build (\\d+)").find(org.json.JSONObject(body).optString("body"))?.groupValues?.get(1)?.toLong()
            }.getOrNull() ?: return@execute
            val mine = runCatching { packageManager.getPackageInfo(packageName, 0).longVersionCode }.getOrDefault(Long.MAX_VALUE)
            if (latest > mine) runOnUiThread { updateBtn.visibility = View.VISIBLE }
        }
    }
}
