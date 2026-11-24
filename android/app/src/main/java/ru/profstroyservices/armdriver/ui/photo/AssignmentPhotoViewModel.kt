package ru.profstroyservices.armdriver.ui.photo

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentPhotoCompletion
import ru.profstroyservices.armdriver.data.repository.DocumentRepository
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import ru.profstroyservices.armdriver.data.update.AppUpdater
import ru.profstroyservices.armdriver.ui.camera.PhotoCaptureResult
import java.io.File
import javax.inject.Inject

private val sides = listOf("Спереди", "Сзади", "Слева", "Справа")

@HiltViewModel
class AssignmentPhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documents: DocumentRepository,
    private val completedPhotos: AssignmentPhotoCompletion,
    private val settings: DriverSettingsRepository,
    private val queue: QueueRepository,
    private val updater: AppUpdater
) : ViewModel() {
    private val assignmentId: String = checkNotNull(savedStateHandle["assignmentId"])
    val completed: StateFlow<Set<PhotoKind>> = completedPhotos.observe(assignmentId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val busy = MutableStateFlow(false)
    val notice = MutableStateFlow<String?>(null)

    fun holdUpdates(): () -> Unit = updater.holdInstall()

    fun submitCamera(kind: PhotoKind, result: PhotoCaptureResult) {
        val expected = if (kind == PhotoKind.FREE) 1 else 4
        if (!result.completed || result.photos.size != expected || result.photos.any { it.kind != kind }) {
            result.photos.forEach { File(it.path).delete() }
            if (result.completed) notice.value = "Нужно снять все четыре стороны машины"
            return
        }
        save(kind, result.photos.mapIndexed { index, photo ->
            File(photo.path) to if (kind == PhotoKind.FREE) null else sides[index]
        })
    }

    fun submitGallery(uri: Uri) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            var file: File? = null
            var enqueued = false
            try {
                val driverId = settings.driverId.first() ?: error("Телефон не привязан к водителю")
                file = documents.copyFromGallery(uri)
                documents.enqueueAssignmentPhotos(listOf(file to null), driverId, assignmentId, PhotoKind.FREE)
                enqueued = true
                notice.value = "Фото сохранено и отправляется"
                queue.flush()
            } catch (error: Exception) {
                if (!enqueued) file?.delete()
                notice.value = "Не удалось добавить фото: ${error.message ?: "неизвестная ошибка"}"
            } finally {
                busy.value = false
            }
        }
    }

    private fun save(kind: PhotoKind, photos: List<Pair<File, String?>>) {
        if (busy.value) return
        busy.value = true
        notice.value = null
        viewModelScope.launch {
            var enqueued = false
            try {
                val driverId = settings.driverId.first() ?: error("Телефон не привязан к водителю")
                documents.enqueueAssignmentPhotos(photos, driverId, assignmentId, kind)
                enqueued = true
                if (kind != PhotoKind.FREE) completedPhotos.markDone(assignmentId, kind)
                notice.value = "Фото сохранены и отправляются"
                queue.flush()
            } catch (error: Exception) {
                if (!enqueued) photos.forEach { it.first.delete() }
                notice.value = "Не удалось добавить фото: ${error.message ?: "неизвестная ошибка"}"
            } finally {
                busy.value = false
            }
        }
    }
}
