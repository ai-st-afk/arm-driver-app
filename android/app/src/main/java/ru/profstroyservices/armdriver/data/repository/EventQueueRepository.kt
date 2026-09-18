package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.flow.Flow
import ru.profstroyservices.armdriver.data.db.PendingEventDao
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EventQueueRepository @Inject constructor(
    private val dao: PendingEventDao
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
}
