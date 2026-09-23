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

    // Для таба «История» — полный локальный лог, включая уже отправленные
    // события (sent=1 не удаляется, см. markSent).
    fun observeAll(): Flow<List<PendingEventEntity>> = dao.observeAll()

    fun observeLastRejected(): Flow<PendingEventEntity?> = dao.observeLastRejected()

    // GUID генерируется на телефоне в момент события (инвариант из AGENTS.md),
    // не при последующей отправке. Повторный вызов для уже записанного
    // события (assignmentId+type+tripId) не создаёт второй GUID.
    // Возвращает id созданного события или null, если такое уже записано —
    // по нему потом можно отменить действие, пока оно не ушло.
    suspend fun enqueue(
        type: String,
        driverId: String,
        assignmentId: String,
        assignmentVersion: Int,
        tripId: String? = null,
        comment: String = ""
    ): String? {
        val duplicate = if (type == EventTypes.OZNAKOMLENIE) {
            dao.existsForVersion(assignmentId, type, assignmentVersion)
        } else {
            dao.exists(assignmentId, type, tripId)
        }
        if (duplicate) return null
        val id = UUID.randomUUID().toString()
        dao.insert(
            PendingEventEntity(
                id = id,
                type = type,
                driverId = driverId,
                assignmentId = assignmentId,
                tripId = tripId,
                time = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                comment = comment,
                assignmentVersion = assignmentVersion
            )
        )
        return id
    }

    // Кнопка «Отмена» — общий путь и для быстрого отката в снекбаре сразу
    // после нажатия, и для явной отмены шага рейса позже. Событие не
    // удаляется (см. PendingEventEntity.cancelled) — остаётся видно в
    // «Истории» с пометкой «отменено». Возвращает false, если событие уже
    // отправлено в 1С — тогда отменять локально нечего, это уже факт.
    suspend fun cancelPending(id: String): Boolean = dao.markCancelled(id) > 0

    // То же самое, но по типу шага и рейсу — когда конкретный id события
    // уже не под рукой (кнопка «Отмена» открыта позже, не сразу после тапа).
    suspend fun cancelStep(assignmentId: String, tripId: String, type: String): Boolean {
        val event = dao.findUnsent(assignmentId, tripId, type) ?: return false
        return cancelPending(event.id)
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
        for (result in response.events) {
            if (result.accepted) {
                dao.markSent(result.id)
            } else {
                // Причину отказа 1С присылает вместе с ответом — раньше мы её
                // выбрасывали, и водитель видел только счётчик «не отправлено».
                dao.markRejected(result.id, result.error ?: "1С не приняла событие")
            }
        }
    }
}
