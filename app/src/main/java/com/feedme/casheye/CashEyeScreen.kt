package com.feedme.casheye

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@RequiresApi(Build.VERSION_CODES.P)
@Composable
fun CashEyeScreen(
    cashEyeViewModel: CashEyeViewModel = viewModel()
) {

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val previewView = remember { SurfaceView(context) }
    val surfaceHolder = previewView.holder
    val outputText = remember { mutableStateOf("") }
    val textToSpeech = remember { cashEyeViewModel.initTextToSpeech(context) }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            if (granted) {
                setupCamera(context, surfaceHolder, cameraExecutor)
            }
        }
    )

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            setupCamera(context, surfaceHolder, cameraExecutor)
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(
        modifier = Modifier.fillMaxHeight(0.5f) // Takes half the screen height
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(16.dp)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
        }

        Button(
            onClick = {
                captureImage(context, surfaceHolder, cameraExecutor, cashEyeViewModel, outputText)
            },
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(16.dp)
        ) {
            Text(text = stringResource(R.string.action_capture))
        }

        if (outputText.value.isNotEmpty()) {
            Text(
                text = outputText.value,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            )
            textToSpeech.speak(outputText.value, TextToSpeech.QUEUE_FLUSH, null, "")
        }
    }
}

private var cameraDevice: CameraDevice? = null
private var captureSession: CameraCaptureSession? = null
private lateinit var captureRequestBuilder: CaptureRequest.Builder

@RequiresApi(Build.VERSION_CODES.P)
private fun setupCamera(
    context: Context,
    surfaceHolder: SurfaceHolder,
    cameraExecutor: ExecutorService
) {
    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraId = cameraManager.cameraIdList[1] // Use the first camera (usually the rear camera)
    val handlerThread = HandlerThread("CameraBackground").apply { start() }
    val handler = Handler(handlerThread.looper)

    try {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        cameraManager.openCamera(cameraId, ContextCompat.getMainExecutor(context), object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCameraPreviewSession(camera, surfaceHolder, handler)
            }

            override fun onDisconnected(camera: CameraDevice) {
                cameraDevice?.close()
                cameraDevice = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                cameraDevice?.close()
                cameraDevice = null
            }
        })
    } catch (e: CameraAccessException) {
        Log.e("Camera2", "Failed to open camera", e)
    }
}

private lateinit var imageReader: ImageReader

private fun createCameraPreviewSession(camera: CameraDevice, surfaceHolder: SurfaceHolder, handler: Handler) {
    val previewSurface = surfaceHolder.surface

    // Initialize ImageReader before session creation
    imageReader = ImageReader.newInstance(
        surfaceHolder.surfaceFrame.width(),
        surfaceHolder.surfaceFrame.height(),
        ImageFormat.JPEG,
        1
    )

    captureRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
    captureRequestBuilder.addTarget(previewSurface)

    camera.createCaptureSession(
        listOf(previewSurface, imageReader.surface), // Add ImageReader surface here
        object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                captureSession = session
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                try {
                    session.setRepeatingRequest(captureRequestBuilder.build(), null, handler)
                } catch (e: CameraAccessException) {
                    Log.e("Camera2", "Failed to start camera preview", e)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                Log.e("Camera2", "Failed to configure camera preview session")
            }
        }, handler
    )
}

private fun captureImage(
    context: Context,
    surfaceHolder: SurfaceHolder,
    executor: ExecutorService,
    cashEyeViewModel: CashEyeViewModel,
    outputText: MutableState<String>
) {
    if (cameraDevice == null || captureSession == null || !surfaceHolder.surface.isValid) {
        Log.e("CameraCapture", "Camera device, session, or surface is not valid")
        return
    }

    val handler = Handler(Looper.getMainLooper())

    // Set up ImageReader listener
    imageReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.capacity())
        buffer.get(bytes)
        image.close()

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            Log.e("BitmapError", "Failed to decode image into a bitmap.")
            return@setOnImageAvailableListener
        }
        cashEyeViewModel.processImage(bitmap) { detectedText ->
            outputText.value = detectedText
        }
    }, handler)

    val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
    captureRequestBuilder.addTarget(imageReader.surface) // Use ImageReader's surface for capture

    try {
        captureSession!!.stopRepeating()
        captureSession!!.abortCaptures()
        captureSession!!.capture(captureRequestBuilder.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                createCameraPreviewSession(cameraDevice!!, surfaceHolder, handler) // Restart the preview
            }
        }, handler)
    } catch (e: CameraAccessException) {
        Log.e("Camera2", "Failed to capture image", e)
    }
}

fun createFile(context: Context): File {
    val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
    return File.createTempFile(
        "JPEG_${timeStamp}_", /* prefix */
        ".jpg", /* suffix */
        storageDir /* directory */
    )
}
