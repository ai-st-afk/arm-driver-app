package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest
import ru.profstroyservices.armdriver.data.network.userMessage
import javax.inject.Inject
import javax.inject.Singleton

// Окно отмены шага (RoadmapViewModel.scheduleFlush) — 5 секунд, события лежат
// в очереди непосланными всё это время специально, не потому что что-то не
// работает. Чуть больше — запас на обычный сетевой круг после самого flush().
private const val QUIET_GRACE_MILLIS = 6_000L

data class QueueState(
    val pendingCount: Int = 0,
    // Причина отказа 1С по конкретному событию (поштучный ответ) —
    // отличается от networkError: тут запрос дошёл, но 1С не приняла.
    val rejectionReason: String? = null,
    // То же самое, но по фото — отдельный поток от событий (у 1С это
    // вообще другой эндпоинт), поэтому и причина отдельная. Частый случай —
    // 1С временно не принимает фото, а события при этом уходят нормально.
    val photoRejectionReason: String? = null,
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
        documents.observeLastRejected().map { it?.lastError },
        _networkError
    ) { unsentEvents, pendingPhotos, eventRejection, photoRejection, networkError ->
        QueueState(
            pendingCount = unsentEvents + pendingPhotos,
            rejectionReason = eventRejection,
            photoRejectionReason = photoRejection,
            networkError = networkError
        )
    }.withQuietGrace(QUIET_GRACE_MILLIS)

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

// Без этой задержки баннер «Не отправлено» загорался на каждое нажатие
// кнопки — очередь действительно непуста первые секунды, но это окно
// отмены шага (5 с, RoadmapViewModel.scheduleFlush) и обычная сетевая
// задержка, не застрявшая очередь (инвариант 6 из AGENTS.md — про вторую,
// а не про первую). Подтверждённый отказ
// (rejectionReason/photoRejectionReason/networkError) показываем сразу,
// без задержки — это уже не «подождём», а факт.
@OptIn(ExperimentalCoroutinesApi::class)
fun Flow<QueueState>.withQuietGrace(graceMillis: Long): Flow<QueueState> = transformLatest { raw ->
    val confirmedFailure = raw.rejectionReason != null || raw.photoRejectionReason != null || raw.networkError != null
    if (raw.pendingCount == 0 || confirmedFailure) {
        emit(raw)
    } else {
        delay(graceMillis)
        emit(raw)
    }
}
