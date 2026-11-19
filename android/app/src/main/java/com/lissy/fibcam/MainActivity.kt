package com.lissy.fibcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Outline
import android.graphics.PointF
import android.graphics.RectF
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
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.camera.view.ScreenFlashView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
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

    // ---- palette (Figma colour styles; see T)
    private val cBase = T.CARBON
    private val cElev = T.GRAPHITE
    private val cLine = T.BORDER
    private val cText = T.IVORY
    private val cMuted = T.SUBTLE
    private val cGold = T.AMBER
    private val mono: Typeface get() = T.medium
    private val medium: Typeface get() = T.medium

    // ---- views
    private lateinit var root: FrameLayout
    private lateinit var stage: FrameLayout
    private lateinit var preview: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var hud: FrameLayout
    private lateinit var hudStack: LinearLayout
    private lateinit var pill: TextView
    private lateinit var nice: TextView
    private lateinit var exposureTag: TextView
    private lateinit var header: FrameLayout
    private lateinit var coachBtn: TextView
    private lateinit var doubleBtn: ImageView
    private lateinit var flashBtn: FrameLayout
    private lateinit var flashIcon: ImageView
    private lateinit var flashAuto: TextView
    /** Front camera has no flash unit: a full-screen white flash above everything (outside the live Look's effect). */
    private lateinit var screenFlash: ScreenFlashView
    private lateinit var thumb: ImageView
    private lateinit var gateMsg: TextView
    private lateinit var tabRow: LinearLayout
    private lateinit var selector: FrameLayout
    private lateinit var composeScroller: FadeScroller
    private lateinit var lookScroller: FadeScroller
    private val composeTiles = HashMap<Guide, TileView>()
    private val lookTiles = HashMap<ArtMode, TileView>()
    private lateinit var ctxArea: FrameLayout
    private lateinit var composeSub: LinearLayout
    private lateinit var followBtn: FollowToggle
    private lateinit var lookControls: LookControls
    /** Exposure lives on the viewfinder: a chip at the end of the zoom pill opens [evBar] in its place. */
    private lateinit var evBar: LinearLayout
    private lateinit var evChip: TextView
    private lateinit var evDivider: View
    private lateinit var evValue: TextView
    private lateinit var evSlider: EvSlider
    private lateinit var zoomRow: LinearLayout
    private lateinit var camLayer: FrameLayout
    private lateinit var ghost: ImageView
    private lateinit var shutterRow: FrameLayout
    private var settingsView: View? = null
    private var viewerView: View? = null

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
    private var artState = ArtState(ArtMode.OFF.defaults, false)
    private val artValues: FloatArray get() = artState.values
    /** The active Look control: an index into art.controls. */
    private var artParam = 0
    private val artSlot: Int get() = art.controls.getOrNull(artParam)?.slot ?: 0
    /** Follow guide: shared camera state, kept across tabs and Looks. */
    private var follow = false
    /** Which of the active guide's anchors Follow guide uses; remembered per guide. */
    private var anchor: Int
        get() = prefs.getInt("anchor:${guide.name}", 0)
        set(v) { prefs.edit().putInt("anchor:${guide.name}", v).apply() }
    /** A point tapped in the viewfinder (frame uv): the sharp region goes there until the guide changes. */
    private var tapFocus: PointF? = null
    /**
     * Tilt Shift's band is a line, so it only follows guides whose geometry suits a line: the thirds and grid
     * lines (the classic miniature band), and the triangle and dynamic-symmetry diagonals (which also set its
     * angle). With Spiral, Centre, Diagonals or Crop the band stays wherever you tap, drag or slide it.
     */
    private val tiltFollows: Boolean get() = art == ArtMode.TILT_SHIFT && followActive &&
        guide in setOf(Guide.THIRDS, Guide.PHI_GRID, Guide.TRIANGLES, Guide.DYNAMIC)
    /** Whether Follow guide is steering the current Look's target (Tilt Shift only with line-friendly guides). */
    private val lookFollows: Boolean get() = if (art == ArtMode.TILT_SHIFT) tiltFollows else followActive
    /** Follow guide is on and the guide has a focal point to follow. */
    private val followActive: Boolean get() = follow && GuideShape.hasAnchors(guide)
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
    private var evSupported = true
    /** EV to restore once the camera reports its steps (Remember my settings). */
    private var evWanted = 0f
    // settings
    private var saveOriginal = true
    private var remember = true
    private var guideOpacity = 0.75f
    private var shot: String? = null
    private var renderShot: String? = null
    private var evAtStart = false

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
    private var insetTop = 0
    private var insetBottom = 0

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openCrop(uri)
    }

    private val pickFirst = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) setFirst(uri)
    }

    /** Android's own "allow Crooked Light to delete this?" prompt, for photos the app no longer owns. */
    private val askDelete = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == RESULT_OK) afterDelete() else Toast.makeText(this, "Photo kept", Toast.LENGTH_SHORT).show()
    }

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else gate("Camera access was refused. Allow it in Settings → Apps → Crooked Light → Permissions.")
    }

    // =========================================================== lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        T.init(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        shot = intent?.getStringExtra("fibShot")
        saveOriginal = prefs.getBoolean("saveOriginal", true)
        remember = prefs.getBoolean("remember2", false)     // off by default: every launch starts fresh
        guideOpacity = prefs.getFloat("guideOpacity", 0.75f)
        coach = prefs.getBoolean("coach", false)
        // A restore (Android rebuilt the screen behind your back) brings everything back. Otherwise every launch
        // starts fresh: Compose, Thirds, the Original look, every Look's sliders back at 50%, exposure 0.
        // Settings › Remember my settings brings back the last Look, guide and exposure value instead.
        val restoring = savedInstanceState != null
        val keep = restoring || remember
        if (!keep) prefs.edit().apply { for (k in prefs.all.keys) if (k.startsWith("art3:")) remove(k) }.apply()
        layer = if (restoring) prefs.getString("layer", "COMPOSE") ?: "COMPOSE" else "COMPOSE"
        if (layer !in listOf("COMPOSE", "ART")) layer = "COMPOSE"     // Exposure is no longer a tab
        guide = if (keep) runCatching { Guide.valueOf(prefs.getString("guide", Guide.THIRDS.name)!!) }.getOrDefault(Guide.THIRDS) else Guide.THIRDS
        quadrant = if (keep) prefs.getInt("quadrant", 0) else 0
        triFlip = if (keep) prefs.getBoolean("triFlip", false) else false
        art = if (keep) runCatching { ArtMode.valueOf(prefs.getString("art", ArtMode.OFF.name)!!) }.getOrDefault(ArtMode.OFF) else ArtMode.OFF
        if (!Art.supported) art = ArtMode.OFF
        artState = Art.loadState(prefs, art)
        evWanted = if (keep) prefs.getFloat("ev", 0f) else 0f
        follow = prefs.getBoolean("follow2", true)          // on by default
        Haptics.enabled = prefs.getBoolean("haptics", true)
        lastUri = prefs.getString("lastUri", null)?.let { Uri.parse(it) }
        double = prefs.getBoolean("double", false)
        firstUri = prefs.getString("firstUri", null)?.let { Uri.parse(it) }
        blend = runCatching { Blend.valueOf(prefs.getString("blend", Blend.SCREEN.name)!!) }.getOrDefault(Blend.SCREEN)
        blendStrength = prefs.getFloat("blendStrength", 0.6f)
        shot?.let { applyShot(it) }
        setContentView(buildUi())
        screenFlash = ScreenFlashView(this).also { root.addView(it, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)) }
        screenFlash.setScreenFlashWindow(window)
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceRot = when (o) { in 45..134 -> 90; in 135..224 -> 180; in 225..314 -> 270; else -> 0 }
                imageCapture?.targetRotation = when (deviceRot) { 90 -> Surface.ROTATION_270; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            }
        }
        overlay.squareGuide = art == ArtMode.HOLGA
        overlay.guideOpacity = guideOpacity
        applyGuide(); applyArtUi(); applyLayer(); syncCoachBtn(); syncFlashBtn(); syncDoubleBtn()
        firstUri?.let { if (double) loadFirst(it) }
        loadThumb()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Back steps out one level at a time: Settings → camera; a Look's setting → its first setting
            // (Intensity); Look or Exposure → Compose (the main menu); Compose → leave the app as usual.
            override fun handleOnBackPressed() {
                when {
                    settingsView != null -> closeSettings()
                    viewerView != null -> closeViewer()
                    evBar.visibility == View.VISIBLE -> closeEv()
                    layer == "ART" && artParam != 0 -> { artParam = 0; applyArtUi() }
                    layer != "COMPOSE" -> { layer = "COMPOSE"; save(); applyLayer() }
                    else -> { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
                }
            }
        })
        if (shot == "settings") root.post { openSettings() }
        renderShot?.let { r -> root.post { showRenderTest(r) } }
        if (evAtStart) root.postDelayed({ openEv() }, 2500)
        Updater.listener = { st -> runOnUiThread { onUpdateStatus(st) } }
        analysisExecutor.execute { Updater.cleanUp(this) }     // the first check happens in onResume
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    /**
     * Test hook for the CI screenshots: `-e fibShot look:HOLGA:3` etc. It only sets screen state, the same
     * as tapping would, and is never sent by the app itself.
     */
    private fun applyShot(s: String) {
        val p = s.split(":")
        when (p[0]) {
            "compose" -> { layer = "COMPOSE"; guide = runCatching { Guide.valueOf(p[1]) }.getOrDefault(guide)
                p.getOrNull(2)?.toIntOrNull()?.let { if (guide == Guide.SPIRAL) quadrant = it else triFlip = it == 1 }
                follow = p.getOrNull(3) == "follow"
                p.getOrNull(4)?.toIntOrNull()?.let { anchor = it } }
            "look" -> { layer = "ART"; follow = false; art = runCatching { ArtMode.valueOf(p[1]) }.getOrDefault(ArtMode.OFF); artState = Art.loadState(prefs, art)
                artParam = p.getOrNull(2)?.toIntOrNull() ?: 0
                p.getOrNull(3)?.toIntOrNull()?.let { artValues[5] = it.toFloat() }
                if (p.getOrNull(4) == "follow") follow = true
                p.getOrNull(5)?.let { n -> guide = runCatching { Guide.valueOf(n) }.getOrDefault(guide) }
                p.getOrNull(6)?.toIntOrNull()?.let { if (guide == Guide.SPIRAL) quadrant = it else triFlip = it == 1 }
                p.getOrNull(7)?.toIntOrNull()?.let { anchor = it }
                // "setN": put the active control at N% (for the dial screenshots)
                p.getOrNull(8)?.removePrefix("set")?.toIntOrNull()?.let { n -> art.controls.getOrNull(artParam)?.slot?.takeIf { it >= 0 }?.let { artValues[it] = n / 100f } } }
            "exposure" -> evAtStart = true
            "render" -> renderShot = s
            "batch" -> labBatch()
            "double" -> { double = true; coach = true; flashMode = ImageCapture.FLASH_MODE_ON }
        }
    }

    override fun onResume() {
        super.onResume()
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_UI)
        if (orientation.canDetectOrientation()) orientation.enable()
        loadThumb()
        if (shot == null) {
            checkForUpdate(manual = false)                 // throttled to every six hours inside
            pendingInstall?.let { f -> if (canInstall()) { pendingInstall = null; launchInstall(f) } }
            overlay.postDelayed({ maybeOfferUpdate() }, 1500)
        }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        lastTouch = SystemClock.uptimeMillis()
        return super.dispatchTouchEvent(ev)
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
            .putBoolean("follow2", follow).putBoolean("haptics", Haptics.enabled)
            .putBoolean("double", double).putString("firstUri", firstUri?.toString())
            .putString("blend", blend.name).putFloat("blendStrength", blendStrength)
            .putFloat("ev", if (evSupported) evIndex * evStep else evWanted)
            .putBoolean("saveOriginal", saveOriginal).putBoolean("remember2", remember).putFloat("guideOpacity", guideOpacity)
            .apply()
    }

    // =========================================================== UI helpers
    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dpi(v: Float) = dp(v).roundToInt()
    private fun rounded(fill: Int, stroke: Int? = null, radius: Float = 999f, strokeW: Float = 1f) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius); if (stroke != null) setStroke(dpi(strokeW).coerceAtLeast(1), stroke)
    }
    private fun withAlpha(c: Int, a: Float) = Color.argb((a * 255).roundToInt(), Color.red(c), Color.green(c), Color.blue(c))

    /** Text for sheets and messages (sp, so it follows the system text size). Controls use [text] in dp. */
    private fun label(text: String, size: Float, color: Int, face: Typeface = T.regular, spacing: Float = 0f) = TextView(this).apply {
        this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = face; letterSpacing = spacing
        includeFontPadding = false
    }

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private fun spacer() = View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }
    private fun at(v: View, x: Int, y: Int, w: Int, h: Int) {
        val l = v.layoutParams as FrameLayout.LayoutParams
        if (l.leftMargin != x || l.topMargin != y || l.width != w || l.height != h) { l.leftMargin = x; l.topMargin = y; l.width = w; l.height = h; l.gravity = Gravity.NO_GRAVITY; v.layoutParams = l }
    }
    private fun bmp(id: Int): Bitmap? = BitmapFactory.decodeResource(resources, id, BitmapFactory.Options().apply { inScaled = false })

    /** A 44dp (or [size]) round Carbon button with a 24dp Ivory line icon. */
    private fun roundIcon(icon: Int, size: Float, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageResource(icon); scaleType = ImageView.ScaleType.CENTER
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(T.CARBON) }
        contentDescription = desc
        setOnClickListener { onClick() }
        layoutParams = lp(dpi(size), dpi(size))
    }

    /** Header button states (Figma Toggle button): Carbon; active adds a 1dp Amber ring and an Amber icon. */
    private fun setActive(v: View, icon: ImageView?, on: Boolean) {
        (v.background as GradientDrawable).setStroke(if (on) dpi(1f).coerceAtLeast(1) else 0, T.AMBER)
        icon?.setColorFilter(if (on) T.AMBER else T.IVORY)
    }

    // =========================================================== layout
    private fun buildUi(): View {
        root = FrameLayout(this).apply { setBackgroundColor(T.CARBON) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            if (b.top != insetTop || b.bottom != insetBottom) { insetTop = b.top; insetBottom = b.bottom; root.post { relayout() } }
            insets
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob -> if (r - l != or - ol || b - t != ob - ot) root.post { relayout() } }

        // viewfinder: the live picture, then the guides, then messages
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE   // a TextureView, so Looks can be applied live
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
        gateMsg = label("", 14f, cMuted).apply { gravity = Gravity.CENTER; setPadding(dpi(32f), 0, dpi(32f), 0); visibility = View.GONE }
        stage.addView(gateMsg, lp(match, match))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFrame() }
        root.addView(stage, lp(0, 0))

        // messages under the header: double exposure progress, coaching, "Nice"
        hudStack = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        exposureTag = text("", 12f, T.IVORY, T.medium).apply {
            setPadding(dpi(12f), dpi(7f), dpi(12f), dpi(7f)); visibility = View.GONE
            background = pill(T.alpha(T.CARBON, 0.85f), dp(16f), T.AMBER, dpi(1f))
            setTextColor(T.AMBER)
            setOnClickListener { discardFirst() }
        }
        hudStack.addView(exposureTag, LinearLayout.LayoutParams(wrap, wrap))
        pill = text("", 12f, T.IVORY, T.medium).apply { setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f)); visibility = View.GONE }
        hudStack.addView(pill, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(8f) })
        nice = text("Nice", 12f, T.AMBER, T.semibold).apply {
            setPadding(dpi(14f), dpi(8f), dpi(14f), dpi(8f)); visibility = View.GONE
            background = pill(T.alpha(T.CARBON, 0.85f), dp(16f), T.AMBER, dpi(1f))
        }
        hudStack.addView(nice, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(8f) })
        root.addView(hudStack, lp(0, 0))

        // header (Figma Camera / Header): Coach on the left; Double exposure and Flash on the right
        header = FrameLayout(this)
        coachBtn = text("Coach", 11f, T.IVORY, T.medium, caps = true).apply {
            gravity = Gravity.CENTER; setPadding(dpi(12f), 0, dpi(12f), 0)
            background = pill(T.CARBON, dp(16f))
            contentDescription = "Coach"
            setOnClickListener {
                coach = !coach
                if (coach) hintUntil = SystemClock.uptimeMillis() + 4000
                save(); syncCoachBtn(); updateHud(); this@MainActivity.overlay.invalidate()
            }
        }
        header.addView(coachBtn, lp(wrap, dpi(32f), Gravity.START or Gravity.CENTER_VERTICAL))
        doubleBtn = roundIcon(R.drawable.ic_double, 34f, "Double exposure") { onDoubleBtn() }
        header.addView(doubleBtn, lp(dpi(34f), dpi(34f), Gravity.END or Gravity.CENTER_VERTICAL).apply { rightMargin = dpi(48f) })
        flashBtn = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(T.CARBON) }
            setOnClickListener { cycleFlash() }
        }
        flashIcon = ImageView(this).apply { setImageResource(R.drawable.ic_flash); scaleType = ImageView.ScaleType.CENTER }
        flashBtn.addView(flashIcon, lp(match, match))
        flashAuto = text("A", 7f, T.AMBER, T.semibold).apply { visibility = View.GONE }
        flashBtn.addView(flashAuto, lp(wrap, wrap, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dpi(6f); bottomMargin = dpi(5f) })
        header.addView(flashBtn, lp(dpi(32f), dpi(32f), Gravity.END or Gravity.CENTER_VERTICAL))
        root.addView(header, lp(0, 0))

        // zoom (Figma Camera / Zoom), over the bottom of the viewfinder
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(8f), dpi(2f), dpi(8f), dpi(2f))
            background = pill(T.GRAPHITE, dp(16f)); alpha = 0.85f; visibility = View.GONE
        }
        root.addView(zoomRow, lp(wrap, dpi(31f)))

        // tabs
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setPadding(dpi(2f), dpi(2f), dpi(2f), dpi(2f))
            background = pill(T.GRAPHITE, dp(16f))
        }
        for ((key, name) in listOf("COMPOSE" to "Compose", "ART" to "Look")) {
            tabRow.addView(text(name, 13f, T.IVORY, T.medium).apply {
                gravity = Gravity.CENTER; tag = key
                setOnClickListener { if (layer != key) { layer = key; save(); applyLayer() } }
            }, LinearLayout.LayoutParams(0, match, 1f).apply { if (key != "COMPOSE") leftMargin = dpi(8f) })
        }
        root.addView(tabRow, lp(0, 0))

        // selector row: compositions or Looks
        selector = FrameLayout(this)
        composeScroller = FadeScroller(this, dp(36f))
        val cRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        val comps = listOf(Guide.OFF to ("Off" to R.drawable.comp_off), Guide.THIRDS to ("Thirds" to R.drawable.comp_thirds),
            Guide.PHI_GRID to ("Grid" to R.drawable.comp_grid), Guide.SPIRAL to ("Spiral" to R.drawable.comp_spiral),
            Guide.TRIANGLES to ("Triangle" to R.drawable.comp_triangles), Guide.DIAGONALS to ("Diagonals" to R.drawable.comp_diagonals),
            Guide.DYNAMIC to ("Dynamic" to R.drawable.comp_dynamic), Guide.CENTRE to ("Centre" to R.drawable.comp_centre),
            Guide.MARKS to ("Crop" to R.drawable.comp_crop))
        comps.forEachIndexed { i, (g, v) ->
            val t = TileView(this, bmp(v.second), 60f, 50f, RectF(0f, 0f, 60f, 50f), 4f, T.GRAPHITE, v.first, 11f, crop = false).apply {
                contentDescription = "${v.first} guide"
                setOnClickListener { if (guide != g) { guide = g; aligned = false; save(); applyGuide(); guideMoved() } }
            }
            composeTiles[g] = t
            cRow.addView(t, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        composeScroller.addView(cRow, lp(wrap, match))
        selector.addView(composeScroller, lp(match, match))
        lookScroller = FadeScroller(this, dp(36f))
        val lRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        val looks = listOf(ArtMode.OFF to ("Original" to R.drawable.look_original), ArtMode.HOLGA to ("Holga" to R.drawable.look_holga),
            ArtMode.PINHOLE to ("Pinhole" to R.drawable.look_pinhole), ArtMode.TILT_SHIFT to ("Tilt shift" to R.drawable.look_tilt_shift),
            ArtMode.GRAINY_FILM to ("Grainy film" to R.drawable.look_grainy_film), ArtMode.CROSS_PROCESS to ("Cross process" to R.drawable.look_cross_process),
            ArtMode.CYANOTYPE to ("Cyanotype" to R.drawable.look_cyanotype), ArtMode.DREAM_LENS to ("Dream lens" to R.drawable.look_dream_lens),
            ArtMode.INFRARED to ("Infrared" to R.drawable.look_infrared), ArtMode.KINO to ("Kino" to R.drawable.look_kino),
            ArtMode.EXPIRED to ("Expired" to R.drawable.look_expired), ArtMode.REDSCALE to ("Redscale" to R.drawable.look_redscale),
            ArtMode.SOLARISED to ("Solarised" to R.drawable.look_solarised), ArtMode.INSTANT to ("Instant" to R.drawable.look_instant),
            ArtMode.BLEACH_BYPASS to ("Bleach bypass" to R.drawable.look_bleach_bypass))
        looks.forEachIndexed { i, (m, v) ->
            val t = TileView(this, bmp(v.second), 60f, 50f, RectF(0f, 0f, 60f, 50f), 8f, null, v.first, 11f, crop = true).apply {
                contentDescription = "${v.first} look"
                setOnClickListener { selectLook(m) }
            }
            lookTiles[m] = t
            lRow.addView(t, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        lookScroller.addView(lRow, lp(wrap, match))
        selector.addView(lookScroller, lp(match, match))
        root.addView(selector, lp(0, 0))

        root.addView(buildShutterRow(), lp(0, 0))

        // contextual area: composition alternatives, or the Look's controls
        ctxArea = FrameLayout(this)
        composeSub = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctxArea.addView(composeSub, lp(wrap, wrap))
        // Follow guide: always at the right of the sub-options row; the variations sit on the left
        followBtn = FollowToggle(this).apply {
            setOnClickListener {
                follow = !follow; tapFocus = null; save(); syncFollow(); placeTiltOnGuide(); applyArtUi(); applyLiveArt(); focusOnAnchor()
                if (follow) showMask()
            }
        }
        ctxArea.addView(followBtn, lp(wrap, dpi(44f), Gravity.END or Gravity.TOP).apply { topMargin = -dpi(6f) })
        lookControls = LookControls(this).apply {
            onSelect = { i -> artParam = i; applyArtUi(); if (art.isFocusSlider(artSlot)) showMask() }
            onChoice = { i ->
                artValues[5] = i.toFloat()
                if (art.isRandom(i)) liveSeed = (Math.random() * 1000).toFloat()   // Random: a fresh, stable variant
                Art.saveState(prefs, art, artState); applyArtUi(); applyLiveArt()
            }
            onReset = {
                artState = Art.resetState(prefs, art); Haptics.neutral(this)
                applyArtUi(); applyLiveArt()
            }
            slider.onChange = { v -> onArtSlider(v) }
            slider.onStop = { this@MainActivity.overlay.fadeMask() }
        }
        ctxArea.addView(lookControls, lp(match, wrap))
        root.addView(ctxArea, lp(0, 0))

        // exposure: replaces the selector row and the contextual area
        evBar = buildEvBar()
        root.addView(evBar, lp(0, 0))

        return root
    }

    private fun buildShutterRow(): View {
        val bar = FrameLayout(this).apply { setBackgroundColor(T.CARBON) }
        shutterRow = bar
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            foreground = rounded(Color.TRANSPARENT, T.alpha(Color.WHITE, 0.25f), 10f)
            background = rounded(T.GRAPHITE, null, 10f)
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(10f)) } }
            clipToOutline = true
            contentDescription = "Edit the last photo"
            setOnClickListener { if (shownPhoto() != null) openViewer() else pickFromLibrary() }
            setOnLongClickListener { pickFromLibrary(); true }
        }
        bar.addView(thumb, lp(dpi(44f), dpi(44f)))
        val importBtn = roundIcon(R.drawable.ic_import, 44f, "Import a photo") {
            if (double && firstUri == null) pickFirst.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) else pickFromLibrary()
        }
        bar.addView(importBtn)
        val shutter = FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.TRANSPARENT); setStroke(dpi(3f), T.IVORY) }
            val inner = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(T.IVORY) } }
            addView(inner, lp(dpi(60f), dpi(60f), Gravity.CENTER))
            setOnClickListener { capture(this) }
            contentDescription = "Take photo"
        }
        bar.addView(shutter, lp(dpi(72f), dpi(72f)))
        val flip = roundIcon(R.drawable.ic_flip, 44f, "Switch camera") {
            front = !front; if (front && flashMode == ImageCapture.FLASH_MODE_AUTO) flashMode = ImageCapture.FLASH_MODE_OFF; tracker.clear(); trackState = null; overlay.target = null; tapFocus = null; bindCamera()
        }
        bar.addView(flip)
        val settings = roundIcon(R.drawable.ic_settings, 44f, "Settings") { openSettings() }
        bar.addView(settings)
        return bar
    }

    /**
     * Places everything for this screen: the 3:4 viewfinder under the status bar, the controls block under it
     * (tabs +12, selector +53, contextual area +125), and the shutter row with its shutter ending at the
     * navigation area, as in the Figma frame. Short screens first slide the viewfinder up under the status bar,
     * and only then shrink it; tall screens give the extra to the contextual area.
     */
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
        // zoom: centred, its bottom edge 10dp above the viewfinder's
        (zoomRow.layoutParams as FrameLayout.LayoutParams).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; topMargin = vfBottom - dpi(41f); width = wrap; height = dpi(31f) }
        zoomRow.requestLayout()
        at(tabRow, dpi(16f), vfBottom + dpi(12f), w - dpi(32f), dpi(31f))
        at(selector, 0, vfBottom + dpi(53f), w, dpi(65f))
        val ctxTop = vfBottom + dpi(128f)
        val ctxH = max(0, rowTop + dpi(12f) - ctxTop)
        at(ctxArea, dpi(16f), ctxTop, w - dpi(32f), ctxH)
        lookControls.available = ctxH
        at(evBar, dpi(16f), vfBottom - dpi(41f), w - dpi(32f), dpi(31f))
        at(shutterRow, 0, rowTop, w, h - rowTop)
        // the shutter row: last shot, import, shutter, flip, settings (Figma x 16 / 86 / 156 / 254 / 324 on 384)
        val cx = w / 2
        val kids = (0 until shutterRow.childCount).map { shutterRow.getChildAt(it) }
        at(kids[0], dpi(16f), dpi(26f), dpi(44f), dpi(44f))
        at(kids[1], cx - dpi(84f) - dpi(22f), dpi(26f), dpi(44f), dpi(44f))
        at(kids[2], cx - dpi(36f), dpi(12f), dpi(72f), dpi(72f))
        at(kids[3], cx + dpi(84f) - dpi(22f), dpi(26f), dpi(44f), dpi(44f))
        at(kids[4], w - dpi(60f), dpi(26f), dpi(44f), dpi(44f))
        settingsView?.let { applySettingsInsets(it) }
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
        if (art == ArtMode.TILT_SHIFT) { placeTiltOnGuide(); applyArtUi(); applyLiveArt() }   // the guide's values need the frame
    }

    // =========================================================== layers
    private fun applyLayer() {
        for (i in 0 until tabRow.childCount) {
            val t = tabRow.getChildAt(i) as TextView; val on = t.tag == layer
            t.setTextColor(if (on) T.GRAPHITE else T.IVORY)
            t.typeface = if (on) T.semibold else T.medium
            t.background = if (on) pill(T.AMBER, dp(24f)) else null
        }
        val c = layer == "COMPOSE"; val a = layer == "ART"; val e = false
        composeScroller.visibility = if (c) View.VISIBLE else View.GONE
        lookScroller.visibility = if (a) View.VISIBLE else View.GONE
        selector.visibility = if (e) View.GONE else View.VISIBLE
        composeSub.visibility = if (c) View.VISIBLE else View.GONE
        followBtn.visibility = if (c) View.VISIBLE else View.GONE
        lookControls.visibility = if (a) View.VISIBLE else View.GONE
        ctxArea.visibility = if (e) View.GONE else View.VISIBLE
        if (c) composeTiles[guide]?.let { composeScroller.reveal(it, smooth = false) }
        if (a) lookTiles[art]?.let { lookScroller.reveal(it, smooth = false) }
    }

    // ---- double exposure
    private fun syncDoubleBtn() {
        setActive(doubleBtn, doubleBtn, double)
        doubleBtn.contentDescription = if (double) "Double exposure on" else "Double exposure off"
        exposureTag.visibility = if (double) View.VISIBLE else View.GONE
        exposureTag.text = if (firstUri == null) "Exposure 1 of 2" else "Exposure 2 of 2  ✕"
    }

    /** Off: turns double exposure on. On: opens its options (blend, strength, first frame, turn off). */
    private fun onDoubleBtn() {
        if (!double) {
            double = true; firstUri?.let { loadFirst(it) }
            save(); syncDoubleBtn(); applyGhost()
            Toast.makeText(this, "Double exposure on · tap again for blend options", Toast.LENGTH_SHORT).show()
        } else showDoubleSheet()
    }

    private fun turnOffDouble() {
        double = false; ghostBmp = null
        save(); syncDoubleBtn(); applyGhost()
    }

    private fun showDoubleSheet() {
        sheet { box, dlg ->
            box.addView(label("Double exposure", 17f, cText, T.semibold))
            box.addView(label(if (firstUri == null) "The next photo is the first exposure; the one after is laid over it." else "The first exposure floats over the viewfinder. The next photo completes the pair.", 13f, cMuted).apply { setPadding(0, dpi(6f), 0, dpi(16f)) })
            box.addView(label("BLEND", 10f, cGold, T.medium, 0.08f))
            val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(8f), 0, dpi(14f)) }
            fun paint() { for (i in 0 until modes.childCount) { val t = modes.getChildAt(i) as TextView; val on = t.tag == blend
                t.setTextColor(if (on) T.AMBER else T.IVORY); t.background = pill(T.CARBON, dp(13f), if (on) T.AMBER else T.BORDER, dpi(1f)) } }
            for (b in Blend.values()) modes.addView(text(b.label, 11f, T.IVORY, T.medium, caps = true).apply {
                tag = b; gravity = Gravity.CENTER; setPadding(dpi(10f), 0, dpi(10f), 0)
                setOnClickListener { blend = b; save(); applyGhost(); paint() }
            }, LinearLayout.LayoutParams(wrap, dpi(26f)).apply { rightMargin = dpi(8f) })
            paint(); box.addView(modes)
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            head.addView(label("STRENGTH", 10f, cGold, T.medium, 0.08f)); head.addView(spacer())
            val sv = label("${(blendStrength * 100).roundToInt()}%", 11f, cGold, T.medium); head.addView(sv)
            box.addView(head)
            box.addView(ValueSlider(this).apply { value = blendStrength
                onChange = { v -> blendStrength = v; sv.text = "${(v * 100).roundToInt()}%"; applyGhost() }
                onStop = { save() } }, LinearLayout.LayoutParams(match, dpi(18f)).apply { topMargin = dpi(10f); bottomMargin = dpi(18f) })
            if (firstUri == null) box.addView(sheetButton("Use a photo as the first exposure", false) {
                dlg.dismiss(); pickFirst.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
            else box.addView(sheetButton("Discard the first exposure", false) { discardFirst(); dlg.dismiss() },
                LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
            box.addView(sheetButton("Turn off double exposure", false) { turnOffDouble(); dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { bottomMargin = dpi(8f) })
            box.addView(sheetButton("Done", true) { dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap))
        }
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
        coachBtn.background = pill(T.CARBON, dp(16f), if (coach) T.AMBER else null, dpi(1f))
        coachBtn.setTextColor(if (coach) T.AMBER else T.IVORY)
        coachBtn.contentDescription = if (coach) "Coach on" else "Coach off"
    }

    private fun flashLabel(): String = when {
        flashMode == ImageCapture.FLASH_MODE_OFF -> "Flash off"
        front -> "Screen flash on"
        flashMode == ImageCapture.FLASH_MODE_AUTO -> "Flash auto"
        else -> "Flash on"
    }

    private fun syncFlashBtn() {
        val on = flashMode != ImageCapture.FLASH_MODE_OFF
        setActive(flashBtn, flashIcon, on)
        flashAuto.visibility = if (flashMode == ImageCapture.FLASH_MODE_AUTO) View.VISIBLE else View.GONE
        flashBtn.contentDescription = flashLabel()
    }

    /** Hands the chosen flash to the camera: the real flash on the back, the screen as a flash on the front. */
    private fun applyFlash() {
        val cap = imageCapture ?: return
        runCatching {
            if (front) {
                if (flashMode == ImageCapture.FLASH_MODE_ON) { cap.screenFlash = screenFlash.screenFlash; cap.flashMode = ImageCapture.FLASH_MODE_SCREEN }
                else cap.flashMode = ImageCapture.FLASH_MODE_OFF
            } else {
                cap.flashMode = ImageCapture.FLASH_MODE_OFF   // the back camera's flash is the torch, lit around the shot (see capture)
            }
        }
    }

    private fun cycleFlash() {
        if (!front && camera?.cameraInfo?.hasFlashUnit() == false) {
            Toast.makeText(this, "This camera has no flash", Toast.LENGTH_SHORT).show(); return
        }
        // Off → On → Auto, so the first tap does what the icon promises. The front camera has no Auto: Off ↔ screen flash.
        flashMode = if (front) { if (flashMode == ImageCapture.FLASH_MODE_ON) ImageCapture.FLASH_MODE_OFF else ImageCapture.FLASH_MODE_ON }
            else when (flashMode) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON; ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO; else -> ImageCapture.FLASH_MODE_OFF }
        applyFlash()
        syncFlashBtn()
        Haptics.neutral(flashBtn)
        Toast.makeText(this, flashLabel(), Toast.LENGTH_SHORT).show()
    }

    // ---- compose
    private fun applyGuide() {
        overlay.guide = guide; overlay.quadrant = quadrant; overlay.triFlip = triFlip; overlay.align = align
        overlay.hidden = false
        if (::preview.isInitialized) overlay.post { applyLiveArt() }
        for ((g, t) in composeTiles) t.chosen = g == guide
        composeSub.removeAllViews()
        // only guides with real alternatives get a submenu; the space stays empty otherwise
        val alts: List<Triple<String, Int, () -> Unit>> = when (guide) {
            Guide.SPIRAL -> listOf("Top left" to R.drawable.or_spiral_tl, "Top right" to R.drawable.or_spiral_tr,
                "Bottom left" to R.drawable.or_spiral_bl, "Bottom right" to R.drawable.or_spiral_br).mapIndexed { q, (n, r) ->
                Triple(n, r) { quadrant = q; aligned = false; save(); applyGuide(); guideMoved() } }
            Guide.TRIANGLES -> listOf("Diagonal A" to R.drawable.or_tri_a, "Diagonal B" to R.drawable.or_tri_b).mapIndexed { i, (n, r) ->
                Triple(n, r) { triFlip = i == 1; aligned = false; save(); applyGuide(); guideMoved() } }
            else -> emptyList()
        }
        alts.forEachIndexed { i, (name, res, act) ->
            val sel = if (guide == Guide.SPIRAL) i == quadrant else (i == 1) == triFlip
            composeSub.addView(TileView(this, bmp(res), 48f, 35f, RectF(3f, 1f, 43f, 34f), 4f, T.GRAPHITE, name, 11f, crop = false).apply {
                chosen = sel; contentDescription = name; setOnClickListener { act() }
            }, LinearLayout.LayoutParams(wrap, wrap).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        syncFollow()
        overlay.invalidate(); updateHud()
    }

    /** The toggle's state and the chosen anchor's marker. Off and Crop have no focal point: the toggle dims. */
    private fun syncFollow() {
        followBtn.set(follow, GuideShape.hasAnchors(guide))
        overlay.selectedAnchor = if (followActive) anchor else -1
    }

    // ---- looks
    private fun selectLook(m: ArtMode) {
        if (!Art.supported && m != ArtMode.OFF) { Toast.makeText(this, "Looks need Android 13 or newer.", Toast.LENGTH_SHORT).show(); return }
        if (m == art) return
        // each look keeps its own settings; Original just pauses the look, nothing is forgotten
        art = m; artState = Art.loadState(prefs, m); artParam = 0
        placeTiltOnGuide()
        overlay.squareGuide = art == ArtMode.HOLGA; overlay.invalidate(); updateHud()
        save(); applyArtUi(); applyLiveArt()
        lookTiles[m]?.let { lookScroller.reveal(it) }
    }

    private fun applyArtUi() {
        for ((m, t) in lookTiles) t.chosen = m == art
        artParam = artParam.coerceIn(0, (art.controls.size - 1).coerceAtLeast(0))
        lookControls.bind(art, artValues, artParam, guidedSlots())
    }

    private fun onArtSlider(v: Float) {
        if (tiltFollows && artSlot == 1) manualTilt()
        artValues[artSlot] = v
        // Angle changed while following: the band turns about the guide's anchor, so it still runs through it
        if (tiltFollows && artSlot == 3) guideCtx()?.let { Focus.linked(it) }?.let { a -> placeTilt(a.u, a.v, null) }
        Art.saveState(prefs, art, artState)
        lookControls.setValue(v)
        applyLiveArt()
        if (art.isFocusSlider(artSlot)) showMask()
    }

    /** Nothing is locked: Follow guide places Tilt Shift's band by moving its own Focus and Angle. */
    private fun guidedSlots(): Map<Int, Float> = emptyMap()

    /**
     * Puts Tilt Shift's band through a point (frame uv), keeping it a straight band: Focus is where the band
     * offsets it perpendicular to its Angle; Angle becomes the guide's line only when the guide has an explicit,
     * unambiguous one (the triangles' diagonal, a dynamic-symmetry diagonal), otherwise it stays yours.
     */
    private fun placeTilt(u: Float, v: Float, angle: Float?) {
        val fr = overlay.frame
        if (fr.isEmpty) return
        if (angle != null) {
            var t = angle; while (t > 1.5707964f) t -= 3.1415927f; while (t < -1.5707964f) t += 3.1415927f
            artValues[3] = (t / 1.5707964f + 0.5f).coerceIn(0f, 1f)
        }
        val ang = (artValues[3] - 0.5f) * 1.5707964f            // Angle's calibration is linear around 50%
        artValues[1] = Focus.tiltFocusThrough(u, v, ang, fr.width() / fr.height())
        Art.saveState(prefs, art, artState)
    }

    /** Follow guide on: move Tilt Shift's band onto the guide's chosen anchor (and along its line, if any). */
    private fun placeTiltOnGuide() {
        if (!tiltFollows) return
        val a = guideCtx()?.let { Focus.linked(it) } ?: return
        placeTilt(a.u, a.v, a.angle)
    }

    private fun guideCtx(): GuideCtx? {
        val fr = overlay.frame
        if (fr.isEmpty) return null
        return GuideCtx(guide, quadrant, triFlip, anchor, RectF(fr), RectF(overlay.guideFrame))
    }

    /** The mask the look uses right now (frame uv). */
    private fun liveMask(): FocusMask {
        // a tapped point follows the subject while the tracker holds it, and stays put if it loses it
        val tap = tapFocus?.let { liveSubject() ?: it }
        val fr = overlay.frame
        return Focus.resolve(art, artValues, followActive, guideCtx(), liveSubject(), tap, if (fr.isEmpty) 0.75f else fr.width() / fr.height())
    }

    /** The guide changed: a guide-linked focus moves with it (and shows briefly); a manual one stays put. */
    private fun guideMoved() {
        tapFocus = null
        placeTiltOnGuide()
        focusOnAnchor()
        syncFollow()
        if (art != ArtMode.OFF && art.follows && followActive) { applyLiveArt(); showMask() }
        if (art == ArtMode.TILT_SHIFT) applyArtUi()
    }

    /** Briefly outlines the simulated focus region: never part of the photo. */
    private fun showMask() {
        if (art == ArtMode.OFF) return
        val m = liveMask()
        if (m.type == 0 || m.strength <= 0f && art != ArtMode.TILT_SHIFT) return
        overlay.showMask(m)
    }

    private var lastFocus = PointF(0.5f, 0.5f)

    /** The subject you tapped (0..1 across the frame), followed as you move. Null before any tap. */
    private fun liveSubject(): PointF? {
        val fr = overlay.frame
        if (fr.isEmpty) return null
        val t = overlay.target; val st = trackState
        return if (t != null && st != null && !st.lost) PointF((t.x - fr.left) / fr.width(), (t.y - fr.top) / fr.height()) else null
    }
    private fun liveFocus(): PointF = liveMask().let { PointF(it.cx, it.cy) }

    /** The look runs on the preview itself, so the viewfinder shows the finished look. */
    private fun applyLiveArt() {
        if (!::preview.isInitialized) return
        if (!Art.supported || art == ArtMode.OFF || overlay.frame.isEmpty) { if (Build.VERSION.SDK_INT >= 31) camLayer.setRenderEffect(null); liveShader = null; return }
        if (Build.VERSION.SDK_INT < 33) return
        val f = overlay.frame
        if (art == ArtMode.TILT_SHIFT) {
            // the layered miniature blur, built exactly as saved photos build it (Art.render → TiltFx)
            val fx = liveTilt ?: TiltFx.create().also { liveTilt = it }
            if (fx != null) {
                lastFocus = liveFocus()
                camLayer.setRenderEffect(fx.effect(artValues, f.left, f.top, f.width(), f.height(), liveSeed, liveMask()))
                return
            }
        }
        val sh = liveShader?.takeIf { it === liveFor(art) } ?: Art.newShader(art).also { liveShader = it; liveMode = art }
        if (sh == null) { camLayer.setRenderEffect(null); return }
        val fp = liveFocus(); lastFocus = fp
        Art.setUniforms(sh, art, artValues, f.left, f.top, f.width(), f.height(), liveSeed, liveMask())
        camLayer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(sh, "content"))   // live picture and first exposure together
    }
    private var liveMode: ArtMode? = null
    private var liveTilt: TiltFx? = null
    private fun liveFor(m: ArtMode): RuntimeShader? = if (liveMode == m) liveShader else null

    // ---- exposure
    /**
     * The exposure dial over the bottom of the viewfinder, in the zoom pill's place: −2 … +2 EV with the
     * whole stops marked, the value (tap it, or double-tap the dial, for 0) and a close button. It closes
     * itself a few seconds after you stop adjusting.
     */
    private fun buildEvBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(12f), 0, dpi(4f), 0)
            background = pill(T.alpha(T.GRAPHITE, 0.92f), dp(16f)); visibility = View.GONE
            isClickable = true
        }
        bar.addView(text("−2", 11f, T.IVORY, T.medium))
        evSlider = EvSlider(this).apply {
            contentDescription = "Exposure compensation"
            onChange = { ev -> onEvSlider(ev); scheduleEvClose() }
            onDoubleTap = { setEv(0); Haptics.neutral(this); scheduleEvClose() }
        }
        bar.addView(evSlider, LinearLayout.LayoutParams(0, dpi(28f), 1f).apply { leftMargin = dpi(8f); rightMargin = dpi(8f) })
        bar.addView(text("+2", 11f, T.IVORY, T.medium))
        evValue = text("0.0", 12f, T.AMBER, T.medium).apply {
            gravity = Gravity.CENTER; minWidth = dpi(34f); contentDescription = "Reset exposure to 0"
            setOnClickListener { setEv(0); Haptics.neutral(this); scheduleEvClose() }
        }
        bar.addView(evValue, LinearLayout.LayoutParams(wrap, match).apply { leftMargin = dpi(6f) })
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close); scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Close exposure"
            setPadding(dpi(6f), dpi(6f), dpi(6f), dpi(6f)); setOnClickListener { closeEv() }
        }, LinearLayout.LayoutParams(dpi(28f), dpi(28f)))
        return bar
    }

    private val evCloser = Runnable { closeEv() }
    private fun scheduleEvClose() { if (shot != null) return; root.removeCallbacks(evCloser); root.postDelayed(evCloser, 4000) }

    private fun openEv() {
        if (!evSupported) return
        zoomRow.visibility = View.INVISIBLE; evBar.visibility = View.VISIBLE
        scheduleEvClose()
    }

    private fun closeEv() {
        root.removeCallbacks(evCloser)
        evBar.visibility = View.GONE
        zoomRow.visibility = if (zoomRow.childCount > 0) View.VISIBLE else View.GONE
    }

    /** The chip reads "EV" at 0, and the value in Amber once you've changed it, so a leftover offset is never hidden. */
    private fun syncEvChip() {
        if (!::evChip.isInitialized) return
        val show = evSupported && camera != null
        evChip.visibility = if (show) View.VISIBLE else View.GONE
        evDivider.visibility = if (show && zoomRow.childCount > 2) View.VISIBLE else View.GONE
        val zero = evIndex == 0
        evChip.text = if (zero) "EV" else evShort()
        evChip.setTextColor(if (zero) T.IVORY else T.AMBER)
        evChip.contentDescription = "Exposure ${evText()}"
        if (zoomRow.visibility == View.GONE && show && evBar.visibility != View.VISIBLE) zoomRow.visibility = View.VISIBLE
    }

    private fun evShort() = String.format(Locale.US, "%+.1f", evIndex * evStep).replace("+0.0", "0.0").replace("-", "−")

    private fun evText() = String.format(Locale.US, "%+.1f EV", evIndex * evStep).replace("+0.0", "0.0").replace("-", "−")

    private fun refreshExposure() {
        val st = camera?.cameraInfo?.exposureState ?: return
        if (!st.isExposureCompensationSupported) { evSupported = false; closeEv(); syncEvChip(); return }
        evSupported = true; evSlider.isEnabled = true; evSlider.alpha = 1f
        evStep = st.exposureCompensationStep.toFloat()
        val r = st.exposureCompensationRange
        // offer up to ±2 EV, or the camera's own limit if that's smaller
        evMin = max(r.lower, (-2f / evStep).roundToInt()); evMax = min(r.upper, (2f / evStep).roundToInt())
        evIndex = st.exposureCompensationIndex.coerceIn(evMin, evMax)
        evSlider.ev = evIndex * evStep
        evValue.text = evShort()
        syncEvChip()
    }

    /** The slider reports EV; it snaps to the camera's own step and limits. */
    private fun onEvSlider(ev: Float) { val i = (ev / evStep).roundToInt(); if (i != evIndex) setEv(i) else evSlider.ev = evIndex * evStep }

    private fun setEv(index: Int) {
        val cam = camera ?: return
        if (!evSupported) return
        evIndex = index.coerceIn(evMin, evMax)
        cam.cameraControl.setExposureCompensationIndex(evIndex)
        evSlider.ev = evIndex * evStep
        evValue.text = evShort()
        syncEvChip()
        save()
    }

    private fun gate(msg: String) { gateMsg.text = msg; gateMsg.visibility = View.VISIBLE }

    // =========================================================== settings
    private fun applySettingsInsets(v: View) {
        val lp = v.layoutParams as FrameLayout.LayoutParams
        lp.width = match; lp.height = match; v.layoutParams = lp
        v.findViewWithTag<View>("settingsHeader")?.let { (it.layoutParams as LinearLayout.LayoutParams).topMargin = insetTop + dpi(8f); it.requestLayout() }
        v.findViewWithTag<View>("settingsBody")?.setPadding(dpi(16f), dpi(20f), dpi(16f), insetBottom + dpi(24f))
    }

    private fun card() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pill(T.GRAPHITE, dp(12f)) }

    private fun section(parent: LinearLayout, title: String, first: Boolean = false): LinearLayout {
        parent.addView(text(title, 11f, T.AMBER, T.medium, caps = true).apply { lineHeight = dpi(15.4f) }, LinearLayout.LayoutParams(wrap, wrap).apply { if (!first) topMargin = dpi(24f) })
        val c = card(); parent.addView(c, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(10f) }); return c
    }

    private fun switchRow(title: String, desc: String, on: Boolean, toggled: (Boolean, View) -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), dpi(14f), dpi(12f), dpi(14f)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 14f, T.IVORY, T.medium).apply { lineHeight = dpi(19.6f) })
        col.addView(text(desc, 12f, T.SUBTLE, T.regular).apply { maxLines = 3; lineHeight = dpi(16.8f) }, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(4f) })
        row.addView(col, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dpi(12f) })
        val sw = SwitchView(this).apply { checked = on; contentDescription = title; onToggle = { v -> toggled(v, this) } }
        row.addView(sw)
        row.minimumHeight = dpi(82f)
        return row
    }

    private fun actionCard(title: String, icon: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), 0, dpi(16f), 0)
        background = pill(T.GRAPHITE, dp(12f))
        addView(text(title, 13f, T.IVORY, T.medium), LinearLayout.LayoutParams(0, wrap, 1f))
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dpi(18f), dpi(18f)))
        setOnClickListener { onClick() }
    }

    private fun openSettings() {
        if (settingsView != null) return
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(T.CARBON); isClickable = true }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; tag = "settingsHeader"; setPadding(dpi(6f), 0, dpi(16f), 0) }
        head.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back to camera"
            setOnClickListener { closeSettings() }
        }, LinearLayout.LayoutParams(dpi(44f), dpi(48f)))
        head.addView(text("Settings", 22f, T.IVORY, T.semibold), LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(2f) })
        page.addView(head, LinearLayout.LayoutParams(match, dpi(48f)))
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "settingsBody" }
        scroll.addView(body)
        page.addView(scroll, LinearLayout.LayoutParams(match, 0, 1f))

        section(body, "Photos", first = true).addView(switchRow("Save original copy", "Keep an unfiltered copy when a Look is applied.", saveOriginal) { v, _ -> saveOriginal = v; save() })
        val cam = section(body, "Camera")
        cam.addView(switchRow("Remember my settings", "Restore the last Look, guide and exposure value.", remember) { v, _ -> remember = v; save() })
        cam.addView(View(this).apply { setBackgroundColor(T.alpha(T.CARBON, 0.7f)) }, LinearLayout.LayoutParams(dpi(320f), dpi(1f)))
        cam.addView(switchRow("Haptic feedback", "Use subtle vibrations when adjusting controls.", Haptics.enabled) { v, sw -> Haptics.enabled = v; save(); if (v) Haptics.confirm(sw) })

        val g = section(body, "Composition guides")
        val gBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dpi(16f), dpi(16f), dpi(16f), dpi(16f)) }
        val gHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        gHead.addView(text("Guide opacity", 14f, T.IVORY, T.medium).apply { lineHeight = dpi(19.6f) }); gHead.addView(spacer())
        val pct = text("${(guideOpacity * 100).roundToInt()}%", 12f, T.AMBER, T.medium); gHead.addView(pct)
        gBox.addView(gHead, LinearLayout.LayoutParams(match, dpi(20f)))
        gBox.addView(text("Keep guides visible without obscuring your photo.", 12f, T.SUBTLE, T.regular).apply { lineHeight = dpi(16.8f); maxLines = 2 }, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(12f) })
        gBox.addView(plainSlider(this).apply {
            value = guideOpacity; contentDescription = "Guide opacity"
            onChange = { v -> guideOpacity = v; pct.text = "${(v * 100).roundToInt()}%"; this@MainActivity.overlay.guideOpacity = v }
            onStop = { save() }
        }, LinearLayout.LayoutParams(match, dpi(22f)).apply { topMargin = dpi(12f) })
        g.addView(gBox)

        body.addView(actionCard("Reset camera settings", R.drawable.ic_reset_cam) { confirmReset() }, LinearLayout.LayoutParams(match, dpi(48f)).apply { topMargin = dpi(24f) })
        body.addView(actionCard("About Crooked Light", R.drawable.ic_chevron) { showAbout() }, LinearLayout.LayoutParams(match, dpi(48f)).apply { topMargin = dpi(10f) })

        root.addView(page, lp(match, match))
        settingsView = page
        applySettingsInsets(page)
    }

    private fun closeSettings() { settingsView?.let { root.removeView(it) }; settingsView = null }

    /** Clears camera preferences (guide, Looks, exposure, toggles). Photos and their saved Look recipes are untouched. */
    private fun confirmReset() {
        sheet { box, dlg ->
            box.addView(label("Reset camera settings?", 17f, cText, T.semibold))
            box.addView(label("Guides, Looks, exposure and these preferences go back to their defaults. Your photos aren't affected.", 13f, cMuted).apply { setPadding(0, dpi(8f), 0, dpi(16f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(sheetButton("Cancel", false) { dlg.dismiss() }, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dpi(10f) })
            row.addView(sheetButton("Reset", true) {
                dlg.dismiss()
                val keep = setOf("lastUri", "thumbUri", "firstUri")
                val e = prefs.edit()
                for (k in prefs.all.keys) if (k !in keep && !k.startsWith("art:") && !k.startsWith("upd")) e.remove(k)
                e.putBoolean("double", false).commit()
                camera?.cameraControl?.setExposureCompensationIndex(0)
                prefs.edit().remove("firstUri").putFloat("ev", 0f).commit()
                recreate()
            }, LinearLayout.LayoutParams(0, wrap, 1f))
            box.addView(row)
        }
    }

    private var settingsStatus: TextView? = null
    private var settingsAction: TextView? = null

    private fun showAbout() {
        sheet { box, dlg ->
            box.addView(label("Crooked Light", 17f, cText, T.semibold))
            box.addView(label("Version ${Updater.installedName(this)} (build ${Updater.installedCode(this)})", 13f, cMuted).apply { setPadding(0, dpi(6f), 0, dpi(14f)) })
            if (!Updater.enabled) {
                box.addView(label("Updates come through Google Play.", 12f, cMuted).apply { setPadding(0, 0, 0, dpi(12f)) })
            } else {
                settingsStatus = label("", 12.5f, cGold).apply { setPadding(0, 0, 0, dpi(12f)) }
                box.addView(settingsStatus)
                settingsAction = sheetButton("Check for updates", false) { onSettingsAction() }
                box.addView(settingsAction, LinearLayout.LayoutParams(match, wrap))
                onUpdateStatus(Updater.status)
            }
            box.addView(sheetButton("Done", true) { dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(8f) })
            dlg.setOnDismissListener { settingsStatus = null; settingsAction = null }
        }
    }

    // =========================================================== sheets
    private var lastTouch = 0L
    private var offered: Updater.Release? = null      // the release waiting for a quiet moment to be offered
    private var pendingInstall: java.io.File? = null  // a verified APK waiting on the "install unknown apps" permission

    /** A dark sheet in the app's own style. */
    private fun sheet(build: (LinearLayout, android.app.Dialog) -> Unit): android.app.Dialog {
        val dlg = android.app.Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(20f), dpi(20f), dpi(20f), dpi(16f))
            background = rounded(T.GRAPHITE, null, 16f)
        }
        build(box, dlg)
        dlg.setContentView(box)
        dlg.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
        return dlg
    }
    private fun sheetButton(text: String, primary: Boolean, onClick: () -> Unit) = label(text, 14f, if (primary) T.CARBON else T.IVORY, T.medium).apply {
        gravity = Gravity.CENTER; setPadding(dpi(16f), dpi(12f), dpi(16f), dpi(12f))
        background = if (primary) rounded(T.AMBER, null, 12f) else rounded(T.CARBON, T.BORDER, 12f)
        setOnClickListener { onClick() }
    }


    // =========================================================== camera
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
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .build()
        imageCapture = cap
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43)
                .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { img ->
            sceneLuma = runCatching { lumaOf(img) }.getOrDefault(sceneLuma)
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
        applyFlash(); syncFlashBtn()
        chipsFor = ""
        lensStops = camera?.let { measureLenses(it) }
        camera?.cameraInfo?.zoomState?.observe(this) { z -> onZoom(z) }
        refreshExposure()
        if (evSupported) {
            // restore the remembered (or current) exposure, snapped to this camera's steps
            val want = if (evWanted != 0f) evWanted else evIndex * evStep
            evWanted = 0f
            evIndex = (want / evStep).roundToInt().coerceIn(evMin, evMax)
            camera?.cameraControl?.setExposureCompensationIndex(evIndex)
            evSlider.ev = evIndex * evStep; evValue.text = evShort(); syncEvChip()
        }
        hintUntil = SystemClock.uptimeMillis() + 4000
        updateHud(); applyLiveArt()
        overlay.post { focusOnAnchor() }          // Follow guide: the camera starts focused on the target
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
        val stops = zoomStops().let { if (it.size < 2) emptyList() else it }
        stops.forEachIndexed { i, s ->
            val b = text(zoomLabel(s), 12f, T.IVORY, T.medium).apply {
                gravity = Gravity.CENTER; tag = s
                contentDescription = "Zoom ${zoomLabel(s)}"
                setOnClickListener { camera?.cameraControl?.setZoomRatio(s) }
            }
            zoomRow.addView(b, LinearLayout.LayoutParams(dpi(44f), dpi(27f)).apply { if (i > 0) leftMargin = dpi(8f) })
        }
        // exposure: a divider and a chip at the end of the pill
        evDivider = View(this).apply { setBackgroundColor(T.BORDER) }
        zoomRow.addView(evDivider, LinearLayout.LayoutParams(dpi(1f), dpi(17f)).apply { leftMargin = dpi(6f) })
        evChip = text("EV", 12f, T.IVORY, T.medium).apply {
            gravity = Gravity.CENTER; minWidth = dpi(38f); setPadding(dpi(6f), 0, dpi(4f), 0)
            setOnClickListener { openEv() }
        }
        zoomRow.addView(evChip, LinearLayout.LayoutParams(wrap, dpi(27f)))
        if (stops.isEmpty()) evDivider.visibility = View.GONE
        if (evBar.visibility != View.VISIBLE) zoomRow.visibility = View.VISIBLE
        syncEvChip()
    }

    /**
     * The chip you're on, or the one just below where a pinch has landed, lights up and shows the
     * live value ("1.4×"), so pinching tells you where you are. Tapping any chip snaps back to it.
     */
    private fun syncZoomChips() {
        var active: TextView? = null
        val stops = (0 until zoomRow.childCount).map { zoomRow.getChildAt(it) }.filter { it.tag is Float }.map { it as TextView }
        for (v in stops) if ((v.tag as Float) <= zoomRatio + 0.02f) active = v
        if (active == null) active = stops.firstOrNull()
        for (v in stops) {
            val on = v === active
            val stop = v.tag as Float
            v.text = if (on && abs(stop - zoomRatio) > 0.04f) zoomLabel(zoomRatio) else zoomLabel(stop)
            v.setTextColor(if (on) T.GRAPHITE else T.IVORY)
            v.typeface = if (on) T.semibold else T.medium
            v.background = if (on) pill(T.AMBER, dp(16f)) else null
        }
    }

    // =========================================================== gestures
    // =========================================================== gestures
    override fun onSubjectTap(sx: Float, sy: Float) {
        val fr = overlay.frame
        if (!fr.contains(sx, sy)) return
        run {
            // The tap names the subject to follow, so the phone can buzz when it lands on the guide.
            // In Coach, tapping the marked subject again lets it go; without Coach there's nothing to tap, so a tap always re-aims.
            val cur = overlay.target
            if (coach && cur != null && trackState?.lost != true && Geo.dist(cur, PointF(sx, sy)) < dp(28f)) {
                tracker.clear(); trackState = null; overlay.target = null; aligned = false; tapFocus = null; overlay.invalidate(); updateHud(); applyLiveArt()
            } else {
                var nx = (sx - fr.left) / fr.width(); val ny = (sy - fr.top) / fr.height()
                if (front) nx = 1f - nx                                   // the preview is mirrored, the analysis frames aren't
                tracker.target(nx, ny)
                aligned = false
                focusAt(sx, sy)
            }
        }
        // Follow guide on: the camera focuses on the guide's target, not wherever the tap landed
        if (lookFollows) focusOnAnchor() else requestFocus(sx, sy)
    }

    /** Moving Tilt Shift's Focus by hand turns Follow guide off; the band stays exactly where it was. */
    private fun manualTilt() {
        follow = false; save(); syncFollow()
        Toast.makeText(this, "Follow guide off: the band is yours to move", Toast.LENGTH_SHORT).show()
    }

    /** Optical autofocus on the active guide anchor (Follow guide on). Asked once per change, not per frame. */
    private fun focusOnAnchor() {
        if (!lookFollows || camera == null) return
        val gf = overlay.guideFrame
        if (gf.isEmpty) return
        val sh = GuideShape.build(guide, Box(gf.left, gf.top, gf.right, gf.bottom), quadrant, triFlip, false, align)
        val a = sh.anchors.getOrNull(anchor.coerceIn(0, (sh.anchors.size - 1).coerceAtLeast(0))) ?: return
        requestFocus(a.p.x, a.p.y)
    }

    private var focusToken = 0

    /**
     * Optical autofocus at the tapped point. PreviewView's metering factory maps the tap through the
     * preview's crop, zoom, rotation and front-camera mirroring. Focus only (FLAG_AF): a tap no longer
     * re-meters exposure, which stays with the Exposure dial. The ring turns Amber only when the camera
     * confirms focus; a fixed-focus lens or a failed lock fades it grey.
     */
    private fun requestFocus(sx: Float, sy: Float) {
        val cam = camera ?: return
        val pt = preview.meteringPointFactory.createPoint(sx, sy)
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF).setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        overlay.showFocus(sx, sy)
        if (!cam.cameraInfo.isFocusMeteringSupported(action)) { overlay.focusResult(null); return }
        val token = ++focusToken
        val f = cam.cameraControl.startFocusAndMetering(action)
        f.addListener({
            if (token != focusToken) return@addListener           // a newer tap took over
            overlay.focusResult(runCatching { f.get().isFocusSuccessful }.getOrDefault(false))
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onDoubleTap() {
        when (guide) {
            Guide.SPIRAL -> { quadrant = (quadrant + 1) % 4; aligned = false; save(); applyGuide() }
            Guide.TRIANGLES -> { triFlip = !triFlip; aligned = false; save(); applyGuide() }
            else -> {}
        }
    }

    /** In Art with Tilt Shift on (and not following the guide), a vertical drag on the picture moves the band. */
    override fun onFocusDrag(dy: Float): Boolean {
        if (layer != "ART" || art != ArtMode.TILT_SHIFT || overlay.frame.isEmpty) return false
        if (tiltFollows) manualTilt()
        artValues[1] = (artValues[1] + dy / overlay.frame.height()).coerceIn(0f, 1f)
        if (artSlot == 1) applyArtUi()
        Art.saveState(prefs, art, artState); applyLiveArt(); showMask()
        return true
    }
    override fun onFocusDragEnd() { overlay.fadeMask() }

    override fun onPinch(factor: Float) {
        // Tilt Shift in the Look tab: a second finger while you move the band must not zoom. Zoom stays on the pill.
        if (layer == "ART" && art == ArtMode.TILT_SHIFT) return
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

    private fun buzz() = Haptics.confirm(overlay)

    /**
     * Where a tap puts the Look's simulated sharp region (the camera's autofocus goes to the tap regardless).
     * Follow guide on: guides with several anchors take the one nearest the tap (remembered per guide);
     * single-anchor guides (spiral eye, centre) stay on their anchor. Follow guide off: the sharp region
     * goes to the tapped point; for Tilt Shift its band (Focus) moves so the line passes through the tap at
     * the current Angle. Global softness is part of a Look and isn't removed by tapping.
     */
    private fun focusAt(sx: Float, sy: Float) {
        val fr = overlay.frame
        if (fr.isEmpty) return
        val uv = PointF((sx - fr.left) / fr.width(), (sy - fr.top) / fr.height())
        if (lookFollows) {
            tapFocus = null
            val gf = overlay.guideFrame
            val sh = GuideShape.build(guide, Box(gf.left, gf.top, gf.right, gf.bottom), quadrant, triFlip, false, align)
            if (sh.anchors.size > 1) {
                val best = GuideShape.nearest(sh, Pt(sx, sy))
                if (best >= 0 && best != anchor) anchor = best
                syncFollow()
                placeTiltOnGuide()
            }
        } else if (art == ArtMode.TILT_SHIFT) {
            placeTilt(uv.x, uv.y, null); tapFocus = null
        } else tapFocus = uv
        applyLiveArt(); applyArtUi(); showMask()
    }

    /**
     * CI check of the simulated effect on a detailed test image, independent of the camera:
     * `render:MODE:GUIDE:variant:anchor:follow|-[:slot=percent,...]`. Renders the Look exactly as a saved
     * photo would be (Art.render with the mask the camera would use), then draws the guide on top of the
     * on-screen copy only, so the sharp region can be compared with the anchor.
     */
    /**
     * Look lab (CI only, via `-e fibShot batch`): renders every line of files/lab/spec.txt through the same
     * Art.render + Art.finish path a saved photo takes, and writes JPEGs to files/lab/out. Line format:
     * `name|fixture file|MODE|slot=percent,...` (slot 5 takes a variation index). Depth is 0 unless set.
     */
    private fun labBatch() {
        if (Build.VERSION.SDK_INT < 33) return
        val dir = getExternalFilesDir("lab") ?: return
        val spec = java.io.File(dir, "spec.txt")
        if (!spec.exists()) return
        Thread {
            val out = java.io.File(dir, "out").apply { mkdirs() }
            val cache = HashMap<String, Bitmap>()
            spec.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val f = line.split("|")
                runCatching {
                    val mode = ArtMode.valueOf(f[2])
                    val v = mode.defaults
                    if (mode != ArtMode.OFF) v[mode.depthSlot] = 0f
                    f.getOrNull(3)?.takeIf { it.isNotBlank() }?.split(",")?.forEach { kv ->
                        val (k, x) = kv.split("="); val i = k.toInt()
                        v[i] = if (i == 5) x.toFloat() else x.toFloat() / 100f
                    }
                    val src = cache.getOrPut(f[1]) {
                        android.graphics.BitmapFactory.decodeFile(java.io.File(dir, f[1]).path).copy(Bitmap.Config.ARGB_8888, false)
                    }
                    val m = Focus.resolve(mode, v, false, null, null, null, src.width.toFloat() / src.height)
                    val img = if (mode == ArtMode.OFF) src else Art.finish(Art.render(src, Recipe(mode, v, 0.37f, false, m)), mode)
                    java.io.FileOutputStream(java.io.File(out, f[0] + ".jpg")).use { img.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                    if (img !== src) img.recycle()
                }.onFailure { java.io.File(out, f[0] + ".err").writeText(it.toString()) }
            }
            java.io.File(dir, "done").writeText("ok")
        }.start()
    }

    private fun showRenderTest(spec: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val p = spec.split(":")
        val mode = runCatching { ArtMode.valueOf(p[1]) }.getOrDefault(ArtMode.HOLGA)
        val g = runCatching { Guide.valueOf(p[2]) }.getOrDefault(Guide.SPIRAL)
        val variant = p.getOrNull(3)?.toIntOrNull() ?: 0
        val anc = p.getOrNull(4)?.toIntOrNull() ?: 0
        val fol = p.getOrNull(5) == "follow"
        val v = mode.defaults
        p.getOrNull(6)?.split(",")?.forEach { kv -> val (k, x) = kv.split("="); v[k.toInt()] = x.toInt() / 100f }
        val w = 1200; val h = 1600
        val src = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(src)
        val pt = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        for (y in 0 until h step 8) for (x in 0 until w step 8) {
            pt.color = if (((x / 8) + (y / 8)) % 2 == 0) Color.rgb(40 + x * 180 / w, 60, 40 + y * 180 / h) else Color.rgb(235, 225 - y * 60 / h, 200 - x * 80 / w)
            c.drawRect(x.toFloat(), y.toFloat(), x + 8f, y + 8f, pt)
        }
        pt.color = Color.BLACK; pt.textSize = 30f; pt.typeface = T.semibold
        for (y in 60 until h step 110) for (x in 20 until w step 230) c.drawText("Aa 1:2 %", x.toFloat(), y.toFloat(), pt)
        pt.style = android.graphics.Paint.Style.STROKE; pt.strokeWidth = 2f
        for (i in 0..24) c.drawLine(0f, i * 70f, w.toFloat(), i * 70f + 40f, pt)
        val fr = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val gf = if (mode == ArtMode.HOLGA) RectF(0f, (h - w) / 2f, w.toFloat(), (h + w) / 2f) else RectF(fr)
        val q = if (g == Guide.SPIRAL) variant else 0; val flip = g != Guide.SPIRAL && variant == 1
        val ctx = GuideCtx(g, q, flip, anc, fr, gf)
        if (mode == ArtMode.TILT_SHIFT && fol) Focus.linked(ctx)?.let { a ->
            a.angle?.let { t0 -> var t = t0; while (t > 1.5707964f) t -= 3.1415927f; while (t < -1.5707964f) t += 3.1415927f; v[3] = t / 1.5707964f + 0.5f }
            v[1] = Focus.tiltFocusThrough(a.u, a.v, (v[3] - 0.5f) * 1.5707964f, w.toFloat() / h)
        }
        val m = Focus.resolve(mode, v, fol, ctx, null, null, w.toFloat() / h)
        val out = Art.render(src, Recipe(mode, v, 0.37f, fol, m))
        val shown = out.copy(Bitmap.Config.ARGB_8888, true)
        GuidePainter(w / 384f).apply { opacity = 0.9f }.draw(android.graphics.Canvas(shown), gf, g, q, flip, false, 1, if (fol) anc else -1)
        val iv = ImageView(this).apply { setImageBitmap(shown); scaleType = ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.BLACK) }
        root.addView(iv, lp(match, match))
        root.addView(text("$mode · $g $variant · anchor $anc · ${if (fol) "follow" else "no follow"} · ${p.getOrNull(6) ?: "defaults"}", 12f, T.IVORY, T.medium).apply {
            setBackgroundColor(T.alpha(T.CARBON, 0.8f)); setPadding(dpi(8f), dpi(6f), dpi(8f), dpi(6f))
        }, lp(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dpi(40f) })
    }

    // =========================================================== updates
    private fun onSettingsAction() {
        when (val st = Updater.status) {
            is Updater.Status.Available -> startDownload(st.r)
            is Updater.Status.Downloading -> confirmCancel()
            is Updater.Status.Ready -> installOrAsk(st.apk)
            is Updater.Status.Checking -> {}
            else -> checkForUpdate(manual = true)
        }
    }

    private fun checkForUpdate(manual: Boolean) {
        if (!Updater.enabled) return
        analysisExecutor.execute {
            val r = Updater.check(this, prefs, manual)
            runOnUiThread {
                if (r != null) { offered = r; if (manual) showUpdateOffer(r) else maybeOfferUpdate() }
            }
        }
    }

    /** Offers an update only at a quiet moment: the camera screen in front, and no touches for a few seconds. */
    private fun maybeOfferUpdate() {
        val r = offered ?: return
        if (isFinishing || !hasWindowFocus() || SystemClock.uptimeMillis() - lastTouch < 5000) {
            overlay.postDelayed({ maybeOfferUpdate() }, 4000); return
        }
        showUpdateOffer(r)
    }

    private fun showUpdateOffer(r: Updater.Release) {
        offered = null
        sheet { box, dlg ->
            box.addView(label("Update available", 17f, cText, T.semibold))
            box.addView(label("Crooked Light v${r.versionName} is ready to install.", 13f, cMuted).apply { setPadding(0, dpi(6f), 0, dpi(12f)) })
            if (r.notes.isNotEmpty()) {
                box.addView(label("WHAT'S NEW", 10f, cGold, mono, 0.13f))
                for (n in r.notes) box.addView(label("·  $n", 13f, cText).apply { setPadding(0, dpi(5f), 0, 0) })
            }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(18f), 0, 0) }
            row.addView(sheetButton("Not now", false) { Updater.dismiss(prefs, r); dlg.dismiss() },
                LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dpi(10f) })
            row.addView(sheetButton("Download update", true) { dlg.dismiss(); startDownload(r) }, LinearLayout.LayoutParams(0, wrap, 1.4f))
            box.addView(row)
        }
    }

    private fun startDownload(r: Updater.Release) {
        if (Updater.status is Updater.Status.Downloading) return
        Thread {
            val (apk, err) = Updater.download(this, r)
            runOnUiThread {
                when {
                    apk != null -> installOrAsk(apk)
                    err != null -> sheet { box, dlg ->
                        box.addView(label("Update not installed", 17f, cText, T.semibold))
                        box.addView(label(err, 13f, cMuted).apply { setPadding(0, dpi(8f), 0, dpi(16f)) })
                        box.addView(sheetButton("Open the download page instead", false) {
                            dlg.dismiss(); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                        }, LinearLayout.LayoutParams(match, wrap))
                        box.addView(sheetButton("Close", false) { dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(8f) })
                    }
                }
            }
        }.start()
    }

    private fun confirmCancel() {
        sheet { box, dlg ->
            box.addView(label("Stop the download?", 17f, cText, T.semibold))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dpi(18f), 0, 0) }
            row.addView(sheetButton("Keep going", false) { dlg.dismiss() }, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dpi(10f) })
            row.addView(sheetButton("Stop", true) { Updater.cancelDownload(); dlg.dismiss() }, LinearLayout.LayoutParams(0, wrap, 1f))
            box.addView(row)
        }
    }

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    /** Android has to allow installs from this app once. Explain, open the right setting, carry on when they're back. */
    private fun installOrAsk(apk: java.io.File) {
        if (canInstall()) { launchInstall(apk); return }
        pendingInstall = apk
        sheet { box, dlg ->
            box.addView(label("One permission", 17f, cText, T.semibold))
            box.addView(label("Android requires permission to install updates downloaded directly from Crooked Light. Enable this permission to continue.",
                13f, cMuted).apply { setPadding(0, dpi(8f), 0, dpi(16f)) })
            box.addView(sheetButton("Open settings", true) {
                dlg.dismiss()
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            }, LinearLayout.LayoutParams(match, wrap))
            box.addView(sheetButton("Not now", false) { pendingInstall = null; dlg.dismiss() }, LinearLayout.LayoutParams(match, wrap).apply { topMargin = dpi(8f) })
        }
    }

    /** Hands the verified APK to Android's own installer; the user confirms there. Nothing installs silently. */
    private fun launchInstall(apk: java.io.File) {
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.updates", apk)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }.onFailure {
            Toast.makeText(this, "Couldn't open the installer: opening the download page instead.", Toast.LENGTH_LONG).show()
            (Updater.status as? Updater.Status.Ready)?.let { st -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(st.r.url))) }
        }
    }

    private fun onUpdateStatus(st: Updater.Status) {
        val (text, action) = when (st) {
            is Updater.Status.Checking -> "Checking for updates…" to "Checking…"
            is Updater.Status.UpToDate -> "You're up to date." to "Check for updates"
            is Updater.Status.Failed -> "Unable to check for updates. Try again later." to "Check for updates"
            is Updater.Status.Available -> "Update available: v${st.r.versionName}" + (if (st.r.notes.isNotEmpty()) "\n" + st.r.notes.joinToString("\n") { "·  $it" } else "") to "Download update"
            is Updater.Status.Downloading -> "Downloading update…" + (if (st.pct >= 0) " ${st.pct}%" else "") to "Stop download"
            is Updater.Status.Ready -> "Update downloaded and checked: v${st.r.versionName}" to "Install update"
            else -> "" to "Check for updates"
        }
        settingsStatus?.text = text; settingsAction?.text = action
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
        pill.setTextColor(if (on) T.AMBER else T.IVORY)
        pill.background = pill(T.alpha(T.CARBON, 0.85f), dp(16f), if (on) T.AMBER else null, dpi(1f))
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
            put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
        }
        // The art look at the moment of the shot. The original is saved as-is; the look becomes a copy.
        // the frame is the whole photo, so the live mask (frame uv) is already in the photo's own coordinates
        val recipe = if (Art.supported && art != ArtMode.OFF) Recipe(art, artValues.copyOf(), liveSeed, followActive, Focus.toPhoto(liveMask(), deviceRot, overlay.frame.width() / overlay.frame.height())) else null
        val first = if (double) firstUri else null
        val takingFirst = double && first == null
        val blendNow = blend; val strengthNow = blendStrength
        // Selfies are saved the way the viewfinder showed them (mirrored), so the photo, its Look copy and
        // the focus mask all match what you framed. CameraX records the flip in the photo's EXIF orientation.
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = front })
            .build()
        // Flash on the back camera is lit by the torch: on, exposure settles on the lit scene, shoot, off.
        // The one-shot flash burst left Pixel photos black (the frame was exposed for a flash that fired too early).
        val torchFlash = !front && flashMode != ImageCapture.FLASH_MODE_OFF && camera?.cameraInfo?.hasFlashUnit() == true &&
            (flashMode == ImageCapture.FLASH_MODE_ON || sceneLuma < AUTO_FLASH_LUMA)
        if (torchFlash && torchShooting) return
        val shoot = { cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                if (torchFlash) endTorchFlash()
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
                if (torchFlash) endTorchFlash()
                Toast.makeText(this@MainActivity, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }) }
        if (!torchFlash) { shoot(); return }
        torchShooting = true
        cap.flashMode = ImageCapture.FLASH_MODE_OFF
        val ctl = camera?.cameraControl
        if (ctl == null) { endTorchFlash(); shoot(); return }
        ctl.enableTorch(true).addListener({ stage.postDelayed({ if (torchShooting) shoot() }, TORCH_SETTLE_MS) }, ContextCompat.getMainExecutor(this))
    }

    /** Average brightness of the latest analysis frame (0..1), for Auto flash. */
    @Volatile private var sceneLuma = 1f
    private var torchShooting = false
    private fun endTorchFlash() {
        torchShooting = false
        camera?.cameraControl?.enableTorch(false)
        applyFlash()
    }
    private fun lumaOf(img: androidx.camera.core.ImageProxy): Float {
        val pl = img.planes[0]; val buf = pl.buffer; val rs = pl.rowStride
        var sum = 0L; var n = 0
        var y = 0
        while (y < img.height) { var x = 0; while (x < img.width) { sum += buf.get(y * rs + x).toInt() and 0xFF; n++; x += 16 }; y += 16 }
        return if (n == 0) 1f else sum / (n * 255f)
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
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
                    put(MediaStore.Images.Media.RELATIVE_PATH, PHOTO_DIR)
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
                    prefs.edit().putString("thumbUri", dest.toString()).apply()
                    // Settings › Save original copy off: once the styled copy is safely written, the original goes
                    if (!saveOriginal) runCatching {
                        if (contentResolver.delete(original, null, null) > 0) {
                            if (lastUri == original) lastUri = dest
                            prefs.edit().putString("lastUri", dest.toString()).remove("art:$original").apply()
                        }
                    }
                    loadThumb()
                }
                    .onFailure { Toast.makeText(this, "Saved the original; the art copy failed: ${it.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun loadThumb() {
        if (!::thumb.isInitialized) return
        val uri = shownPhoto()
        if (uri == null) { thumb.setImageDrawable(null); return }
        saveExecutor.execute {
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
            runOnUiThread { thumb.setImageBitmap(bmp) }          // a photo deleted elsewhere leaves an empty frame
        }
    }

    /** The latest photo as it was saved: the Look copy if there is one, otherwise the shot itself. */
    private fun shownPhoto(): Uri? = prefs.getString("thumbUri", null)?.let { Uri.parse(it) } ?: lastUri

    // =========================================================== the last photo
    /**
     * Tapping the last-photo thumbnail: the photo full screen with Share, Edit and Delete. Every shot is
     * already saved when you take it; this is just what to do with it next.
     */
    private fun openViewer() {
        val uri = shownPhoto() ?: return
        if (viewerView != null) return
        val page = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val img = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Your last photo" }
        page.addView(img, lp(match, match).apply { topMargin = insetTop + dpi(56f); bottomMargin = insetBottom + dpi(88f) })
        page.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back); scaleType = ImageView.ScaleType.CENTER; contentDescription = "Back to camera"
            setOnClickListener { closeViewer() }
        }, lp(dpi(48f), dpi(48f)).apply { topMargin = insetTop + dpi(4f); leftMargin = dpi(6f) })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(T.CARBON) }
        fun action(icon: Int, name: String, act: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; contentDescription = name
            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
            addView(text(name, 12f, T.IVORY, T.medium), LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = dpi(6f) })
            setOnClickListener { act() }
        }
        bar.addView(action(R.drawable.ic_share, "Share") { sharePhoto(uri) }, LinearLayout.LayoutParams(0, match, 1f))
        bar.addView(action(R.drawable.ic_edit, "Edit") { closeViewer(); lastUri?.let { openCrop(it) } ?: openCrop(uri) }, LinearLayout.LayoutParams(0, match, 1f))
        bar.addView(action(R.drawable.ic_delete, "Delete") { confirmDelete() }, LinearLayout.LayoutParams(0, match, 1f))
        page.addView(bar, lp(match, insetBottom + dpi(88f), Gravity.BOTTOM).also { bar.setPadding(0, 0, 0, insetBottom) })
        root.addView(page, lp(match, match))
        viewerView = page
        val target = resources.displayMetrics.widthPixels
        saveExecutor.execute {
            val b = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri)) { dec, info, _ ->
                    val sz = info.size; val k = target.toFloat() / maxOf(1, minOf(sz.width, sz.height))
                    if (k < 1f) dec.setTargetSize((sz.width * k).roundToInt().coerceAtLeast(1), (sz.height * k).roundToInt().coerceAtLeast(1))
                }
            }.getOrNull()
            runOnUiThread { if (b != null) img.setImageBitmap(b) else { closeViewer(); Toast.makeText(this, "That photo isn't there any more.", Toast.LENGTH_SHORT).show(); clearLast() } }
        }
    }

    private fun closeViewer() { viewerView?.let { root.removeView(it) }; viewerView = null }

    private fun sharePhoto(uri: Uri) {
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    /** The photo and, if a Look was applied, its unfiltered original go together. */
    private fun shotUris(): List<Uri> = listOfNotNull(shownPhoto(), lastUri).distinct()

    private fun confirmDelete() {
        val both = shotUris().size > 1
        sheet { box, dlg ->
            box.addView(label("Delete this photo?", 17f, cText, T.semibold))
            box.addView(label(if (both) "It's removed from your gallery, along with its unfiltered original." else "It's removed from your gallery.", 13f, cMuted).apply { setPadding(0, dpi(8f), 0, dpi(16f)) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(sheetButton("Cancel", false) { dlg.dismiss() }, LinearLayout.LayoutParams(0, wrap, 1f).apply { rightMargin = dpi(10f) })
            row.addView(sheetButton("Delete", true) { dlg.dismiss(); deleteShot() }, LinearLayout.LayoutParams(0, wrap, 1f))
            box.addView(row)
        }
    }

    private fun deleteShot() {
        val left = ArrayList<Uri>()
        for (u in shotUris()) try { contentResolver.delete(u, null, null) } catch (e: SecurityException) { left.add(u) }
        if (left.isNotEmpty() && Build.VERSION.SDK_INT >= 30) {
            // photos from before a reinstall belong to the gallery, not the app: Android asks you first
            askDelete.launch(androidx.activity.result.IntentSenderRequest.Builder(MediaStore.createDeleteRequest(contentResolver, left).intentSender).build())
        } else afterDelete()
    }

    private fun afterDelete() { clearLast(); closeViewer(); Toast.makeText(this, "Photo deleted", Toast.LENGTH_SHORT).show() }

    private fun clearLast() {
        lastUri?.let { prefs.edit().remove("art:$it").apply() }
        lastUri = null
        prefs.edit().remove("lastUri").remove("thumbUri").apply()
        loadThumb()
    }

    // The original from the shutter is already saved and is never touched again; editing makes copies.
    private fun openCrop(uri: Uri) {
        startActivity(Intent(this, CropActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun pickFromLibrary() {
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    companion object {
        /** Where photos are saved. Kept from before the rename so existing galleries stay together. */
        const val PHOTO_DIR = "Pictures/Fibonacci Camera"
        /** Auto flash fires (as the torch) when the scene averages darker than this. */
        const val AUTO_FLASH_LUMA = 0.18f
        /** How long the torch lights the scene before the shot, so exposure and white balance settle. */
        const val TORCH_SETTLE_MS = 650L
    }
}
