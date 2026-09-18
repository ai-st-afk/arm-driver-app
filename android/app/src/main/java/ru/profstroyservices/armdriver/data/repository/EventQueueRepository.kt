package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.flow.Flow
import ru.profstroyservices.armdriver.data.db.PendingEventDao
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.db.toEventRequest
import ru.profstroyservices.armdriver.data.network.EventsRequest
import ru.profstroyservices.armdriver.data.network.GatewayApi
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventQueueRepository @Inject constructor(
    private val dao: PendingEventDao,
    private val api: GatewayApi
) {
    fun observeForAssignment(assignmentId: String): Flow<List<PendingEventEntity>> =
        dao.observeForAssignment(assignmentId)

    fun observeUnsentCount(): Flow<Int> = dao.observeUnsentCount()

    // GUID генерируется на телефоне в момент события (инвариант из AGENTS.md),
    // не при последующей отправке. Повторный вызов для уже записанного
    // события (assignmentId+type+tripId) не создаёт второй GUID.
    suspend fun enqueue(
        type: String,
        driverId: String,
        assignmentId: String,
        tripId: String? = null,
        comment: String = ""
    ) {
        if (dao.exists(assignmentId, type, tripId)) return
        dao.insert(
            PendingEventEntity(
                id = UUID.randomUUID().toString(),
                type = type,
                driverId = driverId,
                assignmentId = assignmentId,
                tripId = tripId,
                time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                comment = comment
            )
        )
    }

    // Инвариант 2 из AGENTS.md: помечаем отправленными только accepted:true,
    // поштучно. Если весь запрос упал (сети нет, gateway недоступен, ONE_C
    // не настроен — 503) — исключение просто уходит наверх, очередь
    // остаётся как есть. Строку не удаляем даже при accepted:true (см.
    // markSent) — она всё ещё нужна для отображения прогресса на экране.
    suspend fun sendPending() {
        val unsent = dao.getUnsent()
        if (unsent.isEmpty()) return

        val response = api.sendEvents(EventsRequest(events = unsent.map { it.toEventRequest() }))
        response.events.filter { it.accepted }.forEach { dao.markSent(it.id) }
    }
}
