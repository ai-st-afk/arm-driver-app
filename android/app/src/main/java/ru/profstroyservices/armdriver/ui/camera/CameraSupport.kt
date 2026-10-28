package ru.profstroyservices.armdriver.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner

// Превью задней камеры + переданный use case (снимок или разбор кадров),
// привязанные к жизненному циклу экрана: свернули приложение — камера
// освобождается сама.
@Composable
internal fun CameraPreview(useCase: UseCase, onError: (Throwable) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnError by rememberUpdatedState(onError)
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    DisposableEffect(lifecycleOwner, useCase) {
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, useCase)
                provider = cameraProvider
            } catch (e: Exception) {
                currentOnError(e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            provider?.unbind(preview, useCase)
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

// Экран камеры зафиксирован в портрете (манифест), поэтому ориентацию
// снимка нельзя брать с дисплея —
// иначе фото, снятое в горизонтальном положении, ляжет на бок.
@Composable
internal fun TrackDeviceRotation(onRotation: (Int) -> Unit) {
    val context = LocalContext.current
    val currentOnRotation by rememberUpdatedState(onRotation)
    DisposableEffect(context) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                currentOnRotation(
                    when (orientation) {
                        in 45 until 135 -> Surface.ROTATION_270
                        in 135 until 225 -> Surface.ROTATION_180
                        in 225 until 315 -> Surface.ROTATION_90
                        else -> Surface.ROTATION_0
                    }
                )
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }
}

// Разрешение обычно уже выдано при первом запуске (MainActivity) — это
// страховка на случай, если его отозвали в настройках телефона.
@Composable
fun rememberCameraAccess(onDenied: () -> Unit): (onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    val currentOnDenied by rememberUpdatedState(onDenied)
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val onGranted = pending
        pending = null
        if (granted) onGranted?.invoke() else currentOnDenied()
    }
    return remember(launcher) {
        { onGranted ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                onGranted()
            } else {
                pending = onGranted
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
    }
}
