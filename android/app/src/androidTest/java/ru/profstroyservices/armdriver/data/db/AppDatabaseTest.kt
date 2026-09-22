package ru.profstroyservices.armdriver.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.VehicleDto

@RunWith(AndroidJUnit4::class)
class AppDatabaseTest {

    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun insertAndReadPendingEvent() = runBlocking {
        val event = PendingEventEntity(
            id = "e7c8ff40-fbda-4eae-ade0-30dceb4624b1",
            type = "Ознакомление",
            driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee",
            assignmentId = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            tripId = null,
            time = "2026-09-13T19:00:00+03:00",
            comment = ""
        )

        db.pendingEventDao().insert(event)
        val unsent = db.pendingEventDao().getUnsent()

        assertEquals(1, unsent.size)
        assertEquals(event, unsent.first())

        db.pendingEventDao().deleteById(event.id)
        assertEquals(0, db.pendingEventDao().getUnsent().size)
    }

    @Test
    fun markSentKeepsRowButHidesFromUnsent() = runBlocking {
        val event = PendingEventEntity(
            id = "e7c8ff40-fbda-4eae-ade0-30dceb4624b1",
            type = "Ознакомление",
            driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee",
            assignmentId = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            tripId = null,
            time = "2026-09-13T19:00:00+03:00",
            comment = ""
        )
        val dao = db.pendingEventDao()
        dao.insert(event)

        dao.markSent(event.id)

        // Ушло из "неотправленных" (бейдж/повтор), но не исчезло совсем —
        // прогресс на экране (acknowledged/doneTypes) читает именно
        // observeForAssignment, а не getUnsent.
        assertEquals(0, dao.getUnsent().size)
        assertEquals(1, dao.observeForAssignment(event.assignmentId).first().size)
        assertEquals(true, dao.exists(event.assignmentId, event.type, event.tripId))
    }

    @Test
    fun rejectedEventStaysInQueueWithReason() = runBlocking {
        val event = PendingEventEntity(
            id = "c1d2e3f4-5a6b-7c8d-9e0f-1a2b3c4d5e6f",
            type = "Разгрузился",
            driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee",
            assignmentId = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            tripId = "cc32f791-f71e-4b93-96c0-88066a0cddef",
            time = "2026-09-21T09:12:00+03:00",
            comment = ""
        )
        val dao = db.pendingEventDao()
        dao.insert(event)

        dao.markRejected(event.id, "Разгрузка без отметки прибытия на разгрузку")

        // Инвариант 2: отбитое событие из очереди не исчезает, но теперь
        // рядом с ним лежит причина — её показываем водителю.
        assertEquals(1, dao.getUnsent().size)
        assertEquals(
            "Разгрузка без отметки прибытия на разгрузку",
            dao.observeLastRejected().first()?.lastError
        )

        dao.markSent(event.id)
        assertEquals(0, dao.getUnsent().size)
        assertNull(dao.observeLastRejected().first())
    }

    @Test
    fun undoRemovesOnlyUnsentEvent() = runBlocking {
        val dao = db.pendingEventDao()
        val event = PendingEventEntity(
            id = "7f1c2d3e-4a5b-6c7d-8e9f-0a1b2c3d4e5f",
            type = "ПрибылНаПогрузку",
            driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee",
            assignmentId = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            tripId = "cc32f791-f71e-4b93-96c0-88066a0cddef",
            time = "2026-09-21T06:20:00+03:00",
            comment = ""
        )
        dao.insert(event)

        dao.deleteIfUnsent(event.id)
        assertEquals(0, dao.observeAll().first().size)

        // Уже отправленное событие отменить нельзя: для 1С это свершившийся
        // факт, локальное удаление только рассинхронизировало бы прогресс.
        dao.insert(event)
        dao.markSent(event.id)
        dao.deleteIfUnsent(event.id)
        assertEquals(1, dao.observeAll().first().size)
    }

    @Test
    fun cacheAndReadAssignment() = runBlocking {
        val driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee"
        val assignment = AssignmentDto(
            id = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            version = 1,
            number = "00000000004",
            departureDay = "2026-09-14",
            status = "Активна",
            driver = PersonDto(id = driverId, name = "Комиссаров Михаил Владимирович"),
            vehicle = VehicleDto(id = "63a3259d-cc83-11ed-97ef-d85ed35a8f26", plate = "У005РФ43")
        )

        db.cachedAssignmentDao().upsert(assignment.toEntity(driverId, updatedAt = 1L))
        val cached = db.cachedAssignmentDao().getAllForDriver(driverId)

        assertEquals(1, cached.size)
        assertEquals(assignment, cached.first().toAssignmentDto())
        assertEquals(assignment, db.cachedAssignmentDao().getById(assignment.id)?.toAssignmentDto())
        assertEquals(0, db.cachedAssignmentDao().getAllForDriver("unknown-driver").size)
    }

    @Test
    fun cacheMultipleAssignmentsForSameDriver() = runBlocking {
        val driverId = "1ed61b6b-b61e-4a5f-bcfe-a2a4be252bee"
        val vehicle = VehicleDto(id = "63a3259d-cc83-11ed-97ef-d85ed35a8f26", plate = "У005РФ43")
        val driver = PersonDto(id = driverId, name = "Комиссаров Михаил Владимирович")
        val first = AssignmentDto(
            id = "e28a5167-b292-11f1-9835-d85ed35a8f26",
            version = 1,
            departureDay = "2026-09-14",
            status = "Активна",
            driver = driver,
            vehicle = vehicle
        )
        val second = AssignmentDto(
            id = "a1b2c3d4-1111-2222-3333-444455556666",
            version = 1,
            departureDay = "2026-09-14",
            status = "Активна",
            driver = driver,
            vehicle = vehicle
        )

        db.cachedAssignmentDao().upsert(first.toEntity(driverId, updatedAt = 1L))
        db.cachedAssignmentDao().upsert(second.toEntity(driverId, updatedAt = 2L))

        // Раньше вторая упаковка затёрла бы первую (первичный ключ был
        // driverId) — теперь ключ по id разнарядки, обе остаются.
        val cached = db.cachedAssignmentDao().getAllForDriver(driverId)
        assertEquals(2, cached.size)
        assertEquals(setOf(first.id, second.id), cached.map { it.id }.toSet())
    }
}
