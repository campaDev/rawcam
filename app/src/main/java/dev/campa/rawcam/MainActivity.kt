package dev.campa.rawcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var preview: TextureView
    private lateinit var info: TextView
    private lateinit var autoSwitch: Switch
    private lateinit var isoLabel: TextView
    private lateinit var isoBar: SeekBar
    private lateinit var expLabel: TextView
    private lateinit var expBar: SeekBar
    private lateinit var shutter: Button

    private val manager by lazy { getSystemService(CameraManager::class.java) }
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)

    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private lateinit var isoRange: Range<Int>
    private lateinit var expRange: Range<Long>
    private var sensorOrientation = 90

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var rawReader: ImageReader? = null
    private var jpegReader: ImageReader? = null

    @Volatile private var manual = false
    @Volatile private var isoValue = 100
    @Volatile private var expValue = 10_000_000L

    // RAW image and its capture metadata arrive separately; DngCreator needs both.
    private var pendingRaw: Image? = null
    private var pendingResult: TotalCaptureResult? = null
    private var stamp = ""
    private var frameCount = 0
    private var askedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preview = findViewById(R.id.preview)
        info = findViewById(R.id.info)
        autoSwitch = findViewById(R.id.autoSwitch)
        isoLabel = findViewById(R.id.isoLabel)
        isoBar = findViewById(R.id.isoBar)
        expLabel = findViewById(R.id.expLabel)
        expBar = findViewById(R.id.expBar)
        shutter = findViewById(R.id.shutter)

        preview.layoutParams = preview.layoutParams.apply {
            height = resources.displayMetrics.widthPixels * 4 / 3
        }

        if (!selectCamera()) {
            toast("No hay cámara trasera con soporte RAW")
            finish()
            return
        }

        val bars = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = syncControls()
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        }
        isoBar.setOnSeekBarChangeListener(bars)
        expBar.setOnSeekBarChangeListener(bars)
        autoSwitch.setOnCheckedChangeListener { _, _ -> syncControls() }
        shutter.setOnClickListener { handler.post { capture() } }
        syncControls()
    }

    override fun onResume() {
        super.onResume()
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
        closeCamera()
        super.onPause()
    }

    override fun onDestroy() {
        thread.quitSafely()
        super.onDestroy()
    }

    private fun startWhenReady() {
        if (preview.isAvailable) {
            openCamera()
            return
        }
        preview.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(t: SurfaceTexture, w: Int, h: Int) = openCamera()
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
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        return true
    }

    private fun syncControls() {
        manual = !autoSwitch.isChecked
        isoBar.isEnabled = manual
        expBar.isEnabled = manual

        isoValue = logLerp(isoRange.lower.toDouble(), isoRange.upper.toDouble(), isoBar.progress / 1000.0).roundToInt()
        val maxExp = min(expRange.upper, 1_000_000_000L).toDouble()
        expValue = logLerp(expRange.lower.toDouble(), maxExp, expBar.progress / 1000.0).toLong()

        isoLabel.text = "ISO $isoValue"
        expLabel.text = "Obturación ${formatExposure(expValue)}"
        handler.post { updatePreview() }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (device != null) return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!

        val rawSize = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
        val jpegSize = map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
            .filter { it.width * 3 == it.height * 4 && it.width <= 1920 }
            .maxByOrNull { it.width } ?: Size(1440, 1080)

        rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, ImageFormat.RAW_SENSOR, 2).apply {
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { onRaw(it) } }, handler)
        }
        jpegReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { saveJpeg(it) } }, handler)
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
                updatePreview()
            }

            override fun onConfigureFailed(s: CameraCaptureSession) = toast("No se pudo configurar la sesión")
        }, handler)
    }

    private fun CaptureRequest.Builder.applyExposure() {
        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        if (manual) {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, isoValue)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, expValue)
            set(CaptureRequest.SENSOR_FRAME_DURATION, expValue)
        } else {
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
            if (frameCount++ % 15 != 0) return
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
            val exp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            runOnUiThread { info.text = "Sensor: ISO $iso · ${formatExposure(exp)}" }
        }
    }

    private fun updatePreview() {
        val d = device ?: return
        val s = session ?: return
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(previewSurface!!)
                applyExposure()
            }.build()
            s.setRepeatingRequest(req, previewCallback, handler)
        } catch (e: Exception) {
            toast("Preview: ${e.message}")
        }
    }

    private fun capture() {
        val d = device ?: return
        val s = session ?: return
        runOnUiThread { shutter.isEnabled = false }
        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(rawReader!!.surface)
                addTarget(jpegReader!!.surface)
                applyExposure()
                set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
                set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
            }.build()

            s.capture(req, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    pendingResult = result
                    writeDngIfReady()
                }

                override fun onCaptureFailed(s: CameraCaptureSession, r: CaptureRequest, f: android.hardware.camera2.CaptureFailure) {
                    toast("Falló la captura")
                    runOnUiThread { shutter.isEnabled = true }
                }
            }, handler)
        } catch (e: Exception) {
            toast("Captura: ${e.message}")
            runOnUiThread { shutter.isEnabled = true }
        }
    }

    private fun onRaw(image: Image) {
        pendingRaw?.close()
        pendingRaw = image
        writeDngIfReady()
    }

    private fun writeDngIfReady() {
        val image = pendingRaw ?: return
        val result = pendingResult ?: return
        pendingRaw = null
        pendingResult = null

        try {
            val name = "RAW_$stamp.dng"
            val uri = insertImage(name, "image/x-adobe-dng")
            contentResolver.openOutputStream(uri)!!.use { out ->
                DngCreator(chars, result).use { dng ->
                    dng.setOrientation(exifOrientation())
                    dng.writeImage(out, image)
                }
            }
            publish(uri)
            toast("Guardado $name")
        } catch (e: Exception) {
            toast("DNG: ${e.message}")
        } finally {
            image.close()
            runOnUiThread { shutter.isEnabled = true }
        }
    }

    private fun saveJpeg(image: Image) {
        try {
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
            val uri = insertImage("IMG_$stamp.jpg", "image/jpeg")
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

    private fun closeCamera() {
        session?.close()
        session = null
        device?.close()
        device = null
        rawReader?.close()
        rawReader = null
        jpegReader?.close()
        jpegReader = null
        pendingRaw?.close()
        pendingRaw = null
        pendingResult = null
    }

    private fun logLerp(lo: Double, hi: Double, t: Double) = exp(ln(lo) + (ln(hi) - ln(lo)) * t)

    private fun formatExposure(ns: Long): String =
        if (ns >= 500_000_000L) "%.1fs".format(ns / 1e9)
        else "1/${(1e9 / ns).roundToInt()}s"

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}
