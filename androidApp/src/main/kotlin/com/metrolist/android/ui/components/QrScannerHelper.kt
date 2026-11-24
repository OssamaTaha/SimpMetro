package com.metrolist.android.ui.components

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.library_sync_camera_hint
import com.metrolist.shared.generated.resources.library_sync_from_gallery
import com.metrolist.shared.generated.resources.library_sync_scan_qr
import org.jetbrains.compose.resources.stringResource
import java.util.concurrent.atomic.AtomicBoolean

object QrDecoder {
    private val decodeHints =
        mapOf(
            DecodeHintType.TRY_HARDER to true,
        )

    fun decodeBitmap(bitmap: Bitmap): String? =
        runCatching {
            val argbBitmap =
                if (bitmap.config == Bitmap.Config.ARGB_8888) {
                    bitmap
                } else {
                    bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
            val width = argbBitmap.width
            val height = argbBitmap.height
            val pixels = IntArray(width * height)
            argbBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            if (argbBitmap !== bitmap) argbBitmap.recycle()
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap, decodeHints).text
        }.getOrNull()

    fun decodeUri(
        context: Context,
        uri: Uri,
    ): String? =
        runCatching {
            val bounds =
                BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            var sampleSize = 1
            val maxDim = 1024
            while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
                sampleSize *= 2
            }
            val options =
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bitmap = BitmapFactory.decodeStream(stream, null, options) ?: return null
                val result = decodeBitmap(bitmap)
                bitmap.recycle()
                result
            }
        }.getOrNull()

    fun getClipboardEndpoint(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        val clip = clipboard.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0)?.text?.toString()?.trim().orEmpty()
        return if (text.startsWith("http://") || text.startsWith("https://") || (':' in text && '.' in text)) {
            text
        } else {
            null
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun QrCameraScannerDialog(
    onQrDecoded: (String) -> Unit,
    onPickFromGallery: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val decodedOnce = remember { AtomicBoolean(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    val cameraThread = remember { HandlerThread("QrCameraThread").apply { start() } }
    val cameraHandler = remember { Handler(cameraThread.looper) }

    var activeCamera by remember { mutableStateOf<CameraDevice?>(null) }
    var activeSession by remember { mutableStateOf<CameraCaptureSession?>(null) }
    var activeReader by remember { mutableStateOf<ImageReader?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { activeSession?.close() }
            runCatching { activeCamera?.close() }
            runCatching { activeReader?.close() }
            cameraThread.quitSafely()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(Res.string.library_sync_scan_qr),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = cameraError ?: stringResource(Res.string.library_sync_camera_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (cameraError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TextureView(ctx).apply {
                                surfaceTextureListener =
                                    object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(
                                            surfaceTexture: SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) {
                                            runCatching {
                                                val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                                                val cameraId =
                                                    manager.cameraIdList.firstOrNull { id ->
                                                        manager
                                                            .getCameraCharacteristics(id)
                                                            .get(CameraCharacteristics.LENS_FACING) ==
                                                            CameraCharacteristics.LENS_FACING_BACK
                                                    } ?: manager.cameraIdList.firstOrNull()
                                                if (cameraId == null) {
                                                    cameraError = "No camera available"
                                                    return
                                                }
                                                surfaceTexture.setDefaultBufferSize(1280, 720)
                                                val previewSurface = Surface(surfaceTexture)
                                                val reader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 2)
                                                activeReader = reader
                                                val zxingReader = MultiFormatReader()
                                                val hints = mapOf(DecodeHintType.TRY_HARDER to true)

                                                reader.setOnImageAvailableListener({ r ->
                                                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                                                    try {
                                                        if (decodedOnce.get()) return@setOnImageAvailableListener
                                                        val yPlane = image.planes[0]
                                                        val buffer = yPlane.buffer
                                                        val data = ByteArray(buffer.remaining())
                                                        buffer.get(data)
                                                        val source =
                                                            PlanarYUVLuminanceSource(
                                                                data,
                                                                yPlane.rowStride,
                                                                image.height,
                                                                0,
                                                                0,
                                                                image.width,
                                                                image.height,
                                                                false,
                                                            )
                                                        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
                                                        val result = runCatching { zxingReader.decode(binaryBitmap, hints) }.getOrNull()
                                                        if (result != null && decodedOnce.compareAndSet(false, true)) {
                                                            val text = result.text
                                                            post { onQrDecoded(text) }
                                                        }
                                                    } finally {
                                                        image.close()
                                                    }
                                                }, cameraHandler)

                                                manager.openCamera(
                                                    cameraId,
                                                    object : CameraDevice.StateCallback() {
                                                        override fun onOpened(camera: CameraDevice) {
                                                            activeCamera = camera
                                                            val requestBuilder =
                                                                camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                                                    addTarget(previewSurface)
                                                                    addTarget(reader.surface)
                                                                    set(
                                                                        CaptureRequest.CONTROL_AF_MODE,
                                                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                                                    )
                                                                }
                                                            camera.createCaptureSession(
                                                                listOf(previewSurface, reader.surface),
                                                                object : CameraCaptureSession.StateCallback() {
                                                                    override fun onConfigured(session: CameraCaptureSession) {
                                                                        activeSession = session
                                                                        runCatching {
                                                                            session.setRepeatingRequest(
                                                                                requestBuilder.build(),
                                                                                null,
                                                                                cameraHandler,
                                                                            )
                                                                        }
                                                                    }

                                                                    override fun onConfigureFailed(session: CameraCaptureSession) {
                                                                        cameraError = "Camera preview failed"
                                                                    }
                                                                },
                                                                cameraHandler,
                                                            )
                                                        }

                                                        override fun onDisconnected(camera: CameraDevice) {
                                                            camera.close()
                                                        }

                                                        override fun onError(
                                                            camera: CameraDevice,
                                                            error: Int,
                                                        ) {
                                                            camera.close()
                                                            cameraError = "Camera error ($error)"
                                                        }
                                                    },
                                                    cameraHandler,
                                                )
                                            }.onFailure {
                                                cameraError = it.message ?: "Camera unavailable"
                                            }
                                        }

                                        override fun onSurfaceTextureSizeChanged(
                                            surface: SurfaceTexture,
                                            width: Int,
                                            height: Int,
                                        ) {}

                                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean = true

                                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
                                    }
                            }
                        },
                    )
                    Box(
                        modifier =
                            Modifier
                                .size(190.dp)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(16.dp),
                                ),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            onDismiss()
                            onPickFromGallery()
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(Res.string.library_sync_from_gallery), maxLines = 1)
                    }
                    TextButton(
                        onClick = onDismiss,
                    ) {
                        Text(stringResource(Res.string.cancel))
                    }
                }
            }
        }
    }
}
