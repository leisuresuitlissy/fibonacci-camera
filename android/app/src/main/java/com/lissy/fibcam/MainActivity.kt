package com.lissy.fibcam

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
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
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
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
    private lateinit var expoL: TextView
    private lateinit var expoR: TextView
    private lateinit var pill: TextView
    private lateinit var coachCard: LinearLayout
    private lateinit var coachT: TextView
    private lateinit var coachS: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var guideRow: LinearLayout
    private lateinit var secondRow: LinearLayout
    private lateinit var sourceTxt: TextView
    private lateinit var coachBtn: TextView
    private lateinit var flashBtn: TextView
    private lateinit var thumb: ImageView
    private lateinit var gateMsg: TextView

    // ---- state
    private val prefs by lazy { getSharedPreferences("fibcam", Context.MODE_PRIVATE) }
    private var guide = Guide.SPIRAL
    private var quadrant = 0
    private var triFlip = false
    private var coach = false
    private var front = false
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val tracker = Tracker()
    private var trackState: Tracker.State? = null
    private var aligned = false
    private var hintUntil = 0L
    private var lastUri: Uri? = null

    private var zoomRatio = 1f
    private var zoomMin = 1f
    private var zoomMax = 1f
    private var chipsFor = ""
    @Volatile private var lastExpoAt = 0L
    private var expoText = ""

    private var level: Float? = null
    private var deviceRot = 0
    private lateinit var sensors: SensorManager
    private lateinit var orientation: OrientationEventListener

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
        setContentView(buildUi())
        sensors = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                deviceRot = when (o) { in 45..134 -> 90; in 135..224 -> 180; in 225..314 -> 270; else -> 0 }
                imageCapture?.targetRotation = when (deviceRot) { 90 -> Surface.ROTATION_270; 180 -> Surface.ROTATION_180; 270 -> Surface.ROTATION_90; else -> Surface.ROTATION_0 }
            }
        }
        applyGuide(); syncCoachBtn()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else askCamera.launch(Manifest.permission.CAMERA)
    }

    override fun onResume() {
        super.onResume()
        val g = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (g != null) sensors.registerListener(this, g, SensorManager.SENSOR_DELAY_UI)
        if (orientation.canDetectOrientation()) orientation.enable()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        orientation.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    private fun save() {
        prefs.edit().putString("guide", guide.name).putInt("quadrant", quadrant)
            .putBoolean("triFlip", triFlip).putBoolean("coach", coach).apply()
    }

    // =========================================================== UI
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

    private fun lp(w: Int, h: Int, g: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, g)
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val match = ViewGroup.LayoutParams.MATCH_PARENT

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(cBase) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom); insets
        }

        // top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(16f), dpi(8f), dpi(12f), dpi(8f))
        }
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(label("Fibonacci Camera", 15f, cText, Typeface.DEFAULT_BOLD))
        sourceTxt = label("LIVE · REAR", 10f, cMuted, mono, 0.1f).apply { setPadding(0, dpi(3f), 0, 0) }
        titles.addView(sourceTxt)
        top.addView(titles, LinearLayout.LayoutParams(0, wrap, 1f))
        coachBtn = chip("COACH", false) { coach = !coach; save(); syncCoachBtn(); updateHud() }
        top.addView(coachBtn)
        flashBtn = chip("FLASH OFF", false) { cycleFlash() }
        top.addView(flashBtn, LinearLayout.LayoutParams(wrap, wrap).apply { leftMargin = dpi(8f) })
        root.addView(top, LinearLayout.LayoutParams(match, wrap))

        // viewfinder
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipChildren = true }
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        stage.addView(preview, lp(match, match))
        overlay = OverlayView(this).also { it.listener = this }
        stage.addView(overlay, lp(match, match))
        hud = FrameLayout(this)
        stage.addView(hud, lp(0, 0))
        expoL = label("", 11f, withAlpha(cText, 0.8f), mono, 0.03f).apply { setShadowLayer(dp(3f), 0f, dp(1f), 0xAA000000.toInt()) }
        hud.addView(expoL, lp(wrap, wrap, Gravity.TOP or Gravity.START).apply { leftMargin = dpi(12f); topMargin = dpi(10f) })
        expoR = label("", 11f, cGold, mono, 0.03f).apply { setShadowLayer(dp(3f), 0f, dp(1f), 0xAA000000.toInt()) }
        hud.addView(expoR, lp(wrap, wrap, Gravity.TOP or Gravity.END).apply { rightMargin = dpi(12f); topMargin = dpi(10f) })
        pill = label("TAP SUBJECT", 10.5f, cMuted, mono, 0.12f).apply {
            setPadding(dpi(11f), dpi(7f), dpi(11f), dpi(7f)); visibility = View.GONE
        }
        hud.addView(pill, lp(wrap, wrap, Gravity.BOTTOM or Gravity.START).apply { leftMargin = dpi(12f); bottomMargin = dpi(60f) })
        coachCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dpi(14f), dpi(11f), dpi(14f), dpi(12f)); visibility = View.GONE
        }
        coachT = label("", 10.5f, cMuted, mono, 0.14f)
        coachS = label("", 14f, cText).apply { setPadding(0, dpi(6f), 0, 0); setLineSpacing(0f, 1.15f) }
        coachCard.addView(coachT); coachCard.addView(coachS)
        hud.addView(coachCard, lp(match, wrap, Gravity.BOTTOM).apply { leftMargin = dpi(12f); rightMargin = dpi(12f); bottomMargin = dpi(58f) })
        zoomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; background = rounded(withAlpha(cBase, 0.6f)); setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f)); visibility = View.GONE
        }
        hud.addView(zoomRow, lp(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dpi(12f) })
        gateMsg = label("", 14f, cMuted).apply { gravity = Gravity.CENTER; setPadding(dpi(32f), 0, dpi(32f), 0); visibility = View.GONE }
        stage.addView(gateMsg, lp(match, match))
        stage.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutFrame() }
        root.addView(stage, LinearLayout.LayoutParams(match, 0, 1f))

        // guide picker
        val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        guideRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        scroll.addView(guideRow)
        root.addView(scroll, LinearLayout.LayoutParams(match, dpi(52f)))

        secondRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dpi(16f), 0, dpi(16f), 0) }
        root.addView(secondRow, LinearLayout.LayoutParams(match, dpi(52f)))

        // shutter bar
        val bar = FrameLayout(this)
        thumb = ImageView(this).apply {
            background = rounded(cElev, cLine, 12f); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() { override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, dp(12f)) } }
            clipToOutline = true
            setOnClickListener { openLast() }
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
        val flip = label("⟲", 24f, cMuted).apply {
            gravity = Gravity.CENTER; background = rounded(cElev, cLine, 12f); contentDescription = "Switch camera"
            setOnClickListener { front = !front; tracker.clear(); trackState = null; this@MainActivity.overlay.target = null; bindCamera() }
        }
        bar.addView(flip, lp(dpi(54f), dpi(54f), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = dpi(24f) })
        root.addView(bar, LinearLayout.LayoutParams(match, dpi(120f)))
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
        hud.post { hud.requestLayout() }
        overlay.invalidate()
    }

    private fun syncCoachBtn() {
        coachBtn.setTextColor(if (coach) cGold else cMuted)
        coachBtn.background = if (coach) rounded(withAlpha(cGold, 0.18f), withAlpha(cGold, 0.85f)) else rounded(cElev)
    }

    private fun cycleFlash() {
        flashMode = when (flashMode) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
        imageCapture?.flashMode = flashMode
        val on = flashMode != ImageCapture.FLASH_MODE_OFF
        flashBtn.text = when (flashMode) { ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"; ImageCapture.FLASH_MODE_ON -> "FLASH ON"; else -> "FLASH OFF" }
        flashBtn.setTextColor(if (on) cGold else cMuted)
        flashBtn.background = if (on) rounded(withAlpha(cGold, 0.18f), withAlpha(cGold, 0.85f)) else rounded(cElev)
    }

    private fun applyGuide() {
        overlay.guide = guide; overlay.quadrant = quadrant; overlay.triFlip = triFlip
        guideRow.removeAllViews()
        for (g in Guide.values()) {
            guideRow.addView(chip(g.label, g == guide) { guide = g; aligned = false; save(); applyGuide() },
                LinearLayout.LayoutParams(wrap, wrap).apply { rightMargin = dpi(8f) })
        }
        secondRow.removeAllViews()
        when (guide) {
            Guide.SPIRAL -> {
                secondRow.addView(label("EYE", 10f, cMuted, mono, 0.13f))
                secondRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                for (q in 0..3) secondRow.addView(tile(q == quadrant, { c, p, on -> drawQuad(c, p, q, on) }) { quadrant = q; aligned = false; save(); applyGuide() },
                    LinearLayout.LayoutParams(dpi(52f), dpi(40f)).apply { leftMargin = dpi(8f) })
            }
            Guide.TRIANGLES -> {
                secondRow.addView(label("DIRECTION", 10f, cMuted, mono, 0.13f))
                secondRow.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                for (f in listOf(false, true)) secondRow.addView(tile(f == triFlip, { c, p, on -> drawDir(c, p, f, on) }) { triFlip = f; aligned = false; save(); applyGuide() },
                    LinearLayout.LayoutParams(dpi(52f), dpi(40f)).apply { leftMargin = dpi(8f) })
            }
            Guide.OFF -> secondRow.addView(label("NO GUIDE", 10f, cMuted, mono, 0.13f))
            else -> secondRow.addView(label("FIXED TO THE FRAME", 10f, cMuted, mono, 0.13f))
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

    private fun View.drawQuad(c: Canvas, p: Paint, q: Int, on: Boolean) {
        val cell = dp(10f); val gap = dp(1.5f)
        val x0 = (width - (cell * 2 + gap)) / 2; val y0 = (height - (cell * 2 + gap)) / 2
        p.style = Paint.Style.FILL
        for (i in 0..3) {
            val cx = x0 + (i % 2) * (cell + gap); val cy = y0 + (i / 2) * (cell + gap)
            p.color = if (i == q) (if (on) cGold else withAlpha(cText, 0.75f)) else withAlpha(cMuted, 0.22f)
            c.drawRoundRect(cx, cy, cx + cell, cy + cell, dp(2f), dp(2f), p)
        }
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

    private fun gate(msg: String) { gateMsg.text = msg; gateMsg.visibility = View.VISIBLE }

    // =========================================================== camera
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ provider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun bindCamera() {
        val p = provider ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()
        val ratio43 = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val pb = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio43).build())
        // Every preview frame reports what the camera actually chose: aperture, shutter, ISO.
        Camera2Interop.Extender(pb).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { onExposure(result) }
        })
        val prev = pb.build().also { it.setSurfaceProvider(preview.surfaceProvider) }
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
        camera?.cameraInfo?.zoomState?.observe(this) { z -> onZoom(z) }
        hintUntil = SystemClock.uptimeMillis() + 4000
        updateHud()
    }

    private fun onExposure(r: TotalCaptureResult) {
        val now = SystemClock.uptimeMillis()
        if (now - lastExpoAt < 250) return
        lastExpoAt = now
        val parts = ArrayList<String>(4)
        r.get(CaptureResult.LENS_APERTURE)?.let { parts.add("ƒ/" + String.format(Locale.US, "%.1f", it)) }
        r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { ns ->
            val s = ns / 1e9
            parts.add(if (s >= 0.5) String.format(Locale.US, "%.1fs", s) else "1/" + (1 / s).roundToInt())
        }
        r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { parts.add("ISO $it") }
        val t = parts.joinToString("   ")
        runOnUiThread { expoText = t; renderExpo() }
    }

    private fun renderExpo() {
        val z = if (abs(zoomRatio - 1f) < 0.05f) "1×" else String.format(Locale.US, "%.1f×", zoomRatio)
        expoL.text = if (expoText.isEmpty()) z else "$expoText   $z"
    }

    private fun onZoom(z: ZoomState) {
        zoomRatio = z.zoomRatio; zoomMin = z.minZoomRatio; zoomMax = z.maxZoomRatio
        val key = "$zoomMin-$zoomMax"
        if (key != chipsFor) { chipsFor = key; buildZoomChips() }
        syncZoomChips(); renderExpo()
    }

    private fun zoomStops(): List<Float> {
        val out = ArrayList<Float>()
        if (zoomMin < 0.95f) out.add(zoomMin)                        // the ultra-wide, where the camera offers it
        out.add(1f)
        for (s in listOf(2f, 5f, 10f)) if (s <= zoomMax + 0.01f) out.add(s)
        return out
    }

    private fun zoomLabel(z: Float) = when {
        z < 1f -> String.format(Locale.US, "%.1f", z).removePrefix("0")
        else -> "${z.roundToInt()}×"
    }

    private fun buildZoomChips() {
        zoomRow.removeAllViews()
        val stops = zoomStops()
        if (stops.size < 2) { zoomRow.visibility = View.GONE; return }
        for (s in stops) {
            val b = label(zoomLabel(s), 12f, cText, mono).apply {
                gravity = Gravity.CENTER; minWidth = dpi(40f); setPadding(dpi(8f), 0, dpi(8f), 0); tag = s
                setOnClickListener { camera?.cameraControl?.setZoomRatio(s) }
            }
            zoomRow.addView(b, LinearLayout.LayoutParams(wrap, dpi(30f)))
        }
        zoomRow.visibility = View.VISIBLE
    }

    private fun syncZoomChips() {
        var best: View? = null; var bd = Float.MAX_VALUE
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i); val d = abs((v.tag as Float) - zoomRatio)
            if (d < bd) { bd = d; best = v }
        }
        for (i in 0 until zoomRow.childCount) {
            val v = zoomRow.getChildAt(i) as TextView; val on = v === best && bd < 0.15f
            v.setTextColor(if (on) cGold else cText)
            v.background = if (on) rounded(withAlpha(cText, 0.16f)) else null
        }
    }

    // =========================================================== gestures
    override fun onSubjectTap(sx: Float, sy: Float) {
        val fr = overlay.frame
        val cur = overlay.target
        if (cur != null && trackState?.lost != true && Geo.dist(cur, PointF(sx, sy)) < dp(28f)) {
            tracker.clear(); trackState = null; overlay.target = null; aligned = false; overlay.invalidate(); updateHud(); return
        }
        if (!fr.contains(sx, sy)) return
        var nx = (sx - fr.left) / fr.width(); val ny = (sy - fr.top) / fr.height()
        if (front) nx = 1f - nx                                       // the preview is mirrored, the analysis frames aren't
        tracker.target(nx, ny)
        aligned = false
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
    }

    private fun buzz() {
        val fb = if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        overlay.performHapticFeedback(fb)
        if (coach) overlay.postDelayed({ overlay.performHapticFeedback(fb) }, 110)
    }

    /** Screen offset (subject minus goal) → the move that fixes it, in the world's terms. */
    private fun coachLine(dx: Float, dy: Float): String {
        val (wx, wy) = when (deviceRot) { 90 -> -dy to dx; 180 -> -dx to -dy; 270 -> dy to -dx; else -> dx to dy }
        val out = ArrayList<String>(2)
        // Subject right of the goal: turn the camera right, which slides the scene left.
        if (abs(wx) > 0.015f) out.add((if (wx > 0) "pan right" else "pan left") + if (abs(wx) < 0.08f) " a touch" else "")
        if (abs(wy) > 0.015f) out.add((if (wy > 0) "tilt down" else "tilt up") + if (abs(wy) < 0.08f) " a touch" else "")
        val s = out.joinToString(" · ").ifEmpty { "hold there" }
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun levelBit(): String {
        val l = level ?: return ""
        return if (l < 1.5f) " · level" else " · ${l.roundToInt()}° off level"
    }

    private fun updateHud() {
        if (!::hud.isInitialized) return
        val fr = overlay.frame
        expoR.text = if (guide == Guide.SPIRAL) "STRETCHED" else ""
        val st = trackState
        val goals = if (fr.isEmpty) emptyList() else Geo.goals(guide, fr, quadrant, triFlip)
        val state: String
        var line: String? = null
        if (st == null || overlay.target == null) state = "none"
        else if (st.lost) state = "lost"
        else {
            val p = overlay.target!!
            val best = goals.minByOrNull { Geo.dist(p, it) }
            if (best == null) { state = "noguide"; aligned = false }
            else {
                val d = Geo.dist(p, best)
                val r = max(dp(14f), 0.045f * min(fr.width(), fr.height()))
                if (!aligned && d < r) { aligned = true; buzz() } else if (aligned && d > r * 1.8f) aligned = false
                state = if (aligned) "aligned" else if (d < r * 3) "close" else "far"
                if (!aligned) line = coachLine((p.x - best.x) / fr.width(), (p.y - best.y) / fr.height())
            }
        }
        overlay.aligned = aligned
        val where = if (guide == Guide.SPIRAL) "on the eye" else "on a power point"
        val title = when (state) { "none" -> "TAP YOUR SUBJECT"; "lost" -> "LOST IT"; "noguide" -> "NO TARGET"; "aligned" -> "ALIGNED"; "close" -> "ALMOST"; else -> "MOVE" }
        val text = when (state) {
            "none" -> "Tap what the photo is about. It buzzes when the subject lands $where."
            "lost" -> "Tap the subject again."
            "noguide" -> "Pick a guide to aim for."
            "aligned" -> "Subject $where" + levelBit()
            else -> (line ?: "Hold there") + levelBit()
        }
        val on = state == "aligned"
        coachCard.visibility = if (coach) View.VISIBLE else View.GONE
        coachCard.background = if (on) rounded(0xB33C2E14.toInt(), withAlpha(cGold, 0.8f), 14f) else rounded(withAlpha(cBase, 0.66f), withAlpha(cText, 0.22f), 14f)
        coachT.text = "●  $title"; coachT.setTextColor(if (on) cGold else cMuted)
        coachS.text = text
        val showPill = !coach && (state != "none" || SystemClock.uptimeMillis() < hintUntil)
        pill.visibility = if (showPill) View.VISIBLE else View.GONE
        pill.text = "●  " + when (state) { "aligned" -> "ALIGNED"; "lost" -> "LOST"; "none" -> "TAP SUBJECT"; "noguide" -> "NO GUIDE"; else -> "TARGET" }
        pill.setTextColor(if (on) cGold else cMuted)
        pill.background = if (on) rounded(withAlpha(cGold, 0.18f), withAlpha(cGold, 0.85f)) else rounded(withAlpha(cBase, 0.55f), withAlpha(cText, 0.25f))
        if (state == "none" && SystemClock.uptimeMillis() < hintUntil) pill.postDelayed({ updateHud() }, 4100)
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
        val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(out: ImageCapture.OutputFileResults) {
                lastUri = out.savedUri
                loadThumb()
            }
            override fun onError(e: ImageCaptureException) {
                Toast.makeText(this@MainActivity, "Couldn't save: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        })
    }

    private fun loadThumb() {
        val uri = lastUri ?: return
        analysisExecutor.execute {
            val bmp = runCatching { contentResolver.loadThumbnail(uri, Size(240, 240), null) }.getOrNull()
            if (bmp != null) runOnUiThread { thumb.setImageBitmap(bmp) }
        }
    }

    private fun openLast() {
        val uri = lastUri ?: return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
