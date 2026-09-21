package ru.profstroyservices.armdriver.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import ru.profstroyservices.armdriver.data.db.PendingPhotoDao
import ru.profstroyservices.armdriver.data.db.PendingPhotoEntity
import ru.profstroyservices.armdriver.data.network.GatewayApi
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// 1С просила сжимать перед отправкой: "для читаемой подписи на накладной
// достаточно 1-2 МБ", жёсткий лимит на их стороне — 5 МБ.
private const val MAX_PHOTO_BYTES = 1_500_000L
private const val MAX_PHOTO_DIMENSION = 2000

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
        compressInPlace(file)
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
                    photoId = photo.id.toRequestBody("text/plain".toMediaType()),
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

    // Даунсемплинг по разрешению + подбор качества JPEG, пока файл не
    // влезет в лимит. Камера отдаёт полноразмерный снимок (может быть
    // 4000×3000 и больше 5 МБ) — для читаемой подписи на накладной
    // это избыточно.
    private suspend fun compressInPlace(file: File) = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > MAX_PHOTO_DIMENSION ||
            bounds.outHeight / sampleSize > MAX_PHOTO_DIMENSION
        ) {
            sampleSize *= 2
        }

        val bitmap = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sampleSize }
        ) ?: return@withContext

        var quality = 90
        var bytes: ByteArray
        do {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            bytes = stream.toByteArray()
            quality -= 10
        } while (bytes.size > MAX_PHOTO_BYTES && quality >= 40)
        bitmap.recycle()

        file.writeBytes(bytes)
    }
}
