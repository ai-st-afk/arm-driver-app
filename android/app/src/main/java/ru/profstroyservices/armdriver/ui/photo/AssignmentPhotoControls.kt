package ru.profstroyservices.armdriver.ui.photo

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.ui.camera.CapturePhotos
import ru.profstroyservices.armdriver.ui.camera.PhotoCaptureRequest
import ru.profstroyservices.armdriver.ui.camera.rememberCameraAccess

@Composable
fun AssignmentPhotoControls(
    requiredKind: PhotoKind?,
    showFree: Boolean = true,
    viewModel: AssignmentPhotoViewModel = hiltViewModel()
) {
    val completed by viewModel.completed.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val notice by viewModel.notice.collectAsState()
    var pendingKind by rememberSaveable { mutableStateOf<String?>(null) }
    var chooseSource by rememberSaveable { mutableStateOf(false) }
    var galleryPending by rememberSaveable { mutableStateOf(false) }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(pendingKind, chooseSource, galleryPending, busy) {
        val release = if (pendingKind != null || chooseSource || galleryPending || busy) viewModel.holdUpdates() else null
        onDispose { release?.invoke() }
    }

    val camera = rememberLauncherForActivityResult(CapturePhotos()) { result ->
        val kind = pendingKind?.let(PhotoKind::fromCode)
        pendingKind = null
        if (kind != null) viewModel.submitCamera(kind, result)
        else result.photos.forEach { java.io.File(it.path).delete() }
    }
    val requestCamera = rememberCameraAccess(onDenied = {
        cameraDenied = true
        pendingKind = null
    })
    fun startCamera(kind: PhotoKind) {
        pendingKind = kind.code
        requestCamera {
            cameraDenied = false
            camera.launch(PhotoCaptureRequest(kind.code, 0))
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        galleryPending = false
        if (uri != null) viewModel.submitGallery(uri)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (requiredKind != null) {
            val name = if (requiredKind == PhotoKind.VEHICLE_KEYS) "после выдачи ключей" else "перед медиком"
            if (requiredKind in completed) {
                Text("Фото машины $name: четыре стороны сохранены", color = MaterialTheme.colorScheme.primary)
            } else {
                Text("Обязательно сфотографируйте машину с четырёх сторон $name.", color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = { startCamera(requiredKind) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(64.dp)
                ) { Text("Фото машины: 4 стороны") }
            }
        }
        if (showFree) {
            OutlinedButton(
                onClick = { chooseSource = true },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp)
            ) { Text("Отправить фото") }
        }
        if (cameraDenied) Text("Разрешите доступ к камере в настройках телефона.", color = MaterialTheme.colorScheme.error)
        notice?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
    }

    if (chooseSource) {
        AlertDialog(
            onDismissRequest = { chooseSource = false },
            title = { Text("Отправить фото") },
            text = { Text("Выберите, как добавить фотографию к разнарядке.") },
            confirmButton = {
                Button(onClick = { chooseSource = false; startCamera(PhotoKind.FREE) }) { Text("Камера") }
            },
            dismissButton = {
                TextButton(onClick = { chooseSource = false; galleryPending = true; gallery.launch("image/*") }) { Text("Галерея") }
            }
        )
    }
}
