package ru.profstroyservices.armdriver.ui.camera

import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.profstroyservices.armdriver.data.repository.decodeUpright
import java.io.File

private const val ReviewMaxDimension = 1600
private val ControlHeight = 64.dp

private sealed interface Stage {
    data object Aim : Stage
    data object Capturing : Stage
    data class Review(val image: ImageBitmap) : Stage
}

// Камера приложения с подсказкой, что именно снимать, прямо поверх
// видоискателя — на системную камеру такую подсказку не наложить. После
// снимка водитель сразу видит результат и может переснять, пока фото
// никуда не ушло: смазанная накладная обнаружилась бы только в 1С.
@Composable
fun GuidedPhotoDialog(
    prompt: String,
    hint: String?,
    target: File,
    onCaptured: () -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        LockOrientationWhileVisible()
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val imageCapture = remember {
            ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
        }
        TrackDeviceRotation { imageCapture.targetRotation = it }
        var stage by remember { mutableStateOf<Stage>(Stage.Aim) }
        var error by remember { mutableStateOf<String?>(null) }

        fun capture() {
            stage = Stage.Capturing
            error = null
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        scope.launch {
                            val preview = withContext(Dispatchers.IO) { decodeUpright(target, ReviewMaxDimension) }
                            if (preview != null) {
                                stage = Stage.Review(preview.asImageBitmap())
                            } else {
                                error = "Снимок не сохранился. Попробуйте ещё раз."
                                stage = Stage.Aim
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e("GuidedPhotoDialog", "Capture failed", exception)
                        error = "Не удалось сделать снимок. Попробуйте ещё раз."
                        stage = Stage.Aim
                    }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            when (val current = stage) {
                is Stage.Review -> Image(
                    bitmap = current.image,
                    contentDescription = "Снимок",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                else -> CameraPreview(
                    useCase = imageCapture,
                    onError = { e ->
                        Log.e("GuidedPhotoDialog", "Camera bind failed", e)
                        error = "Камера недоступна. Закройте и попробуйте ещё раз."
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = if (stage is Stage.Review) "Проверьте снимок" else prompt,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                val secondLine = if (stage is Stage.Review) "Если смазано или не видно — переснимите" else hint
                secondLine?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyLarge, color = Color.White)
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .navigationBarsPadding()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                error?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFF8A80))
                }
                when (val current = stage) {
                    is Stage.Review -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Цвета явно, не из темы: основной цвет темы чёрный,
                        // и на чёрном фоне камеры «Готово» сливалось с ним.
                        OutlinedButton(
                            onClick = { stage = Stage.Aim },
                            modifier = Modifier.weight(1f).height(ControlHeight),
                            border = BorderStroke(2.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Переснять", style = MaterialTheme.typography.labelLarge) }
                        Button(
                            onClick = onCaptured,
                            modifier = Modifier.weight(1f).height(ControlHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                        ) { Text("Готово", style = MaterialTheme.typography.labelLarge) }
                    }

                    else -> Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                            Text("Отмена", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                        ShutterButton(
                            busy = current is Stage.Capturing,
                            onClick = ::capture
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(84.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .background(if (busy) Color.Gray else Color.White, CircleShape)
            .semantics { contentDescription = "Сделать снимок" }
            .clickable(enabled = !busy, onClick = onClick)
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(32.dp))
    }
}
