package ai.bridge.app

import android.content.Context
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import ai.bridge.BridgeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class VisionFrame(val bytes: ByteArray, val mimeType: String, val capturedAt: Long = SystemClock.elapsedRealtime())
internal fun interface VisionSource { suspend fun capture(): VisionFrame }

internal class FlipperScreenshotVisionSource(
    private val client: FlipperRpcGattClient,
    private val capabilities: () -> DeviceCapabilities,
) : VisionSource {
    override suspend fun capture(): VisionFrame = withContext(Dispatchers.IO) {
        val deviceCaps = capabilities()
        if(!deviceCaps.screenshotHelper) throw UnsupportedOperationException("Flipper screenshot helper is unavailable")
        val requestId = client.sendBridge(BridgeType.REQUEST_SCREENSHOT)
        val result = client.await(BridgeType.ACTION_RESULT, requestId, 30_000)
            ?: throw IllegalStateException("Flipper screenshot request timed out")
        val resultCode = result.payload.firstOrNull()?.toInt()?.and(0xFF)
        if(resultCode != 0) throw IllegalStateException("Flipper screenshot request ${when(resultCode) { 4 -> "unsupported"; 6 -> "busy"; else -> "failed" }}")
        val image = client.awaitScreenshot(requestId, 180_000)
            ?: throw IllegalStateException("No screenshot arrived from the Flipper helper")
        if(deviceCaps.maxFileSize > 0 && image.size > deviceCaps.maxFileSize) throw IllegalStateException("Flipper image exceeds its advertised transfer limit")
        VisionFrame(image, "image/png")
    }

}

/** CameraX produces a single compressed frame only when the agent asks for one. */
internal class CameraVisionSource(context: Context) : VisionSource {
    private val appContext = context.applicationContext
    private val captureExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var imageCapture: ImageCapture? = null

    fun bind(owner: LifecycleOwner, previewView: PreviewView, onReady: (Boolean) -> Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(appContext)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(82)
                    .build()
                provider.unbindAll()
                provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                imageCapture = capture
                onReady(true)
            } catch (_: Exception) {
                imageCapture = null
                onReady(false)
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun unbind() {
        imageCapture = null
        runCatching { ProcessCameraProvider.getInstance(appContext).get().unbindAll() }
    }

    fun close() { captureExecutor.shutdownNow(); unbind() }

    override suspend fun capture(): VisionFrame = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture
        if(capture == null) {
            continuation.resumeWithException(IllegalStateException("Enable phone camera preview first"))
            return@suspendCancellableCoroutine
        }
        val file = File(appContext.cacheDir, "vision-${System.currentTimeMillis()}.jpg")
        continuation.invokeOnCancellation { file.delete() }
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, captureExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                if(continuation.isActive) {
                    try {
                        val bytes = file.readBytes()
                        require(bytes.size <= ai.bridge.ScreenshotReceiver.MAX_FILE_SIZE) { "Camera image exceeds the 2 MiB vision limit" }
                        continuation.resume(VisionFrame(bytes, "image/jpeg"))
                    }
                    catch(error: Exception) { continuation.resumeWithException(error) }
                }
                file.delete()
            }
            override fun onError(exception: ImageCaptureException) {
                file.delete()
                if(continuation.isActive) continuation.resumeWithException(exception)
            }
        })
    }
}
