package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(
    val pendingCount: Int = 0,
    val rejectionReason: String? = null
)

// Вся исходящая очередь одним объектом: события и фото. Водителю неважно,
// что именно не ушло — важно, что очередь не пуста. Раньше отправка жила
// только в RoadmapViewModel, поэтому события уровня смены («Ознакомился и
// принял», «Начать смену», «Закончить смену») могли остаться в телефоне
// навсегда — их экран отправку не дёргал (инвариант 6 из AGENTS.md).
@Singleton
class QueueRepository @Inject constructor(
    private val events: EventQueueRepository,
    private val documents: DocumentRepository
) {
    val state: Flow<QueueState> = combine(
        events.observeUnsentCount(),
        documents.observePendingCount(),
        events.observeLastRejected().map { it?.lastError }
    ) { unsentEvents, pendingPhotos, rejection ->
        QueueState(pendingCount = unsentEvents + pendingPhotos, rejectionReason = rejection)
    }

    // Падение одного потока не должно мешать другому: фото и события уходят
    // независимо (у 1С это вообще разные эндпоинты).
    suspend fun flush() {
        runCatching { events.sendPending() }
        runCatching { documents.uploadPending() }
    }
}
