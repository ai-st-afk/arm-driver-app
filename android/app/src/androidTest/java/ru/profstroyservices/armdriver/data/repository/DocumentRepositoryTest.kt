package ru.profstroyservices.armdriver.data.repository

import android.content.Context
import android.graphics.Bitmap
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.data.db.AppDatabase
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.AssignmentsListResponse
import ru.profstroyservices.armdriver.data.network.DeviceRequest
import ru.profstroyservices.armdriver.data.network.DeviceResponse
import ru.profstroyservices.armdriver.data.network.DocumentUploadResponse
import ru.profstroyservices.armdriver.data.network.DriverDto
import ru.profstroyservices.armdriver.data.network.EventsRequest
import ru.profstroyservices.armdriver.data.network.EventsResponse
import ru.profstroyservices.armdriver.data.network.GatewayApi
import java.io.File
import java.io.IOException

private const val EVENT = "8f3a1c2e-4b7d-4a91-9c11-2f5e6d0a7b31"
private const val OTHER_EVENT = "9a4b2d3f-5c8e-4b02-8d22-3f6e7d1b8c42"

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val photosDir = File(context.filesDir, "photos")
    private lateinit var db: AppDatabase
    private val api = FakeUploadApi()
    private lateinit var repository: DocumentRepository

    @Before
    fun setUp() {
        photosDir.deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = DocumentRepository(context, api, db.pendingPhotoDao())
    }

    @After
    fun tearDown() {
        db.close()
        photosDir.deleteRecursively()
    }

    private fun capturedPhoto(): File = repository.createCaptureFile().also { file ->
        file.outputStream().use {
            Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
    }

    // Шаг откатили до отправки — его фото не должны уйти в 1С.
    @Test
    fun discardForEventRemovesOnlyThatEventsPhotos() = runBlocking {
        val mine = capturedPhoto()
        val other = capturedPhoto()
        repository.enqueue(mine, "d", "a", "t", eventId = EVENT, kind = PhotoKind.DOCUMENT)
        repository.enqueue(other, "d", "a", "t2", eventId = OTHER_EVENT, kind = PhotoKind.DOCUMENT)

        repository.discardForEvent(EVENT)

        assertFalse(mine.exists())
        assertTrue(other.exists())
        assertEquals(listOf(other.absolutePath), db.pendingPhotoDao().getAllFilePaths())
    }

    private fun File.ageDays(days: Int) = setLastModified(System.currentTimeMillis() - days * 24L * 60 * 60 * 1000)

    @Test
    fun deliveredPhotoFileIsDeleted() = runBlocking {
        val photo = capturedPhoto()
        repository.enqueue(photo, driverId = "d", assignmentId = "a", tripId = "t", eventId = EVENT, kind = PhotoKind.TRUCK_REAR)

        repository.uploadPending()

        assertEquals(1, api.uploads)
        assertEquals(EVENT, api.lastEventId)
        assertEquals("truck_rear", api.lastKind)
        assertFalse(photo.exists())
        assertTrue(db.pendingPhotoDao().getAllFilePaths().isEmpty())
    }

    // Не доставлено — файл обязан остаться, иначе отправлять будет нечего.
    @Test
    fun failedPhotoKeepsFileEvenWhenOld() = runBlocking {
        api.fail = true
        val photo = capturedPhoto()
        repository.enqueue(photo, driverId = "d", assignmentId = "a", tripId = "t", eventId = EVENT, kind = PhotoKind.TRUCK_SIDE)
        photo.ageDays(3)

        repository.uploadPending()

        assertTrue(photo.exists())
        assertEquals(listOf(photo.absolutePath), db.pendingPhotoDao().getAllFilePaths())
    }

    // Старые неучтённые файлы — то, что накопилось до исправления, и
    // брошенные съёмки. Свежий неучтённый — снимок, который водитель
    // ещё смотрит в превью: его трогать нельзя.
    @Test
    fun oldOrphanFilesAreDeletedFreshOnesKept() = runBlocking {
        val oldOrphan = capturedPhoto().apply { ageDays(2) }
        val freshOrphan = capturedPhoto()

        repository.uploadPending()

        assertFalse(oldOrphan.exists())
        assertTrue(freshOrphan.exists())
    }

    private class FakeUploadApi : GatewayApi {
        var fail = false
        var uploads = 0
        var lastEventId: String? = null
        var lastKind: String? = null

        private fun RequestBody.text() = Buffer().also { writeTo(it) }.readUtf8()

        override suspend fun uploadDocument(
            photoId: RequestBody,
            driverId: RequestBody,
            assignmentId: RequestBody,
            tripId: RequestBody,
            eventId: RequestBody?,
            kind: RequestBody?,
            photo: MultipartBody.Part
        ): DocumentUploadResponse {
            if (fail) throw IOException("offline")
            uploads++
            lastEventId = eventId?.text()
            lastKind = kind?.text()
            return DocumentUploadResponse(status = "ok")
        }

        override suspend fun getAssignments(driverId: String): AssignmentsListResponse = error("not used")
        override suspend fun getAssignment(id: String, driverId: String): AssignmentDto = error("not used")
        override suspend fun registerDevice(request: DeviceRequest): DeviceResponse = error("not used")
        override suspend fun sendEvents(request: EventsRequest): EventsResponse = error("not used")
        override suspend fun getDriver(id: String): DriverDto = error("not used")
    }
}
