package com.soma.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Size
import android.view.Surface
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.soma.core.model.NoteEntry
import com.soma.media.EncryptedImageContainer
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CapturedPhoto(
    val jpegBytes: ByteArray,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
)

@Composable
fun CameraCaptureScreen(
    saving: Boolean,
    onCaptured: (CapturedPhoto) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(enabled = !saving, onBack = onBack)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) renders inside the view hierarchy and
            // stays within its Compose bounds. PERFORMANCE uses a SurfaceView
            // whose separate window layer painted over the back-arrow top bar,
            // hiding it once the live preview started.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val imageCapture = remember {
        @Suppress("DEPRECATION")
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setOutputFormat(ImageCapture.OUTPUT_FORMAT_JPEG)
            .setTargetResolution(Size(CAPTURE_WIDTH, CAPTURE_HEIGHT))
            .build()
    }
    var ready by remember { mutableStateOf(false) }
    var bindingFailed by remember { mutableStateOf(false) }
    var captureFailed by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner, previewView, imageCapture) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching {
                    val provider = future.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                    imageCapture.targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                    ready = true
                }.onFailure { bindingFailed = true }
            },
            mainExecutor,
        )
        onDispose {
            if (future.isDone) runCatching { future.get().unbindAll() }
            cameraExecutor.shutdownNow()
        }
    }

    fun takePhoto() {
        if (!ready || bindingFailed || saving || captured) return
        captureFailed = false
        ready = false
        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    try {
                        val plane = image.planes.firstOrNull() ?: error("Camera returned no JPEG plane")
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                        val capturedPhoto = CapturedPhoto(
                            jpegBytes = bytes,
                            width = image.width,
                            height = image.height,
                            rotationDegrees = image.imageInfo.rotationDegrees,
                        )
                        mainExecutor.execute {
                            captured = true
                            onCaptured(capturedPhoto)
                        }
                    } catch (_: Throwable) {
                        mainExecutor.execute {
                            captureFailed = true
                            ready = true
                        }
                    } finally {
                        image.close()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    mainExecutor.execute {
                        captureFailed = true
                        ready = true
                    }
                }
            },
        )
    }

    // The camera preview surface paints its whole allocated area and would cover
    // a top bar laid out above it. The bar is therefore drawn last, as an opaque
    // overlay on top of the preview, and the preview column reserves matching
    // space so nothing sits behind it. clipToBounds keeps the preview inside its box.
    Box(Modifier.fillMaxSize().background(Paper).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(CAMERA_TOP_BAR_HEIGHT))
            Box(
                Modifier.weight(1f).fillMaxWidth().clipToBounds()
                    .background(androidx.compose.ui.graphics.Color.Black),
            ) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                if (!ready || bindingFailed || captureFailed || saving || captured) {
                    EmptyHint(
                        stringResource(
                            when {
                                saving || captured -> R.string.photo_saving
                                bindingFailed -> R.string.photo_failed
                                captureFailed -> R.string.photo_retry
                                else -> R.string.photo_starting
                            },
                        ),
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().height(112.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
            Canvas(
                Modifier.width(64.dp).height(64.dp).then(
                    if (ready && !saving && !bindingFailed && !captured) {
                        tapModifier(::takePhoto, stringResource(R.string.photo_take))
                    } else {
                        Modifier
                    },
                ),
            ) {
                val color = if (ready && !saving && !bindingFailed && !captured) Ink else DimInk
                drawCircle(
                    color = color,
                    radius = size.minDimension * 0.36f,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = size.minDimension * 0.06f,
                        cap = StrokeCap.Round,
                    ),
                )
                drawCircle(color = color, radius = size.minDimension * 0.25f)
            }
            Text(
                stringResource(
                    when {
                        saving || captured -> R.string.photo_saving
                        bindingFailed -> R.string.photo_failed
                        captureFailed -> R.string.photo_retry
                        else -> R.string.photo_take
                    },
                ),
                color = if (ready && !bindingFailed && !captured) Ink else DimInk,
                fontSize = 13.sp,
            )
            }
        }
        CameraTopBar(
            saving = saving,
            onBack = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .height(CAMERA_TOP_BAR_HEIGHT)
                .background(Paper),
        )
    }
}

private val CAMERA_TOP_BAR_HEIGHT = 56.dp

/**
 * Camera navigation is deliberately laid out inside the full screen width.
 * The shared top bar offsets its arrow beyond the padded content column; on a
 * camera screen that made most of the apparent hit target fall outside its
 * parent. Keep this control visible during saving as calm progress feedback,
 * but disable navigation until the encrypted original is safely committed.
 */
@Composable
private fun CameraTopBar(
    saving: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp, start = 4.dp, end = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BackArrow(
            modifier = Modifier.align(Alignment.CenterStart),
            onBack = onBack,
            enabled = !saving,
        )
        Text(
            stringResource(R.string.photo_title).replaceFirstChar(Char::uppercase),
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 64.dp),
        )
    }
}

/** Decrypts and downsamples only the visible image; no plaintext disk cache is created. */
@Composable
fun EncryptedEntryImage(
    entry: NoteEntry,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    backgroundColor: Color = DimInk.copy(alpha = 0.16f),
) {
    val app = LocalContext.current.applicationContext as SomaApplication
    val attachment = entry.activeImage
    val bitmap by produceState<ImageBitmap?>(null, attachment?.fileId) {
        value = if (attachment == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val (_, jpeg) = EncryptedImageContainer.read(
                        app.encryptedImageFile(attachment.fileId),
                        attachment.fileId,
                        app.imageKeyProvider,
                    )
                    try {
                        decodeForDisplay(jpeg, attachment.rotationDegrees).asImageBitmap()
                    } finally {
                        jpeg.fill(0)
                    }
                }.getOrNull()
            }
        }
    }
    Box(modifier.clipToBounds().background(backgroundColor)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = stringResource(R.string.photo_description),
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

private fun decodeForDisplay(jpeg: ByteArray, rotationDegrees: Int): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > MAX_DISPLAY_EDGE || bounds.outHeight / sample > MAX_DISPLAY_EDGE) {
        sample *= 2
    }
    val decoded = requireNotNull(
        BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ),
    ) { "Could not decode image" }
    if (rotationDegrees == 0) return decoded
    return Bitmap.createBitmap(
        decoded,
        0,
        0,
        decoded.width,
        decoded.height,
        Matrix().apply { postRotate(rotationDegrees.toFloat()) },
        true,
    ).also { if (it !== decoded) decoded.recycle() }
}

private const val CAPTURE_WIDTH = 1280
private const val CAPTURE_HEIGHT = 960
private const val MAX_DISPLAY_EDGE = 1080
