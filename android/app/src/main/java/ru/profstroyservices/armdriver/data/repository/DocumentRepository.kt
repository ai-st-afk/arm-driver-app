package ru.profstroyservices.armdriver.data.repository

import android.content.Context
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import ru.profstroyservices.armdriver.data.db.PendingPhotoDao
import ru.profstroyservices.armdriver.data.db.PendingPhotoEntity
import ru.profstroyservices.armdriver.data.network.GatewayApi
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GatewayApi,
    private val dao: PendingPhotoDao
) {
    private val photosDir: File
        get() = File(context.filesDir, "photos").apply { mkdirs() }

    // Файл + content:// Uri для ACTION_IMAGE_CAPTURE — камера пишет фото
    // прямо сюда, без прохода через галерею (инвариант "только с камеры",
    // озвученный автором).
    fun createCaptureTarget(): Pair<File, android.net.Uri> {
        val file = File(photosDir, "${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    suspend fun enqueue(file: File, driverId: String, assignmentId: String, tripId: String) {
        dao.insert(
            PendingPhotoEntity(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                driverId = driverId,
                assignmentId = assignmentId,
                filePath = file.absolutePath
            )
        )
    }

    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    // Пробует отправить все ещё не загруженные фото. Каждое — независимо:
    // одно упавшее не должно блокировать остальные (в очереди могут быть
    // фото с разных ездок/попыток).
    suspend fun uploadPending() {
        for (photo in dao.getPending()) {
            val file = File(photo.filePath)
            if (!file.exists()) {
                // Файл потеряли (переустановка, чистка кэша ОС) — тащить
                // в очереди уже нечего, убираем запись, чтобы не зависала.
                dao.deleteById(photo.id)
                continue
            }
            runCatching {
                api.uploadDocument(
                    driverId = photo.driverId.toRequestBody("text/plain".toMediaType()),
                    assignmentId = photo.assignmentId.toRequestBody("text/plain".toMediaType()),
                    tripId = photo.tripId.toRequestBody("text/plain".toMediaType()),
                    photo = MultipartBody.Part.createFormData(
                        "photo",
                        file.name,
                        file.asRequestBody("image/jpeg".toMediaType())
                    )
                )
            }.onSuccess {
                dao.deleteById(photo.id)
            }
            // При ошибке запись остаётся — подхватится следующим «Повторить».
        }
    }
}
