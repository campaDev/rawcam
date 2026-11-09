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
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.os.Build
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
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private enum class DriveKind { SINGLE, BURST, BRACKET, STACK }
    private data class Drive(val label: String, val kind: DriveKind, val evs: List<Double>, val infinity: Boolean = false)
    private data class Exposure(val iso: Int, val time: Long)
    private data class Frame(
        val base: String,
        val withRaw: Boolean,
        val withJpeg: Boolean,
        val location: Location?,
        val stack: Boolean = false,
        val infinity: Boolean = false,
    )

    private enum class SaveFormat(val label: String) {
        BOTH("RAW + JPEG"),
        RAW("Solo RAW"),
        JPEG("Solo JPEG"),
    }
    private data class Lens(val id: String, val zoom: Float, val label: String)

    private enum class Wb(val label: String, val awb: Int) {
        AUTO("WB auto", CaptureRequest.CONTROL_AWB_MODE_AUTO),
        DAYLIGHT("WB luz día", CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
        CLOUDY("WB nublado", CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
        SHADE("WB sombra", CaptureRequest.CONTROL_AWB_MODE_SHADE),
        TUNGSTEN("WB incandesc.", CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
        FLUORESCENT("WB fluoresc.", CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
        KELVIN("WB Kelvin", CaptureRequest.CONTROL_AWB_MODE_OFF),
    }

    private val drives = listOf(
        Drive("Simple", DriveKind.SINGLE, listOf(0.0)),
        Drive("Ráfaga ×4", DriveKind.BURST, List(4) { 0.0 }),
        Drive("Ráfaga ×8", DriveKind.BURST, List(8) { 0.0 }),
        Drive("Ráfaga ×16", DriveKind.BURST, List(16) { 0.0 }),
        Drive("Bracket ±1 ×3", DriveKind.BRACKET, listOf(-1.0, 0.0, 1.0)),
        Drive("Bracket ±2 ×3", DriveKind.BRACKET, listOf(-2.0, 0.0, 2.0)),
        Drive("Bracket ±2 ×5", DriveKind.BRACKET, listOf(-2.0, -1.0, 0.0, 1.0, 2.0)),
        Drive("Promedio ×8", DriveKind.STACK, List(8) { 0.0 }),
        Drive("Promedio ×16", DriveKind.STACK, List(16) { 0.0 }),
        Drive("Promedio ×32", DriveKind.STACK, List(32) { 0.0 }),
        Drive("Astro ×16 (∞)", DriveKind.STACK, List(16) { 0.0 }, infinity = true),
        Drive("Astro ×32 (∞)", DriveKind.STACK, List(32) { 0.0 }, infinity = true),
    )
    private val timers = listOf(0, 2, 10)
    private val intervals = listOf(0, 2, 5, 10, 30, 60)
    private val shotCounts = listOf(10, 30, 100, 300)

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
    private lateinit var focusToggle: CheckBox
    private lateinit var focusLabel: TextView
    private lateinit var focusBar: SeekBar
    private lateinit var wbBtn: Button
    private lateinit var lensBtn: Button
    private lateinit var kelvinLabel: TextView
    private lateinit var kelvinBar: SeekBar

    private lateinit var formatBtn: Button
    private lateinit var qualityBtn: Button
    private lateinit var processBtn: Button
    private lateinit var locationToggle: CheckBox
    private lateinit var intervalBtn: Button
    private lateinit var shotsBtn: Button
    private lateinit var lensToggle: CheckBox
    private lateinit var distanceToggle: CheckBox
    private lateinit var distanceBadge: TextView
    private lateinit var lensWarning: TextView
    private lateinit var lensDebug: TextView

    private class LensStats(val haze: Float, val contrast: Float, val glow: Float, val meanLuma: Float)

    @Volatile private var afFocused = false
    private var focusCalibrated = false
    private var dirtyScore = 0f

    // Intervalometer (UI thread), with the shot number read by the camera thread for naming.
    private var intervalIndex = 0
    private var shotCountIndex = 0
    private var intervalRunning = false
    private var shotsTaken = 0
    @Volatile private var intervalShot = 0
    @Volatile private var intervalStamp = ""

    // Stacking accumulator: io thread only.
    private var stackSum: IntArray? = null
    private var stackCount = 0
    private var stackWidth = 0
    private var stackHeight = 0
    private var stackResult: TotalCaptureResult? = null
    private var stackLocation: Location? = null

    // Whether the running sequence is a stack: camera handler thread only.
    private var stackSequence = false
    private var stackBase = ""

    private val qualities = listOf(85, 90, 95, 100)
    private var qualityIndex = 2
    @Volatile private var saveFormat = SaveFormat.BOTH
    @Volatile private var jpegQuality = 95
    @Volatile private var naturalJpeg = false
    @Volatile private var useLocation = false
    @Volatile private var lastLocation: Location? = null
    private var nrModes: Set<Int> = emptySet()
    private val locationManager by lazy { getSystemService(LocationManager::class.java) }

    private val lenses = ArrayList<Lens>()
    private var lensIndex = 0
    private var wbOptions = listOf(Wb.AUTO)
    private var wbIndex = 0
    private var minFocusDiopters = 0f
    @Volatile private var zoomRatio = 1f
    @Volatile private var manualFocus = false
    @Volatile private var focusDiopters = 0f
    @Volatile private var wbMode = Wb.AUTO
    @Volatile private var kelvin = 5500
    @Volatile private var lastTransform: ColorSpaceTransform? = null

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
    private val pendingJpegs = HashMap<Long, Image>()
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
        focusToggle = findViewById(R.id.focusToggle)
        focusLabel = findViewById(R.id.focusLabel)
        focusBar = findViewById(R.id.focusBar)
        wbBtn = findViewById(R.id.wbBtn)
        lensBtn = findViewById(R.id.lensBtn)
        kelvinLabel = findViewById(R.id.kelvinLabel)
        kelvinBar = findViewById(R.id.kelvinBar)
        formatBtn = findViewById(R.id.formatBtn)
        qualityBtn = findViewById(R.id.qualityBtn)
        processBtn = findViewById(R.id.processBtn)
        locationToggle = findViewById(R.id.locationToggle)
        intervalBtn = findViewById(R.id.intervalBtn)
        shotsBtn = findViewById(R.id.shotsBtn)
        lensToggle = findViewById(R.id.lensToggle)
        distanceToggle = findViewById(R.id.distanceToggle)
        distanceBadge = findViewById(R.id.distanceBadge)
        lensWarning = findViewById(R.id.lensWarning)
        lensDebug = findViewById(R.id.lensDebug)

        lensToggle.setOnCheckedChangeListener { _, checked ->
            if (!checked) {
                dirtyScore = 0f
                lensWarning.visibility = View.GONE
                lensDebug.visibility = View.GONE
            }
            if (!needsAnalysis()) overlay.setAnalysis(null, null)
        }
        distanceToggle.setOnCheckedChangeListener { _, checked ->
            if (!checked) distanceBadge.visibility = View.GONE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        intervalBtn.setOnClickListener {
            if (intervalRunning) return@setOnClickListener
            intervalIndex = (intervalIndex + 1) % intervals.size
            updateButtons()
        }
        shotsBtn.setOnClickListener {
            if (intervalRunning) return@setOnClickListener
            shotCountIndex = (shotCountIndex + 1) % shotCounts.size
            updateButtons()
        }

        formatBtn.setOnClickListener {
            saveFormat = SaveFormat.values()[(saveFormat.ordinal + 1) % SaveFormat.values().size]
            updateButtons()
        }
        qualityBtn.setOnClickListener {
            qualityIndex = (qualityIndex + 1) % qualities.size
            jpegQuality = qualities[qualityIndex]
            updateButtons()
        }
        processBtn.setOnClickListener {
            naturalJpeg = !naturalJpeg
            updateButtons()
        }
        locationToggle.setOnCheckedChangeListener { _, checked ->
            useLocation = checked
            if (!checked) {
                stopLocation()
                lastLocation = null
            } else if (hasLocationPermission()) {
                startLocation()
            } else {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION,
                )
            }
        }

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

        buildLenses()
        if (lenses.isEmpty()) {
            toast("No hay cámara trasera con soporte RAW")
            finish()
            return
        }
        loadCamera(lenses[0].id)
        applyCameraRanges()

        val bars = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = syncControls()
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        evBar.setOnSeekBarChangeListener(bars)
        isoBar.setOnSeekBarChangeListener(bars)
        expBar.setOnSeekBarChangeListener(bars)
        focusBar.setOnSeekBarChangeListener(bars)
        kelvinBar.setOnSeekBarChangeListener(bars)

        focusToggle.setOnCheckedChangeListener { _, _ -> syncControls() }
        wbBtn.setOnClickListener {
            wbIndex = (wbIndex + 1) % wbOptions.size
            updateButtons()
            syncControls()
        }
        lensBtn.setOnClickListener { switchLens((lensIndex + 1) % lenses.size) }

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
        if (useLocation && hasLocationPermission()) startLocation()
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
        if (code == REQUEST_LOCATION) {
            if (results.any { it == PackageManager.PERMISSION_GRANTED }) {
                startLocation()
            } else {
                locationToggle.isChecked = false
                toast("Sin permiso de ubicación")
            }
            return
        }
        if (results.firstOrNull() == PackageManager.PERMISSION_GRANTED) startWhenReady()
        else toast("Se necesita permiso de cámara")
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    @SuppressLint("MissingPermission")
    private fun startLocation() {
        if (!hasLocationPermission()) return
        stopLocation()
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!locationManager.isProviderEnabled(provider)) continue
                locationManager.requestLocationUpdates(provider, 5000L, 10f, locationListener, Looper.getMainLooper())
                if (lastLocation == null) lastLocation = locationManager.getLastKnownLocation(provider)
            } catch (e: Exception) {
                // Provider unavailable on this device; keep trying the others.
            }
        }
    }

    private fun stopLocation() {
        locationManager.removeUpdates(locationListener)
    }

    override fun onPause() {
        stopInterval()
        stopLocation()
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

    /**
     * One entry per RAW-capable back camera, plus an ultra-wide entry when a logical camera
     * reaches below 1× through CONTROL_ZOOM_RATIO (how the Pixel exposes its ultra-wide).
     */
    private fun buildLenses() {
        lenses.clear()
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            val back = c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            val raw = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true
            if (!back || !raw) continue

            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
            val main = if (lenses.isEmpty()) "1×" else "Cam $id"
            lenses += Lens(id, 1f, if (focal != null) String.format(Locale.US, "%s (%.1fmm)", main, focal) else main)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val zoom = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
                if (zoom != null && zoom.lower < 0.99f) {
                    lenses += Lens(id, zoom.lower, String.format(Locale.US, "Gran angular %.1f×", zoom.lower))
                }
            }
        }
    }

    private fun switchLens(index: Int) {
        if (busy || index == lensIndex) return
        val target = lenses[index]
        val sameCamera = target.id == cameraId
        lensIndex = index
        zoomRatio = target.zoom
        focusRegion = null
        locked = false
        focusRing.visibility = View.GONE
        lockBadge.visibility = View.GONE
        updateButtons()
        if (sameCamera) {
            syncControls()
            return
        }
        handler.post {
            closeCamera()
            runOnUiThread {
                loadCamera(target.id)
                applyCameraRanges()
                syncControls()
                handler.post { openCamera() }
            }
        }
    }

    /** Re-applies per-camera slider ranges and option lists after loading a camera. */
    private fun applyCameraRanges() {
        evBar.max = evRange.upper - evRange.lower
        evBar.progress = -evRange.lower
        focusToggle.isEnabled = minFocusDiopters > 0f
        if (minFocusDiopters <= 0f) focusToggle.isChecked = false
        wbIndex = wbIndex.coerceIn(0, wbOptions.size - 1)
        updateButtons()
    }

    private fun loadCamera(id: String) {
        cameraId = id
        chars = manager.getCameraCharacteristics(id)
        minFocusDiopters = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awbModes = chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toSet() ?: emptySet()
        val manualPost = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
        wbOptions = Wb.values().filter { wb ->
            when (wb) {
                Wb.AUTO -> true
                Wb.KELVIN -> manualPost && wb.awb in awbModes
                else -> wb.awb in awbModes
            }
        }
        lastTransform = null
        nrModes = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toSet() ?: emptySet()
        focusCalibrated = chars.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
            CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)!!
        expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)!!
        evRange = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        evStep = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP) ?: Rational(1, 1)
        activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        maxExposure = min(expRange.upper, MAX_EXPOSURE_NS)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
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

        manualFocus = focusToggle.isChecked && minFocusDiopters > 0f
        focusBar.isEnabled = manualFocus
        val t = focusBar.progress / 1000f
        focusDiopters = minFocusDiopters * t * t
        focusLabel.text = when {
            minFocusDiopters <= 0f -> "Esta lente tiene foco fijo"
            !manualFocus -> "Foco: automático"
            focusDiopters < 0.01f -> "Foco: ∞"
            else -> String.format(Locale.US, "Foco: %.2f m", 1f / focusDiopters)
        }

        wbMode = wbOptions.getOrElse(wbIndex) { Wb.AUTO }
        kelvin = 2000 + kelvinBar.progress * 100
        val showKelvin = if (wbMode == Wb.KELVIN) View.VISIBLE else View.GONE
        kelvinLabel.visibility = showKelvin
        kelvinBar.visibility = showKelvin
        kelvinLabel.text = "Temperatura ${kelvin}K"

        handler.removeCallbacks(previewUpdate)
        handler.postDelayed(previewUpdate, 50)
    }

    private val previewUpdate = Runnable { refreshPreview() }

    private fun updateButtons() {
        driveBtn.text = drives[driveIndex].label
        val secs = timers[timerIndex]
        timerBtn.text = if (secs == 0) "Timer: no" else "Timer: ${secs}s"
        wbBtn.text = wbOptions.getOrElse(wbIndex) { Wb.AUTO }.label
        lensBtn.text = "Lente: ${lenses.getOrNull(lensIndex)?.label ?: "-"}"
        lensBtn.isEnabled = lenses.size > 1
        formatBtn.text = saveFormat.label
        qualityBtn.text = "JPEG $jpegQuality%"
        qualityBtn.isEnabled = saveFormat != SaveFormat.RAW
        processBtn.text = if (naturalJpeg) "JPEG natural" else "JPEG estándar"
        processBtn.isEnabled = saveFormat != SaveFormat.RAW
        val interval = intervals[intervalIndex]
        intervalBtn.text = if (interval == 0) "Intervalo: no" else "Intervalo: ${interval}s"
        shotsBtn.text = "Tomas: ${shotCounts[shotCountIndex]}"
        shotsBtn.isEnabled = interval > 0
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
        if (intervalRunning) {
            stopInterval()
            return
        }
        if (counting) {
            cancelCountdown()
            return
        }
        if (busy) return
        if (intervals[intervalIndex] > 0) {
            startInterval()
            return
        }
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

    /** Fires one capture (with the current drive mode) every interval; a tick is skipped while a capture is running. */
    private fun startInterval() {
        intervalRunning = true
        shotsTaken = 0
        intervalStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        ui.post(intervalTick)
        updateShutterIdle()
    }

    private val intervalTick = object : Runnable {
        override fun run() {
            if (!intervalRunning) return
            if (shotsTaken >= shotCounts[shotCountIndex]) {
                stopInterval()
                toast("Intervalómetro terminado: $shotsTaken tomas")
                return
            }
            if (!busy) {
                shotsTaken++
                val shot = shotsTaken
                handler.post {
                    intervalShot = shot
                    startSequence()
                }
                updateShutterIdle()
            }
            ui.postDelayed(this, intervals[intervalIndex] * 1000L)
        }
    }

    private fun stopInterval() {
        ui.removeCallbacks(intervalTick)
        intervalRunning = false
        handler.post { intervalShot = 0 }
        updateShutterIdle()
    }

    private fun updateShutterIdle() {
        if (intervalRunning) {
            shutter.isEnabled = true
            shutter.text = "Detener intervalo ($shotsTaken/${shotCounts[shotCountIndex]})"
        } else if (!busy) {
            shutter.isEnabled = true
            shutter.text = SHUTTER_LABEL
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

    private fun needsAnalysis() =
        histToggle.isChecked || zebraToggle.isChecked || peakToggle.isChecked || lensToggle.isChecked

    private val grabTick = object : Runnable {
        override fun run() {
            if (needsAnalysis() && preview.isAvailable && !analyzing) {
                preview.getBitmap(grabBitmap)
                grabBitmap.getPixels(pixels, 0, ANALYSIS_W, 0, 0, ANALYSIS_W, ANALYSIS_H)
                analyzing = true
                val hist = histToggle.isChecked
                val zebra = zebraToggle.isChecked
                val peak = peakToggle.isChecked
                val lensCheck = lensToggle.isChecked
                analysisHandler.post {
                    try {
                        analyze(hist, zebra, peak, lensCheck)
                    } finally {
                        analyzing = false
                    }
                }
            }
            ui.postDelayed(this, ANALYSIS_INTERVAL_MS)
        }
    }

    /** Luma histogram, diagonal-stripe zebras on clipped pixels and gradient-based focus peaking. */
    private fun analyze(hist: Boolean, zebra: Boolean, peak: Boolean, lensCheck: Boolean) {
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
        val lens = if (lensCheck) lensStats(w, h) else null
        runOnUiThread {
            if (needsAnalysis()) overlay.setAnalysis(histogram, if (zebra || peak) mask else null)
            if (lens != null && lensToggle.isChecked) onLensStats(lens)
        }
    }

    /**
     * Heuristic smudge metrics on the downscaled preview:
     *  - haze: 10th percentile of the 8×8-block dark channel (min RGB). A greasy lens adds veiling
     *    light, so even the darkest regions stop reaching black.
     *  - contrast: strongest edges relative to the tonal range. Smudges soften every edge.
     *  - glow: brightness 20 px away from clipped point lights whose surroundings (48 px) are dark.
     *    A clean lens falls off fast; a smudged one spreads a halo. Negative when not measurable.
     */
    private fun lensStats(w: Int, h: Int): LensStats {
        val bs = 8
        val bw = w / bs
        val bh = h / bs
        val dark = IntArray(bw * bh) { 255 }
        val lumaHist = IntArray(256)
        val gradHist = IntArray(511)
        var lumaSum = 0L

        for (y in 0 until h) {
            val by = (y / bs).coerceAtMost(bh - 1)
            for (x in 0 until w) {
                val i = y * w + x
                val p = pixels[i]
                val mn = min(min((p shr 16) and 0xFF, (p shr 8) and 0xFF), p and 0xFF)
                val bi = by * bw + (x / bs).coerceAtMost(bw - 1)
                if (mn < dark[bi]) dark[bi] = mn
                val l = luma[i]
                lumaHist[l]++
                lumaSum += l
                if (x in 1 until w - 1 && y in 1 until h - 1) {
                    gradHist[abs(luma[i + 1] - luma[i - 1]) + abs(luma[i + w] - luma[i - w])]++
                }
            }
        }

        dark.sort()
        val haze = dark[dark.size / 10] / 255f
        val range = percentile(lumaHist, 0.99) - percentile(lumaHist, 0.01) + 1f
        val contrast = percentile(gradHist, 0.995) / (2f * range)

        var glowSum = 0f
        var glowCount = 0
        val near = 20
        val far = 48
        val dirs = intArrayOf(1, -1, w, -w)
        var y = far
        while (y < h - far) {
            var x = far
            while (x < w - far) {
                val i = y * w + x
                if (luma[i] >= 250) {
                    for (d in dirs) {
                        if (luma[i + d * far] < 60) {
                            glowSum += luma[i + d * near]
                            glowCount++
                        }
                    }
                }
                x += 4
            }
            y += 4
        }
        val glow = if (glowCount >= 20) glowSum / glowCount / 255f else -1f

        return LensStats(haze, contrast, glow, lumaSum.toFloat() / (w * h) / 255f)
    }

    private fun percentile(hist: IntArray, q: Double): Int {
        val target = (hist.sum() * q).toLong()
        var acc = 0L
        for (i in hist.indices) {
            acc += hist[i]
            if (acc >= target) return i
        }
        return hist.size - 1
    }

    /** Smooths per-frame verdicts over a few seconds, with hysteresis, before warning. */
    private fun onLensStats(s: LensStats) {
        lensDebug.visibility = View.VISIBLE
        val glowText = if (s.glow < 0) "-" else String.format(Locale.US, "%.2f", s.glow)
        lensDebug.text = String.format(Locale.US, "velo %.2f · contraste %.2f · halo %s", s.haze, s.contrast, glowText)

        // Skip frames that can't be judged: too dark, or not in focus (blur would look like a smudge).
        if (s.meanLuma < 0.08f || !afFocused) return

        val hazy = s.haze > HAZE_LIMIT && s.contrast < CONTRAST_LIMIT
        val glowy = s.glow > GLOW_LIMIT
        val verdict = if (hazy || glowy) 1f else 0f
        dirtyScore += (verdict - dirtyScore) * 0.08f

        if (dirtyScore > 0.6f) {
            val reason = when {
                hazy && glowy -> "velo y halos"
                glowy -> "halos en las luces"
                else -> "imagen lechosa"
            }
            lensWarning.text = "⚠ Lente posiblemente sucio ($reason) — limpialo"
            lensWarning.visibility = View.VISIBLE
        } else if (dirtyScore < 0.3f) {
            lensWarning.visibility = View.GONE
        }
    }

    private fun formatDistance(m: Float): String = when {
        m >= 20f -> "∞"
        m >= 10f -> String.format(Locale.US, "%.0f m", m)
        m >= 1f -> String.format(Locale.US, "%.1f m", m)
        else -> String.format(Locale.US, "%.0f cm", m * 100)
    }

    /** Subject distance and depth of field from the focus result (diopters; 0 = infinity). */
    private fun distanceText(diopters: Float?, range: android.util.Pair<Float, Float>?, focused: Boolean): String {
        if (diopters == null) return "Distancia: —"
        val subject = if (diopters <= 0.05f) "∞ (> 20 m)" else formatDistance(1f / diopters)
        val approx = if (focusCalibrated) "" else " (aprox.)"
        val sb = StringBuilder(if (focused) "Sujeto: $subject$approx" else "Enfocando… $subject")
        if (range != null && focused) {
            val nearM = if (range.first <= 0.05f) 20f else 1f / range.first
            val farM = if (range.second <= 0.05f) 20f else 1f / range.second
            sb.append("\nNítido: ").append(formatDistance(nearM)).append(" – ").append(formatDistance(farM))
        }
        return sb.toString()
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
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, MAX_IN_FLIGHT + 2).apply {
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { onJpeg(it) } }, handler)
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
        pendingJpegs.values.forEach { it.close() }
        pendingJpegs.clear()
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

    /** Focus, white balance and zoom shared by preview and still requests. */
    private fun CaptureRequest.Builder.applyCommon() {
        applyFocus()
        applyWhiteBalance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && zoomRatio != 1f) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio)
        }
    }

    private fun CaptureRequest.Builder.applyWhiteBalance() {
        val transform = lastTransform
        if (wbMode == Wb.KELVIN && transform != null) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, transform)
            set(CaptureRequest.COLOR_CORRECTION_GAINS, kelvinGains(kelvin))
        } else {
            // Kelvin falls back to auto until a color transform has been observed.
            val awb = if (wbMode == Wb.KELVIN) CaptureRequest.CONTROL_AWB_MODE_AUTO else wbMode.awb
            set(CaptureRequest.CONTROL_AWB_MODE, awb)
        }
    }

    /** White-balance gains that neutralise a light source of the given colour temperature (Tanner Helland fit). */
    private fun kelvinGains(k: Int): RggbChannelVector {
        val t = k / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b = when {
            t >= 66 -> 255.0
            t <= 19 -> 0.0
            else -> 138.5177312231 * ln(t - 10) - 305.0447927307
        }
        val rc = r.coerceIn(1.0, 255.0)
        val gc = g.coerceIn(1.0, 255.0)
        val bc = b.coerceIn(1.0, 255.0)
        val rGain = (gc / rc).toFloat().coerceIn(0.1f, 8f)
        val bGain = (gc / bc).toFloat().coerceIn(0.1f, 8f)
        return RggbChannelVector(rGain, 1f, 1f, bGain)
    }

    private fun CaptureRequest.Builder.applyFocus() {
        val region = focusRegion
        if (manualFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters)
        } else if (region != null && maxAfRegions > 0) {
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
            applyCommon()
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
            if (result.get(CaptureResult.CONTROL_AWB_MODE) != CaptureResult.CONTROL_AWB_MODE_OFF) {
                val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                val firstTransform = lastTransform == null && transform != null
                if (transform != null) lastTransform = transform
                if (firstTransform && wbMode == Wb.KELVIN) handler.post { refreshPreview() }
            }
            val afState = result.get(CaptureResult.CONTROL_AF_STATE)
            afFocused = manualFocus ||
                afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED ||
                afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED

            if (frameCount++ % 10 != 0) return
            val shot = baseExposure()
            val text = "${modeLabel()} · Visor: ISO $iso · ${formatExposure(time)}\n" +
                "Foto: ISO ${shot.iso} · ${formatExposure(shot.time)}"
            val distance = distanceText(
                result.get(CaptureResult.LENS_FOCUS_DISTANCE),
                result.get(CaptureResult.LENS_FOCUS_RANGE),
                afFocused,
            )
            runOnUiThread {
                info.text = text
                if (distanceToggle.isChecked) {
                    distanceBadge.text = distance
                    distanceBadge.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun stillRequest(e: Exposure, frame: Frame): CaptureRequest =
        device!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            if (frame.withRaw) addTarget(rawReader!!.surface)
            if (frame.withJpeg) addTarget(jpegReader!!.surface)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            applyCommon()
            if (frame.infinity) {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
            }
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, e.iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, e.time)
            set(CaptureRequest.SENSOR_FRAME_DURATION, e.time)
            set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            set(CaptureRequest.JPEG_QUALITY, jpegQuality.toByte())
            frame.location?.let { set(CaptureRequest.JPEG_GPS_LOCATION, it) }
            if (naturalJpeg) {
                // Natural look: minimal denoise (falls back to off) and no sharpening.
                val nr = when {
                    CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL in nrModes -> CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF in nrModes -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
                    else -> CaptureRequest.NOISE_REDUCTION_MODE_FAST
                }
                set(CaptureRequest.NOISE_REDUCTION_MODE, nr)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            } else {
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }
            setTag(frame)
        }.build()

    // ---- Capture sequence (handler thread) ----

    private fun startSequence() {
        if (busy || session == null || device == null) return
        val drive = drives[driveIndex]
        val baseExp = baseExposure()
        stamp = if (intervalShot > 0) {
            String.format(Locale.US, "%s_t%04d", intervalStamp, intervalShot)
        } else {
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        }

        // Stacks are always RAW; bursts and brackets stay RAW-only unless the user chose JPEG only.
        val isStack = drive.kind == DriveKind.STACK
        val jpegOnly = saveFormat == SaveFormat.JPEG && !isStack
        val withJpeg = jpegOnly || (drive.kind == DriveKind.SINGLE && saveFormat == SaveFormat.BOTH)
        val location = if (useLocation) lastLocation else null

        val requests = try {
            drive.evs.mapIndexed { i, ev ->
                val base = when (drive.kind) {
                    DriveKind.SINGLE -> stamp
                    DriveKind.BURST -> String.format(Locale.US, "%s_b%02d", stamp, i + 1)
                    DriveKind.BRACKET -> String.format(Locale.US, "%s_ev%+.1f", stamp, ev)
                    DriveKind.STACK -> stamp
                }
                val e = if (ev == 0.0) baseExp else shifted(baseExp, ev)
                stillRequest(e, Frame(base, !jpegOnly, withJpeg, location, stack = isStack, infinity = drive.infinity))
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
        stackSequence = isStack
        stackBase = stamp
        if (isStack) io.execute { resetStack() }
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

    private fun onJpeg(image: Image) {
        if (!busy) {
            image.close()
            return
        }
        pendingJpegs[image.timestamp] = image
        tryPair(image.timestamp)
    }

    /** A frame is saved once its metadata and every output it requested (RAW and/or JPEG) have arrived. */
    private fun tryPair(ts: Long) {
        val result = pendingResults[ts] ?: return
        val frame = result.request.tag as? Frame ?: return
        val raw = if (frame.withRaw) (pendingImages[ts] ?: return) else null
        val jpeg = if (frame.withJpeg) (pendingJpegs[ts] ?: return) else null
        pendingResults.remove(ts)
        pendingImages.remove(ts)
        pendingJpegs.remove(ts)
        io.execute {
            var ok = true
            if (raw != null) ok = if (frame.stack) accumulate(raw, result, frame) else saveDng(raw, result, frame)
            if (jpeg != null) ok = saveJpeg(jpeg, "IMG_${frame.base}.jpg") && ok
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
        pendingJpegs.values.forEach { it.close() }
        pendingJpegs.clear()
        pendingResults.clear()
        queue.clear()
        val saved = total - failed
        when {
            stackSequence -> {
                toast("Promediando $saved cuadros…")
                val base = stackBase
                io.execute { writeStack(base) }
            }
            intervalShot > 0 -> Unit
            total == 1 && failed == 0 -> toast("Guardado en DCIM/RawCam")
            total == 1 -> toast("Falló la captura")
            else -> toast("Guardadas $saved/$total en DCIM/RawCam")
        }
        stackSequence = false
        runOnUiThread { updateShutterIdle() }
    }

    private fun showProgress() {
        val text = if (total > 1) "Capturando ${done + 1}/$total…" else "Capturando…"
        runOnUiThread {
            if (intervalRunning) {
                shutter.isEnabled = true
                shutter.text = "Detener intervalo ($shotsTaken/${shotCounts[shotCountIndex]}) · $text"
            } else {
                shutter.isEnabled = false
                shutter.text = text
            }
        }
    }

    // ---- Stacking (io thread) ----

    private fun resetStack() {
        stackSum = null
        stackCount = 0
        stackResult = null
        stackLocation = null
    }

    /** Adds one RAW_SENSOR frame (16-bit little-endian, possibly row-padded) to the running sum. */
    private fun accumulate(image: Image, result: TotalCaptureResult, frame: Frame): Boolean {
        return try {
            val w = image.width
            val h = image.height
            var sum = stackSum
            if (sum == null || w != stackWidth || h != stackHeight) {
                sum = IntArray(w * h)
                stackSum = sum
                stackWidth = w
                stackHeight = h
                stackCount = 0
                stackResult = result
                stackLocation = frame.location
            }
            val plane = image.planes[0]
            val shorts = plane.buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            val rowShorts = plane.rowStride / 2
            val row = ShortArray(w)
            for (y in 0 until h) {
                shorts.position(y * rowShorts)
                shorts.get(row, 0, w)
                val offset = y * w
                for (x in 0 until w) sum[offset + x] += row[x].toInt() and 0xFFFF
            }
            stackCount++
            true
        } catch (e: Exception) {
            toast("Stack: ${e.message}")
            false
        } finally {
            image.close()
        }
    }

    /** Writes the averaged frame as a DNG using the first frame's metadata. */
    private fun writeStack(base: String) {
        val sum = stackSum
        val result = stackResult
        val n = stackCount
        if (sum == null || result == null || n == 0) {
            toast("No hay cuadros para promediar")
            return
        }
        try {
            val pixels = ByteBuffer.allocateDirect(sum.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            val shorts = pixels.asShortBuffer()
            for (i in sum.indices) shorts.put(i, ((sum[i] + n / 2) / n).toShort())
            pixels.rewind()

            val name = "RAW_${base}_avg$n.dng"
            val uri = insertImage(name, "image/x-adobe-dng")
            contentResolver.openOutputStream(uri)!!.use { out ->
                val (dngChars, dngResult) = dngSource(result)
                DngCreator(dngChars, dngResult).use { dng ->
                    dng.setOrientation(exifOrientation())
                    stackLocation?.let { dng.setLocation(it) }
                    dng.writeByteBuffer(out, Size(stackWidth, stackHeight), pixels, 0)
                }
            }
            publish(uri)
            toast("Guardado $name")
        } catch (e: Exception) {
            toast("Stack DNG: ${e.message}")
        } finally {
            resetStack()
        }
    }

    // ---- Saving (io thread) ----

    private fun saveDng(image: Image, result: TotalCaptureResult, frame: Frame): Boolean {
        return try {
            val uri = insertImage("RAW_${frame.base}.dng", "image/x-adobe-dng")
            contentResolver.openOutputStream(uri)!!.use { out ->
                val (dngChars, dngResult) = dngSource(result)
                DngCreator(dngChars, dngResult).use { dng ->
                    dng.setOrientation(exifOrientation())
                    frame.location?.let { dng.setLocation(it) }
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

    private fun saveJpeg(image: Image, name: String): Boolean {
        return try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val uri = insertImage(name, "image/jpeg")
            contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            publish(uri)
            true
        } catch (e: Exception) {
            toast("JPEG: ${e.message}")
            false
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
        private const val REQUEST_LOCATION = 2
        private const val MAX_EXPOSURE_NS = 30_000_000_000L

        // Initial smudge thresholds; tune with the on-screen debug readout.
        private const val HAZE_LIMIT = 0.16f
        private const val CONTRAST_LIMIT = 0.22f
        private const val GLOW_LIMIT = 0.50f
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
