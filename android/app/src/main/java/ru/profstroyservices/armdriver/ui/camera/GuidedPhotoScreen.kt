package ru.profstroyservices.armdriver.ui.camera

import android.util.Log
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.data.repository.decodeUpright
import java.io.File

private const val ReviewMaxDimension = 1600
private val ControlHeight = 64.dp

private sealed interface Stage {
    data object Aim : Stage
    data object Capturing : Stage
    data class Review(val image: ImageBitmap) : Stage
}

// Пошаговая съёмка этапа: подсказка «что снимать» поверх видоискателя,
// после снимка — превью, чтобы смазанный кадр переснять до отправки.
// onDone получает снимки этой съёмки; completed = false — водитель вышел
// раньше, остальное решает экран рейса (доснять или закрыть с причиной).
@Composable
fun GuidedPhotoScreen(
    steps: List<PhotoStep>,
    startStep: Int,
    newFile: () -> File,
    onDone: (photos: List<CapturedPhoto>, completed: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    TrackDeviceRotation { imageCapture.targetRotation = it }

    // Переживают пересоздание экрана: принятые снимки — код вида и путь попеременно.
    var stepIndex by rememberSaveable { mutableIntStateOf(startStep.coerceIn(steps.indices)) }
    var accepted by rememberSaveable { mutableStateOf(listOf<String>()) }
    var currentPath by rememberSaveable { mutableStateOf<String?>(null) }
    var stage by remember { mutableStateOf<Stage>(Stage.Aim) }
    var error by remember { mutableStateOf<String?>(null) }

    val step = steps[stepIndex]
    val acceptedPhotos = accepted.chunked(2).mapNotNull { (code, path) ->
        PhotoKind.fromCode(code)?.let { CapturedPhoto(it, path) }
    }
    val sheet = acceptedPhotos.count { it.kind == step.kind } + 1
    val isLastStep = stepIndex == steps.lastIndex

    fun accept(): List<CapturedPhoto> {
        val path = checkNotNull(currentPath)
        accepted = accepted + listOf(step.kind.code, path)
        currentPath = null
        stage = Stage.Aim
        return acceptedPhotos + CapturedPhoto(step.kind, path)
    }

    fun cancel() {
        currentPath?.let { File(it).delete() }
        onDone(acceptedPhotos, false)
    }

    fun capture() {
        val target = currentPath?.let(::File) ?: newFile().also { currentPath = it.absolutePath }
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
                    Log.e("GuidedPhotoScreen", "Capture failed", exception)
                    error = "Не удалось сделать снимок. Попробуйте ещё раз."
                    stage = Stage.Aim
                }
            }
        )
    }

    BackHandler { if (stage is Stage.Review) stage = Stage.Aim else cancel() }

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
                    Log.e("GuidedPhotoScreen", "Camera bind failed", e)
                    error = "Камера недоступна. Выйдите и попробуйте ещё раз."
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Шаг ${stepIndex + 1} из ${steps.size}",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
            Text(
                text = when {
                    stage is Stage.Review -> "Проверьте снимок"
                    step.allowMore && sheet > 1 -> "${step.prompt}: лист $sheet"
                    else -> step.prompt
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = if (stage is Stage.Review) "Если смазано или не видно — переснимите" else step.hint,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            error?.let {
                Text(text = it, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFFF8A80))
            }
            when (val current = stage) {
                is Stage.Review -> {
                    // Цвета явно, не из темы: основной цвет темы чёрный и на
                    // фоне камеры сливается.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { stage = Stage.Aim },
                            modifier = Modifier.weight(1f).height(ControlHeight),
                            border = BorderStroke(2.dp, Color.White),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                        ) { Text("Переснять", style = MaterialTheme.typography.labelLarge) }
                        if (step.allowMore) {
                            OutlinedButton(
                                onClick = { accept() },
                                modifier = Modifier.weight(1f).height(ControlHeight),
                                border = BorderStroke(2.dp, Color.White),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                            ) { Text("Ещё лист", style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                    Button(
                        onClick = {
                            val photos = accept()
                            if (isLastStep) onDone(photos, true) else stepIndex += 1
                        },
                        modifier = Modifier.fillMaxWidth().height(ControlHeight),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) { Text(if (isLastStep) "Готово" else "Далее", style = MaterialTheme.typography.labelLarge) }
                }

                else -> Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = ::cancel, modifier = Modifier.weight(1f)) {
                        Text("Отмена", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    ShutterButton(busy = current is Stage.Capturing, onClick = ::capture)
                    Spacer(modifier = Modifier.weight(1f))
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
