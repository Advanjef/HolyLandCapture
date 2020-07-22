package jp.ac.titech.itpro.sdl.holylandcapture

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.hardware.camera2.*
import android.location.Location
import android.location.LocationManager
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class TakePictureActivity : AppCompatActivity() {
    private lateinit var textureView: TextureView
    private var captureSession: CameraCaptureSession? = null
    private var cameraDevice: CameraDevice? = null
    private val previewSize: Size = Size(1920, 1080)
    private lateinit var previewRequestBuilder: CaptureRequest.Builder
    private var imageReader: ImageReader? = null
    private lateinit var previewRequest: CaptureRequest
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private lateinit var manager: CameraManager
    private lateinit var selectPictureButton: ImageButton
    private lateinit var alphaSeekBar: SeekBar
    private lateinit var imageView: ImageView
    private lateinit var takePictureButton: ImageButton
    private lateinit var jpegImageReader: ImageReader
    private lateinit var locationManager: LocationManager
    private lateinit var crrLocation: Location

    companion object {
        private const val READ_REQUEST_CODE: Int = 42
        private const val REQUEST_SAVE_IMAGE = 1002
        private const val FILE_PREFIX = "CAMERA_"
        private const val FILE_EXT = ".jpg"
        private const val DATE_FORMAT = "yyyy_MM_dd_HH_mm_ss_SSS"
        private const val JPEG_QUALITY = 100
        private const val REQUEST_EXTERNAL_STORAGE_CODE = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_take_picture)

        textureView = findViewById(R.id.texture_view)
        textureView.surfaceTextureListener = surfaceTextureListener

        selectPictureButton = findViewById(R.id.select_image)
        selectPictureButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(intent, READ_REQUEST_CODE)
        }

        imageView = findViewById(R.id.image_view)

        alphaSeekBar = findViewById(R.id.alpha_seek)
        alphaSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                imageView.alpha = progress / 100F
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                // ツマミがタッチされた時に呼ばれる
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                // ツマミがリリースされた時に呼ばれる
            }
        })
        verifyStoragePermissions(this)
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
            requestCameraPermission()
            return
        }

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        crrLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);

        jpegImageReader = ImageReader.newInstance(4032, 2268, ImageFormat.JPEG, 1)

        takePictureButton = findViewById(R.id.Shutter)

        takePictureButton.setOnClickListener {
            try {
                if (textureView.isAvailable) {
                    val file = getOutputFile()
                    val output = FileOutputStream(file)
                    val bitmap = textureView.bitmap
                    val width = bitmap.width.toFloat()
                    val height = bitmap.height.toFloat()
                    val matrix = Matrix()
                    matrix.preScale(height/width, width/height)
                    matrix.postRotate(270F)
                    val rBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    rBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    output.close()
                    val contentValues = ContentValues().apply {
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put("_data", file.absolutePath)
                    }

                    contentResolver.insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    val context = applicationContext
                    Toast.makeText(context, "Saved to " + file.absolutePath, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        startBackgroundThread()
    }

    private fun openCamera() {
        manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        configureTransform(previewSize.width, previewSize.height)

        try {
            var cameraId: String = manager.cameraIdList[0]
//            var cameraId: String =

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestCameraPermission()
                return
            }
            manager.openCamera(cameraId, stateCallback, null);
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            this@TakePictureActivity.cameraDevice = cameraDevice

            createCameraPreviewSession()
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraDevice.close()
            this@TakePictureActivity.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            onDisconnected(cameraDevice)
            finish()
        }

    }

    private fun createCameraPreviewSession() {
        try {
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(textureView.width, textureView.height)
            val surface = Surface(texture)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(
                CameraDevice.TEMPLATE_PREVIEW
            )
            previewRequestBuilder.addTarget(surface)


            cameraDevice?.createCaptureSession(
                listOf(surface, imageReader?.surface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {

                        if (cameraDevice == null) return
                        captureSession = cameraCaptureSession
                        try {
                            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            previewRequest = previewRequestBuilder.build()
                            captureSession?.setRepeatingRequest(previewRequest,
                                null, Handler(backgroundThread?.looper))
                        } catch (e: CameraAccessException) {
                            Log.e("erfs", e.toString())
                        }

                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        //Tools.makeToast(baseContext, "Failed")
                    }
                }, null)
        } catch (e: CameraAccessException) {
            Log.e("erf", e.toString())
        }

    }

    private fun requestCameraPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            AlertDialog.Builder(baseContext)
                .setMessage("Permission Here")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.CAMERA),
                        200)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .create()
        } else {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 200)
        }
    }

    private fun requestLocationPermission() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION)) {
            AlertDialog.Builder(baseContext)
                .setMessage("Permission Here")
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                        300)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    finish()
                }
                .create()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 300)
        }
    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground").also { it.start() }
        backgroundHandler = Handler(backgroundThread?.looper)
    }

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            imageReader = ImageReader.newInstance(4032, 2268,
                ImageFormat.JPEG, /*maxImages*/ 2);

            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture?, p1: Int, p2: Int) {

        }

        override fun onSurfaceTextureUpdated(p0: SurfaceTexture?) {

        }

        override fun onSurfaceTextureDestroyed(p0: SurfaceTexture?): Boolean {
            return false
        }
    }

    private fun configureTransform(width: Int, height: Int) {
        val rectView = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val rectPreview = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = rectView.centerX()
        val centerY = rectView.centerY()
        val matrix = Matrix()

        when (val rotation = windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                rectPreview.offset(centerX - rectPreview.centerX(), centerY - rectPreview.centerY())

                val scale =
                    (height.toFloat() / previewSize.height).coerceAtLeast(width.toFloat() / previewSize.width)

                with(matrix) {
                    setRectToRect(rectView, rectPreview, Matrix.ScaleToFit.FILL)
                    postScale(scale, scale, centerX, centerY)
                    postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
                }
            }
            Surface.ROTATION_180 -> {
                matrix.postRotate(180f, centerX, centerY)
            }
        }

        textureView.setTransform(matrix)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (resultCode != RESULT_OK) {
            return
        }
        when (requestCode) {
            READ_REQUEST_CODE -> {
                try {
                    resultData?.data?.also { uri ->
                        val inputStream = contentResolver?.openInputStream(uri)
                        val image = BitmapFactory.decodeStream(inputStream)
                        val imageView = findViewById<ImageView>(R.id.image_view)
                        imageView.setImageBitmap(image)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "エラーが発生しました", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun getOutputFile(): File {
        val filename = getOutputFileName(FILE_PREFIX, FILE_EXT)
        verifyStoragePermissions(this)
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), filename)
    }

    private fun getOutputFileName(prefix: String, ext: String): String {
        val sdf = SimpleDateFormat(DATE_FORMAT, Locale.JAPAN)
        val currentDateTime = sdf.format(Date())
        return prefix + currentDateTime + ext
    }

    private fun verifyStoragePermissions(activity: AppCompatActivity) {
        val readPermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE)
        val writePermission = ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (writePermission != PackageManager.PERMISSION_GRANTED || readPermission != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQUEST_EXTERNAL_STORAGE_CODE
            )
        }
    }
}