package com.example.cameratest

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var viewFinder: PreviewView
    private lateinit var imageCaptureButton: ImageButton
    private lateinit var flashToggle: ImageButton
    private lateinit var photoPreviewImageView: ImageView
    private lateinit var keepPhotoButton: Button
    private lateinit var retakePhotoButton: Button
    private lateinit var galleryButton: ImageButton

    private var imageCapture: ImageCapture? = null
    private var isFlashEnabled = false
    private var lastCapturedImageUri: Uri? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            var permissionGranted = true
            permissions.entries.forEach { entry ->
                if (entry.key in REQUIRED_PERMISSIONS && entry.value.not()) {
                    permissionGranted = false
                }
            }

            if (permissionGranted) {
                setupCameraView()
                startCamera()
            } else {
                Toast.makeText(
                    this,
                    "Permissions are required to use the camera.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setupCameraView()
        requestCameraPermissions()

        cameraExecutor = Executors.newSingleThreadExecutor()
    }

    private fun setupCameraView() {
        viewFinder = findViewById(R.id.viewFinder)
        imageCaptureButton = findViewById(R.id.imageCaptureButton)
        flashToggle = findViewById(R.id.flashToggle)
        photoPreviewImageView = findViewById(R.id.photoPreviewImageView)
        keepPhotoButton = findViewById(R.id.keepPhotoButton)
        retakePhotoButton = findViewById(R.id.retakePhotoButton)
        galleryButton = findViewById(R.id.galleryButton) //Optional

        // Initially hide preview and buttons
        photoPreviewImageView.visibility = ImageView.GONE
        keepPhotoButton.visibility = Button.GONE
        retakePhotoButton.visibility = Button.GONE

        // Set up listeners
        imageCaptureButton.setOnClickListener { takePhoto() }
        flashToggle.setOnClickListener { toggleFlash() }
        keepPhotoButton.setOnClickListener { saveAndExit() }
        retakePhotoButton.setOnClickListener { resetToCamera() }
        galleryButton.setOnClickListener { openGallery() }
    }

    private fun requestCameraPermissions() {
        when {
            allPermissionsGranted() -> startCamera()
            else -> permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()
                .also {
                    it.setSurfaceProvider(viewFinder.surfaceProvider)
                }

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageCapture)
            } catch(exc: Exception) {
                Toast.makeText(
                    baseContext,
                    "Failed to start camera: ${exc.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
            .format(System.currentTimeMillis())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if(Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions
            .Builder(
                contentResolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Toast.makeText(
                        baseContext,
                        "Photo capture failed: ${exc.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    lastCapturedImageUri = output.savedUri

                    runOnUiThread {
                        lastCapturedImageUri?.let { uri ->
                            try {
                                // Use BitmapFactory with options to handle rotation and sampling
                                val options = BitmapFactory.Options().apply {
                                    inJustDecodeBounds = true
                                }

                                contentResolver.openInputStream(uri)?.use { inputStream ->
                                    BitmapFactory.decodeStream(inputStream, null, options)
                                }

                                // Calculate sample size to reduce memory usage
                                options.inSampleSize = calculateInSampleSize(options, 1024, 1024)
                                options.inJustDecodeBounds = false

                                // Decode bitmap
                                val originalBitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                                    BitmapFactory.decodeStream(inputStream, null, options)
                                }

                                // Handle rotation
                                val rotationMatrix = Matrix()
                                rotationMatrix.postRotate(90f)  // Adjust rotation as needed

                                val rotatedBitmap = originalBitmap?.let { bitmap ->
                                    Bitmap.createBitmap(
                                        bitmap,
                                        0, 0,
                                        bitmap.width, bitmap.height,
                                        rotationMatrix,
                                        true
                                    )
                                }

                                rotatedBitmap?.let {
                                    photoPreviewImageView.setImageBitmap(it)
                                }

                                // Switch to preview mode
                                viewFinder.visibility = PreviewView.GONE
                                imageCaptureButton.visibility = ImageButton.GONE
                                flashToggle.visibility = ImageButton.GONE

                                photoPreviewImageView.visibility = ImageView.VISIBLE
                                keepPhotoButton.visibility = Button.VISIBLE
                                retakePhotoButton.visibility = Button.VISIBLE

                            } catch (e: Exception) {
                                Toast.makeText(
                                    baseContext,
                                    "Failed to load image: ${e.message}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        )
    }

    // Add these methods to your class
    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / inSampleSize) >= reqHeight &&
                (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun toggleFlash() {
        imageCapture?.let { imageCapture ->
            // Check if flash is available
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
                isFlashEnabled = !isFlashEnabled
                imageCapture.flashMode = if (isFlashEnabled) {
                    ImageCapture.FLASH_MODE_ON
                } else {
                    ImageCapture.FLASH_MODE_OFF
                }

                // Update button appearance
                flashToggle.setImageResource(
                    if (isFlashEnabled) R.drawable.ic_flash_on
                    else R.drawable.ic_flash_off
                )
            } else {
                Toast.makeText(
                    this,
                    "Flash not available on this device",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun openGallery() {
        // Option 1: Open system gallery app
        val intent = Intent(Intent.ACTION_VIEW)
        intent.type = "image/*"
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK

        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "No gallery app found",
                Toast.LENGTH_SHORT
            ).show()
        }

        // Alternative Option 2: Open gallery in your app (more complex)
        // Have to implement galleryactivity function
        /*
        val intent = Intent(this, GalleryActivity::class.java)
        startActivity(intent)
        */
    }

    private fun saveAndExit() {
        // The image is already saved, so just finish the activity
//        Toast.makeText(this, "Photo saved successfully", Toast.LENGTH_SHORT).show()
//        resetToCamera()
//        finish()
        lastCapturedImageUri?.let { uri ->
            try {
                // Get the bitmap with correct rotation
                val bitmap = MediaStore.Images.Media.getBitmap(contentResolver, uri)

                // Create rotation matrix
                val matrix = Matrix()
                matrix.postRotate(90f)  // Adjust rotation as needed

                // Create rotated bitmap
                val rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0,
                    bitmap.width, bitmap.height,
                    matrix, true
                )

                val displayName = SimpleDateFormat(FILENAME_FORMAT, Locale.US)
                    .format(System.currentTimeMillis())

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CameraX-Image")
                    }
                }

                val newUri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    contentValues
                )

                newUri?.let { insertedUri ->
                    contentResolver.openOutputStream(insertedUri)?.use { outputStream ->
                        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                    }

                    Toast.makeText(
                        this,
                        "Photo saved successfully",
                        Toast.LENGTH_SHORT
                    ).show()

                    resetToCamera()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Failed to save photo: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } ?: run {
            Toast.makeText(
                this,
                "No photo to save",
                Toast.LENGTH_SHORT
            ).show()
        }
        }



    private fun resetToCamera() {
        // Delete the last captured image
        lastCapturedImageUri?.let { uri ->
            contentResolver.delete(uri, null, null)
        }

        // Reset UI
        viewFinder.visibility = PreviewView.VISIBLE
        imageCaptureButton.visibility = ImageButton.VISIBLE
        flashToggle.visibility = ImageButton.VISIBLE

        photoPreviewImageView.visibility = ImageView.GONE
        keepPhotoButton.visibility = Button.GONE
        retakePhotoButton.visibility = Button.GONE

        // Clear preview image
        photoPreviewImageView.setImageBitmap(null)
    }



    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P)
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }.toTypedArray()
    }
}