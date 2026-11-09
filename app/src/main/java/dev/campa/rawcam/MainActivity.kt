package dev.campa.rawcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.params.MeteringRectangle
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.util.Rational
import android.util.Size
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private enum class Mode { AUTO, ISO, SHUTTER, MANUAL }
    private enum class DriveKind { SINGLE, BURST, BRACKET }
    private data class Drive(val label: String, val kind: DriveKind, val evs: List<Double>)
    private data class Exposure(val iso: Int, val time: Long)
    private data class Frame(val name: String, val withJpeg: Boolean)

    private val drives = listOf(
        Drive("Simple", DriveKind.SINGLE, listOf(0.0)),
        Drive("Ráfaga ×4", DriveKind.BURST, List(4) { 0.0 }),
        Drive("Ráfaga ×8", DriveKind.BURST, List(8) { 0.0 }),
        Drive("Ráfaga ×16", DriveKind.BURST, List(16) { 0.0 }),
        Drive("Bracket ±1 ×3", DriveKind.BRACKET, listOf(-1.0, 0.0, 1.0)),
        Drive("Bracket ±2 ×3", DriveKind.BRACKET, listOf(-2.0, 0.0, 2.0)),
        Drive("Bracket ±2 ×5", DriveKind.BRACKET, listOf(-2.0, -1.0, 0.0, 1.0, 2.0)),
    )
    private val timers = listOf(0, 2, 10)

    private lateinit var previewFrame: FrameLayout
    private lateinit var preview: TextureView
    private lateinit var focusRing: View
    private lateinit var lockBadge: TextView
    private lateinit var countdownText: TextView
    private lateinit var info: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var evLabel: TextView
    private lateinit var evBar: SeekBar
    private lateinit var isoLabel: TextView
    private lateinit var isoBar: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var expBar: SeekBar
    private lateinit var driveBtn: Button
    private lateinit var timerBtn: Button
    private lateinit var resetBtn: Button
    private lateinit var shutter: Button
    private lateinit var overlay: OverlayView
    private lateinit var histToggle: CheckBox
    private lateinit var zebraToggle: CheckBox
    private lateinit var peakToggle: CheckBox
    private lateinit var gridToggle: CheckBox

    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    private var levelDeg = 0f

    // Viewfinder analysis: a downscaled copy of the preview is analysed off the UI thread.
    private val analysisThread = HandlerThread("analysis").apply { start() }
    private val analysisHandler = Handler(analysisThread.looper)
    private val grabBitmap = Bitmap.createBitmap(ANALYSIS_W, ANALYSIS_H, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(ANALYSIS_W * ANALYSIS_H)
    private val luma = IntArray(ANALYSIS_W * ANALYSIS_H)
    private val maskPixels = IntArray(ANALYSIS_W * ANALYSIS_H)
    private val maskBitmaps = Array(2) { Bitmap.createBitmap(ANALYSIS_W, ANALYSIS_H, Bitmap.Config.ARGB_8888) }
    private var maskIndex = 0
    @Volatile private var analyzing = false

    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private lateinit var isoRange: Range<Int>
    private lateinit var expRange: Range<Long>
    private lateinit var evRange: Range<Int>
    private lateinit var evStep: Rational
    private lateinit var activeArray: Rect
    private var maxExposure = 1_000_000_000L
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var sensorOrientation = 90

    // Camera objects: only touched on the camera handler thread.
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null

    @Volatile private var mode = Mode.AUTO
    @Volatile private var isoValue = 100
    @Volatile private var expValue = 10_000_000L
    @Volatile private var evIndex = 0
    @Volatile private var meteredIso = 100
    @Volatile private var meteredExp = 10_000_000L
    @Volatile private var focusRegion: MeteringRectangle? = null
    @Volatile private var locked = false
    @Volatile private var busy = false
    @Volatile private var driveIndex = 0
    private var timerIndex = 0
    private var counting = false
    private var countdownLeft = 0

    // Capture sequence state: camera handler thread only. RAW images and their
    // metadata arrive separately and are paired by sensor timestamp.
    private val pendingImages = HashMap<Long, Image>()
    private val pendingResults = HashMap<Long, TotalCaptureResult>()
    private val queue = ArrayDeque<CaptureRequest>()
    private var total = 0
    private var done = 0
    private var failed = 0
    private var inFlight = 0
    private var stamp = ""
    private var frameCount = 0
    private var askedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewFrame = findViewById(R.id.previewFrame)
        preview = findViewById(R.id.preview)
        focusRing = findViewById(R.id.focusRing)
        lockBadge = findViewById(R.id.lockBadge)
        countdownText = findViewById(R.id.countdownText)
        info = findViewById(R.id.info)
        modeGroup = findViewById(R.id.modeGroup)
        evLabel = findViewById(R.id.evLabel)
        evBar = findViewById(R.id.evBar)
        isoLabel = findViewById(R.id.isoLabel)
        isoBar = findViewById(R.id.isoBar)
        expLabel = findViewById(R.id.expLabel)
        expBar = findViewById(R.id.expBar)
        driveBtn = findViewById(R.id.driveBtn)
        timerBtn = findViewById(R.id.timerBtn)
        resetBtn = findViewById(R.id.resetBtn)
        shutter = findViewById(R.id.shutter)
        overlay = findViewById(R.id.overlay)
        histToggle = findViewById(R.id.histToggle)
        zebraToggle = findViewById(R.id.zebraToggle)
        peakToggle = findViewById(R.id.peakToggle)
        gridToggle = findViewById(R.id.gridToggle)

        gridToggle.setOnCheckedChangeListener { _, checked -> overlay.showGrid = checked }
        val analysisToggle = { _: android.widget.CompoundButton, _: Boolean ->
            if (!needsAnalysis()) overlay.setAnalysis(null, null)
        }
        histToggle.setOnCheckedChangeListener(analysisToggle)
        zebraToggle.setOnCheckedChangeListener(analysisToggle)
        peakToggle.setOnCheckedChangeListener(analysisToggle)

        previewFrame.layoutParams = previewFrame.layoutParams.apply {
            height = resources.displayMetrics.widthPixels * 4 / 3
        }

        if (!selectCamera()) {
            toast("No hay cámara trasera con soporte RAW")
            finish()
            return
        }

        evBar.max = evRange.upper - evRange.lower
        evBar.progress = -evRange.lower

        val bars = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = syncControls()
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        evBar.setOnSeekBarChangeListener(bars)
        isoBar.setOnSeekBarChangeListener(bars)
        expBar.setOnSeekBarChangeListener(bars)

        modeGroup.setOnCheckedChangeListener { _, id ->
            mode = when (id) {
                R.id.modeIso -> Mode.ISO
                R.id.modeShutter -> Mode.SHUTTER
                R.id.modeManual -> Mode.MANUAL
                else -> Mode.AUTO
            }
            syncControls()
        }

        driveBtn.setOnClickListener {
            driveIndex = (driveIndex + 1) % drives.size
            updateButtons()
        }
        timerBtn.setOnClickListener {
            timerIndex = (timerIndex + 1) % timers.size
            updateButtons()
        }
        resetBtn.setOnClickListener { resetFocus() }
        shutter.setOnClickListener { onShutter() }

        setupGestures()
        updateButtons()
        syncControls()
    }

    override fun onResume() {
        super.onResume()
        sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)?.let {
            sensors.registerListener(levelListener, it, SensorManager.SENSOR_DELAY_UI)
        }
        ui.post(grabTick)
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!askedPermission) {
                askedPermission = true
                requestPermissions(arrayOf(Manifest.permission.CAMERA), 1)
            }
            return
        }
        startWhenReady()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<String>, results: IntArray) {
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else toast("Se necesita permiso de cámara")
    }

    override fun onPause() {
        sensors.unregisterListener(levelListener)
        ui.removeCallbacks(grabTick)
        cancelCountdown()
        shutter.isEnabled = true
        shutter.text = SHUTTER_LABEL
        handler.post { closeCamera() }
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        analysisThread.quitSafely()
        io.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun startWhenReady() {
        if (preview.isAvailable) {
            handler.post { openCamera() }
            return
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) {
                handler.post { openCamera() }
            }
            override fun onSurfaceTextureSizeChanged(t: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(t: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(t: SurfaceTexture) {}
        }
    }

    private fun selectCamera(): Boolean {
        val id = manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK &&
                c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
        } ?: return false

        cameraId = id
        chars = manager.getCameraCharacteristics(id)
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1)
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        maxExposure = min(expRange.upper, 1_000_000_000L)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return true
    }

    // ---- UI ----

    private fun syncControls() {
        isoBar.isEnabled = mode == Mode.ISO || mode == Mode.MANUAL
        expBar.isEnabled = mode == Mode.SHUTTER || mode == Mode.MANUAL
        evBar.isEnabled = mode != Mode.MANUAL

        isoValue = logLerp(isoRange.lower.toDouble(), isoRange.upper.toDouble(), isoBar.progress / 1000.0).roundToInt()
        expValue = logLerp(expRange.lower.toDouble(), maxExposure.toDouble(), expBar.progress / 1000.0).toLong()
        evIndex = evBar.progress + evRange.lower

        isoLabel.text = "ISO $isoValue"
        expLabel.text = "Obturación ${formatExposure(expValue)}"
        evLabel.text = String.format(Locale.US, "Compensación %+.1f EV", evIndex * evStep.toDouble())

        handler.removeCallbacks(previewUpdate)
        handler.postDelayed(previewUpdate, 50)
    }

    private val previewUpdate = Runnable { refreshPreview() }

    private fun updateButtons() {
        driveBtn.text = drives[driveIndex].label
        val secs = timers[timerIndex]
        timerBtn.text = if (secs == 0) "Timer: no" else "Timer: ${secs}s"
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) = toggleLock()
        })
        preview.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }

    private fun focusAt(x: Float, y: Float) {
        if (maxAfRegions == 0 && maxAeRegions == 0) {
            toast("Esta cámara no admite enfoque por toque")
            return
        }
        focusRegion = regionFor(x, y)
        locked = false
        lockBadge.visibility = View.GONE
        val size = focusRing.layoutParams.width
        focusRing.translationX = x - size / 2f
        focusRing.translationY = y - size / 2f
        focusRing.visibility = View.VISIBLE
        handler.post { refreshPreview(CaptureRequest.CONTROL_AF_TRIGGER_START) }
    }

    private fun toggleLock() {
        locked = !locked
        lockBadge.visibility = if (locked) View.VISIBLE else View.GONE
        preview.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val trigger = if (locked) CaptureRequest.CONTROL_AF_TRIGGER_START else CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
        handler.post { refreshPreview(trigger) }
    }

    private fun resetFocus() {
        focusRegion = null
        locked = false
        focusRing.visibility = View.GONE
        lockBadge.visibility = View.GONE
        handler.post { refreshPreview(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL) }
    }

    /** Maps a point on the portrait preview to a metering rectangle in sensor coordinates. */
    private fun regionFor(x: Float, y: Float): MeteringRectangle {
        val nx = (x / preview.width).coerceIn(0f, 1f)
        val ny = (y / preview.height).coerceIn(0f, 1f)
        val (sx, sy) = when (sensorOrientation) {
            90 -> ny to 1f - nx
            180 -> 1f - nx to 1f - ny
            270 -> 1f - ny to nx
            else -> nx to ny
        }
        val w = activeArray.width()
        val h = activeArray.height()
        val size = (min(w, h) * 0.1f).toInt()
        val left = (sx * w - size / 2f).toInt().coerceIn(0, w - size)
        val top = (sy * h - size / 2f).toInt().coerceIn(0, h - size)
        return MeteringRectangle(left, top, size, size, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    private fun onShutter() {
        if (counting) {
            cancelCountdown()
            return
        }
        if (busy) return
        val secs = timers[timerIndex]
        if (secs == 0) {
            handler.post { startSequence() }
            return
        }
        counting = true
        countdownLeft = secs
        countdownText.visibility = View.VISIBLE
        ui.post(tick)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (countdownLeft <= 0) {
                counting = false
                countdownText.visibility = View.GONE
                handler.post { startSequence() }
                return
            }
            countdownText.text = countdownLeft.toString()
            countdownLeft--
            ui.postDelayed(this, 1000)
        }
    }

    private fun cancelCountdown() {
        ui.removeCallbacks(tick)
        counting = false
        countdownLeft = 0
        countdownText.visibility = View.GONE
    }

    // ---- Viewfinder aids ----

    private val levelListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val raw = Math.toDegrees(atan2(e.values[0].toDouble(), e.values[1].toDouble())).toFloat()
            var delta = raw - levelDeg
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            levelDeg += delta * 0.2f
            overlay.setLevel(levelDeg)
        }

        override fun onAccuracyChanged(s: Sensor, accuracy: Int) {}
    }

    private fun needsAnalysis() = histToggle.isChecked || zebraToggle.isChecked || peakToggle.isChecked

    private val grabTick = object : Runnable {
        override fun run() {
            if (needsAnalysis() && preview.isAvailable && !analyzing) {
                preview.getBitmap(grabBitmap)
                grabBitmap.getPixels(pixels, 0, ANALYSIS_W, 0, 0, ANALYSIS_W, ANALYSIS_H)
                analyzing = true
                val hist = histToggle.isChecked
                val zebra = zebraToggle.isChecked
                val peak = peakToggle.isChecked
                analysisHandler.post {
                    try {
                        analyze(hist, zebra, peak)
                    } finally {
                        analyzing = false
                    }
                }
            }
            ui.postDelayed(this, ANALYSIS_INTERVAL_MS)
        }
    }

    /** Luma histogram, diagonal-stripe zebras on clipped pixels and gradient-based focus peaking. */
    private fun analyze(hist: Boolean, zebra: Boolean, peak: Boolean) {
        val w = ANALYSIS_W
        val h = ANALYSIS_H
        val bins = IntArray(HIST_BINS)
        val useMask = zebra || peak
        if (useMask) maskPixels.fill(0)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val l = (r * 77 + g * 150 + b * 29) shr 8
                luma[i] = l
                bins[l * HIST_BINS / 256]++
                if (zebra && (r >= ZEBRA_LEVEL || g >= ZEBRA_LEVEL || b >= ZEBRA_LEVEL) && ((x + y) / 6) % 2 == 0) {
                    maskPixels[i] = ZEBRA_COLOR
                }
            }
        }

        if (peak) {
            for (y in 1 until h - 1) {
                for (x in 1 until w - 1) {
                    val i = y * w + x
                    val gx = luma[i + 1] - luma[i - 1]
                    val gy = luma[i + w] - luma[i - w]
                    if (abs(gx) + abs(gy) > PEAK_THRESHOLD) maskPixels[i] = PEAK_COLOR
                }
            }
        }

        var mask: Bitmap? = null
        if (useMask) {
            mask = maskBitmaps[maskIndex]
            mask.setPixels(maskPixels, 0, w, 0, 0, w, h)
            maskIndex = 1 - maskIndex
        }
        val histogram = if (hist) bins else null
        runOnUiThread { if (needsAnalysis()) overlay.setAnalysis(histogram, mask) }
    }

    // ---- Exposure ----

    /** Exposure the still will use. Priority modes solve the free variable from the metered ISO×time product. */
    private fun baseExposure(): Exposure {
        val product = meteredIso.toDouble() * meteredExp
        return when (mode) {
            Mode.MANUAL -> Exposure(isoValue, expValue)
            Mode.AUTO -> Exposure(meteredIso, meteredExp)
            Mode.ISO -> withIso(product, isoValue)
            Mode.SHUTTER -> withTime(product, expValue)
        }
    }

    private fun shifted(e: Exposure, ev: Double) = withIso(e.iso.toDouble() * e.time * 2.0.pow(ev), e.iso)

    private fun withIso(product: Double, iso: Int): Exposure {
        val time = (product / iso).toLong().coerceIn(expRange.lower, maxExposure)
        val finalIso = (product / time).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
        return Exposure(finalIso, time)
    }

    private fun withTime(product: Double, time: Long): Exposure {
        val iso = (product / time).roundToInt().coerceIn(isoRange.lower, isoRange.upper)
        val finalTime = (product / iso).toLong().coerceIn(expRange.lower, maxExposure)
        return Exposure(iso, finalTime)
    }

    private fun modeLabel() = when (mode) {
        Mode.AUTO -> "Auto"
        Mode.ISO -> "Prioridad ISO"
        Mode.SHUTTER -> "Prioridad obturación"
        Mode.MANUAL -> "Manual"
    }

    // ---- Camera (handler thread) ----

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width } ?: Size(1440, 1080)

        rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, MAX_IN_FLIGHT + 2).apply {
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { onRaw(it) } }, handler)
        }
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r ->
                val image = r.acquireNextImage() ?: return@setOnImageAvailableListener
                val name = "IMG_$stamp.jpg"
                io.execute { saveJpeg(image, name) }
            }, handler)
        }

        val texture = preview.surfaceTexture ?: return
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(d: CameraDevice) {
                device = d
                createSession()
            }

            override fun onDisconnected(d: CameraDevice) {
                d.close()
                device = null
            }

            override fun onError(d: CameraDevice, error: Int) {
                d.close()
                device = null
                toast("Error de cámara ($error)")
            }
        }, handler)
    }

    @Suppress("DEPRECATION")
    private fun createSession() {
        val d = device ?: return
        val targets = listOf(previewSurface!!, rawReader!!.surface, jpegReader!!.surface)
        d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                refreshPreview()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = toast("No se pudo configurar la sesión")
        }, handler)
    }

    private fun closeCamera() {
        busy = false
        pendingImages.values.forEach { it.close() }
        pendingImages.clear()
        pendingResults.clear()
        queue.clear()
        session?.close()
        session = null
        device?.close()
        device = null
        rawReader?.close()
        rawReader = null
        jpegReader?.close()
        jpegReader = null
        previewSurface?.release()
        previewSurface = null
    }

    private fun CaptureRequest.Builder.applyFocus() {
        val region = focusRegion
        if (region != null && maxAfRegions > 0) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
        if (region != null && maxAeRegions > 0) set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
    }

    /**
     * In manual mode, preview exposures longer than 1/30s are shortened and compensated
     * with ISO so the viewfinder stays fluid. Other modes keep AE running for metering.
     */
    private fun previewRequest(trigger: Int?): CaptureRequest =
        device!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(previewSurface!!)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            applyFocus()
            if (mode == Mode.MANUAL) {
                var time = expValue
                var iso = isoValue
                if (time > PREVIEW_MAX_EXPOSURE_NS) {
                    iso = (iso.toDouble() * time / PREVIEW_MAX_EXPOSURE_NS).roundToInt()
                        .coerceIn(isoRange.lower, isoRange.upper)
                    time = PREVIEW_MAX_EXPOSURE_NS
                }
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, time)
                set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(time, PREVIEW_MAX_EXPOSURE_NS))
            } else {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
                set(CaptureRequest.CONTROL_AE_LOCK, locked)
            }
            if (trigger != null) set(CaptureRequest.CONTROL_AF_TRIGGER, trigger)
        }.build()

    private fun refreshPreview(trigger: Int? = null) {
        val s = session ?: return
        if (device == null || previewSurface == null) return
        try {
            if (trigger != null) s.capture(previewRequest(trigger), null, handler)
            s.setRepeatingRequest(previewRequest(null), previewCallback, handler)
        } catch (e: Exception) {
            toast("Preview: ${e.message}")
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val time = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            if (result.get(CaptureResult.CONTROL_AE_MODE) != CaptureResult.CONTROL_AE_MODE_OFF) {
                meteredIso = iso
                meteredExp = time
            }
            if (frameCount++ % 10 != 0) return
            val shot = baseExposure()
            val text = "${modeLabel()} · Visor: ISO $iso · ${formatExposure(time)}\n" +
                "Foto: ISO ${shot.iso} · ${formatExposure(shot.time)}"
            runOnUiThread { info.text = text }
        }
    }

    private fun stillRequest(e: Exposure, frame: Frame): CaptureRequest =
        device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(rawReader!!.surface)
            if (frame.withJpeg) addTarget(jpegReader!!.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            applyFocus()
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.time)
            set(CaptureRequest.SENSOR_FRAME_DURATION, e.time)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            set(CaptureRequest.JPEG_QUALITY, 95.toByte())
            set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            setTag(frame)
        }.build()

    // ---- Capture sequence (handler thread) ----

    private fun startSequence() {
        if (busy || session == null || device == null) return
        val drive = drives[driveIndex]
        val base = baseExposure()
        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        val requests = try {
            drive.evs.mapIndexed { i, ev ->
                val name = when (drive.kind) {
                    DriveKind.SINGLE -> "RAW_$stamp.dng"
                    DriveKind.BURST -> String.format(Locale.US, "RAW_%s_b%02d.dng", stamp, i + 1)
                    DriveKind.BRACKET -> String.format(Locale.US, "RAW_%s_ev%+.1f.dng", stamp, ev)
                }
                val e = if (ev == 0.0) base else shifted(base, ev)
                stillRequest(e, Frame(name, drive.kind == DriveKind.SINGLE))
            }
        } catch (e: Exception) {
            toast("Captura: ${e.message}")
            return
        }

        queue.clear()
        queue.addAll(requests)
        total = requests.size
        done = 0
        failed = 0
        inFlight = 0
        busy = true
        showProgress()
        issueNext()
    }

    /** Keeps at most MAX_IN_FLIGHT frames between request and saved file, bounding RAW memory. */
    private fun issueNext() {
        val s = session ?: return
        while (queue.isNotEmpty() && inFlight < MAX_IN_FLIGHT) {
            val request = queue.removeFirst()
            inFlight++
            try {
                s.capture(request, stillCallback, handler)
            } catch (e: Exception) {
                toast("Captura: ${e.message}")
                inFlight--
                val dropped = 1 + queue.size
                queue.clear()
                failed += dropped
                done += dropped
                if (inFlight == 0) finishSequence()
                return
            }
        }
    }

    private val stillCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (!busy) return
            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            pendingResults[ts] = result
            tryPair(ts)
        }

        override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: CaptureFailure) {
            if (!busy) return
            inFlight--
            failed++
            frameDone()
        }
    }

    private fun onRaw(image: Image) {
        if (!busy) {
            image.close()
            return
        }
        pendingImages[image.timestamp] = image
        tryPair(image.timestamp)
    }

    private fun tryPair(ts: Long) {
        val image = pendingImages[ts] ?: return
        val result = pendingResults[ts] ?: return
        pendingImages.remove(ts)
        pendingResults.remove(ts)
        val frame = result.request.tag as? Frame ?: Frame("RAW_${stamp}_$ts.dng", false)
        io.execute {
            val ok = saveDng(image, result, frame)
            handler.post {
                if (!busy) return@post
                inFlight--
                if (!ok) failed++
                frameDone()
            }
        }
    }

    private fun frameDone() {
        done++
        if (done >= total) finishSequence() else {
            showProgress()
            issueNext()
        }
    }

    private fun finishSequence() {
        busy = false
        pendingImages.values.forEach { it.close() }
        pendingImages.clear()
        pendingResults.clear()
        queue.clear()
        val saved = total - failed
        toast(
            when {
                total == 1 && failed == 0 -> "Guardado RAW_$stamp.dng"
                total == 1 -> "Falló la captura"
                else -> "Guardadas $saved/$total en DCIM/RawCam"
            }
        )
        runOnUiThread {
            shutter.isEnabled = true
            shutter.text = SHUTTER_LABEL
        }
    }

    private fun showProgress() {
        val text = if (total > 1) "Capturando ${done + 1}/$total…" else "Capturando…"
        runOnUiThread {
            shutter.isEnabled = false
            shutter.text = text
        }
    }

    // ---- Saving (io thread) ----

    private fun saveDng(image: Image, result: TotalCaptureResult, frame: Frame): Boolean {
        return try {
            val uri = insertImage(frame.name, "image/x-adobe-dng")
            contentResolver.openOutputStream(uri)!!.use { out ->
                val (dngChars, dngResult) = dngSource(result)
                DngCreator(dngChars, dngResult).use { dng ->
                    dng.setOrientation(exifOrientation())
                    dng.writeImage(out, image)
                }
            }
            publish(uri)
            true
        } catch (e: Exception) {
            toast("DNG: ${e.message}")
            false
        } finally {
            image.close()
        }
    }

    /**
     * Logical multi-cameras (Pixel 7 main) may lack color calibration tags that DngCreator
     * requires; those live on the physical sensor that actually produced the frame.
     */
    private fun dngSource(result: TotalCaptureResult): Pair<CameraCharacteristics, CaptureResult> {
        if (chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) != null) return chars to result

        val active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val candidates = listOfNotNull(active) + chars.physicalCameraIds
        val physId = candidates.firstOrNull { id ->
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1) != null
        } ?: return chars to result

        @Suppress("DEPRECATION")
        val physResult = result.physicalCameraResults[physId] ?: result
        return manager.getCameraCharacteristics(physId) to physResult
    }

    private fun saveJpeg(image: Image, name: String) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val uri = insertImage(name, "image/jpeg")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            publish(uri)
        } catch (e: Exception) {
            toast("JPEG: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun insertImage(name: String, mime: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/RawCam")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        return contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)!!
    }

    private fun publish(uri: Uri) {
        contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }

    private fun exifOrientation() = when (sensorOrientation) {
        90 -> ExifInterface.ORIENTATION_ROTATE_90
        180 -> ExifInterface.ORIENTATION_ROTATE_180
        270 -> ExifInterface.ORIENTATION_ROTATE_270
        else -> ExifInterface.ORIENTATION_NORMAL
    }

    private fun logLerp(lo: Double, hi: Double, t: Double) = exp(ln(lo) + (ln(hi) - ln(lo)) * t)

    private fun formatExposure(ns: Long): String =
        if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9)
        else "1/${(1e9 / ns).roundToInt()}s"

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val PREVIEW_MAX_EXPOSURE_NS = 33_333_333L
        private const val MAX_IN_FLIGHT = 3
        private const val SHUTTER_LABEL = "Capturar"
        private const val ANALYSIS_W = 360
        private const val ANALYSIS_H = 480
        private const val ANALYSIS_INTERVAL_MS = 125L
        private const val HIST_BINS = 64
        private const val ZEBRA_LEVEL = 250
        private const val PEAK_THRESHOLD = 56
        private const val ZEBRA_COLOR = 0xB0FF3030.toInt()
        private const val PEAK_COLOR = 0xFF00E5FF.toInt()
    }
}
