package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import ru.profstroyservices.armdriver.data.network.userMessage
import javax.inject.Inject
import javax.inject.Singleton

data class QueueState(
    val pendingCount: Int = 0,
    // Причина отказа 1С по конкретному событию (поштучный ответ) —
    // отличается от networkError: тут запрос дошёл, но 1С не приняла.
    val rejectionReason: String? = null,
    // Запрос вообще не дошёл до шлюза (нет сети, шлюз недоступен и т.п.) —
    // такое не попадает в lastError ни одного события, раньше водитель
    // видел только счётчик без единого объяснения.
    val networkError: String? = null
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
    // Запрос, который не долетел до шлюза, не попадает ни в чьё lastError —
    // это не ответ 1С, а провал самого похода в сеть. Держим последнюю
    // причину отдельно от БД-состояния, очищаем на следующий успешный flush.
    private val _networkError = MutableStateFlow<String?>(null)

    val state: Flow<QueueState> = combine(
        events.observeUnsentCount(),
        documents.observePendingCount(),
        events.observeLastRejected().map { it?.lastError },
        _networkError
    ) { unsentEvents, pendingPhotos, rejection, networkError ->
        QueueState(
            pendingCount = unsentEvents + pendingPhotos,
            rejectionReason = rejection,
            networkError = networkError
        )
    }

    // Падение одного потока не должно мешать другому: фото и события уходят
    // независимо (у 1С это вообще разные эндпоинты).
    suspend fun flush() {
        val eventsResult = runCatching { events.sendPending() }
        val documentsResult = runCatching { documents.uploadPending() }
        // Оба успели — сеть работает, старая ошибка больше не актуальна.
        // Упал хотя бы один — показываем его причину, не различая, какой
        // именно поток: водителю важно «есть связь или нет», а не детали.
        val failure = eventsResult.exceptionOrNull() ?: documentsResult.exceptionOrNull()
        _networkError.value = failure?.userMessage()
    }
}
