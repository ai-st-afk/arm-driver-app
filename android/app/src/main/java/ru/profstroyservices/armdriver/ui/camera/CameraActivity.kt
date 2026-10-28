package ru.profstroyservices.armdriver.ui.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import dagger.hilt.android.AndroidEntryPoint
import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.DocumentRepository
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme
import javax.inject.Inject

private const val EXTRA_MODE = "mode"
private const val MODE_PHOTOS = "photos"
private const val MODE_QR = "qr"
private const val EXTRA_EVENT_TYPE = "event_type"
private const val EXTRA_START_STEP = "start_step"
private const val RESULT_KINDS = "kinds"
private const val RESULT_PATHS = "paths"
private const val RESULT_COMPLETED = "completed"
private const val RESULT_QR = "qr"

// Камера — отдельное полноэкранное окно, а не диалог: на Xiaomi окно
// диалога уходит под системные кнопки, а их высоту ему не сообщают —
// кнопка спуска оказывалась под «Домой» и не нажималась. Отступы окна
// Activity считаются так же, как у основного экрана. Портретная ориентация
// задана в манифесте: поворот не пересоздаёт экран посреди съёмки, а
// поворот самого снимка берётся с датчика (TrackDeviceRotation).
@AndroidEntryPoint
class CameraActivity : ComponentActivity() {

    @Inject lateinit var documents: DocumentRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Фон камеры тёмный — значки строки состояния и кнопки навигации светлые.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        if (intent.getStringExtra(EXTRA_MODE) == MODE_QR) {
            setContent {
                ArmDriverTheme {
                    QrScanScreen(
                        onScanned = { raw ->
                            setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_QR, raw))
                            finish()
                        },
                        onCancel = { finish() }
                    )
                }
            }
            return
        }

        val steps = photoStepsFor(intent.getStringExtra(EXTRA_EVENT_TYPE).orEmpty())
        if (steps == null) {
            finish()
            return
        }
        setContent {
            ArmDriverTheme {
                GuidedPhotoScreen(
                    steps = steps,
                    startStep = intent.getIntExtra(EXTRA_START_STEP, 0),
                    newFile = documents::createCaptureFile,
                    onDone = { photos, completed ->
                        setResult(
                            Activity.RESULT_OK,
                            Intent()
                                .putExtra(RESULT_KINDS, photos.map { it.kind.code }.toTypedArray())
                                .putExtra(RESULT_PATHS, photos.map { it.path }.toTypedArray())
                                .putExtra(RESULT_COMPLETED, completed)
                        )
                        finish()
                    }
                )
            }
        }
    }
}

data class PhotoCaptureRequest(val eventType: String, val startStep: Int)

data class PhotoCaptureResult(val photos: List<CapturedPhoto>, val completed: Boolean)

class CapturePhotos : ActivityResultContract<PhotoCaptureRequest, PhotoCaptureResult>() {
    override fun createIntent(context: Context, input: PhotoCaptureRequest): Intent =
        Intent(context, CameraActivity::class.java)
            .putExtra(EXTRA_MODE, MODE_PHOTOS)
            .putExtra(EXTRA_EVENT_TYPE, input.eventType)
            .putExtra(EXTRA_START_STEP, input.startStep)

    override fun parseResult(resultCode: Int, intent: Intent?): PhotoCaptureResult {
        val kinds = intent?.getStringArrayExtra(RESULT_KINDS).orEmpty()
        val paths = intent?.getStringArrayExtra(RESULT_PATHS).orEmpty()
        val photos = kinds.zip(paths).mapNotNull { (code, path) ->
            PhotoKind.fromCode(code)?.let { CapturedPhoto(it, path) }
        }
        val completed = resultCode == Activity.RESULT_OK && intent?.getBooleanExtra(RESULT_COMPLETED, false) == true
        return PhotoCaptureResult(photos, completed)
    }
}

class ScanDriverQr : ActivityResultContract<Unit, String?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(context, CameraActivity::class.java).putExtra(EXTRA_MODE, MODE_QR)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        intent?.getStringExtra(RESULT_QR)?.takeIf { resultCode == Activity.RESULT_OK }
}
