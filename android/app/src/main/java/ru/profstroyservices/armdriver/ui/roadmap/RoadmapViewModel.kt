package ru.profstroyservices.armdriver.ui.roadmap

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.repository.DocumentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val STATUS_CANCELLED = "Отменена"

// Сколько водитель может передумать после нажатия. Дольше держать нельзя:
// событие должно уехать в 1С как можно раньше, от него зависит выпуск машины.
private const val UNDO_WINDOW_MILLIS = 5_000L

private val eventTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun formatEventTime(): String = OffsetDateTime.now().format(eventTimeFormatter)

private fun actionFeedbackText(type: String): String = when (type) {
    EventTypes.PRIBYL_NA_POGRUZKU -> "Прибыл на погрузку"
    EventTypes.ZAGRUZILSYA_V_PUT -> "Загрузился, в пути"
    EventTypes.PRIBYL_NA_RAZGRUZKU -> "Прибыл на разгрузку"
    EventTypes.RAZGRUZILSYA -> "Разгрузился"
    else -> "Записано"
}

data class TripUiState(
    val trip: TripDto,
    val doneTypes: Set<String>
) {
    val isCancelled: Boolean get() = trip.status == STATUS_CANCELLED
    val isResolved: Boolean get() = isCancelled || doneTypes.contains(EventTypes.RAZGRUZILSYA) || doneTypes.contains(EventTypes.SRYV)
    val nextAction: String? get() = EventTypes.TRIP_CYCLE.firstOrNull { it !in doneTypes }
}

sealed interface RoadmapUiState {
    data object Loading : RoadmapUiState
    data class Error(val message: String) : RoadmapUiState
    data class Content(
        val assignmentId: String,
        val trips: List<TripUiState>,
        // Первый незакрытый рейс по порядку — её карточка раскрыта и
        // подсвечена. Остальные видны и доступны (решение автора: водитель
        // должен видеть все рейсы), просто свёрнуты.
        val activeTripId: String?,
        // Раньше действия по рейсам можно было выполнять независимо от
        // того, начата смена или нет — баг, найденный автором. Список ездок
        // виден всегда (см. activeTripId выше), а вот кнопки цикла, срыв и
        // отмена шага требуют начатой смены — без неё эти события для 1С
        // бессмысленны (флоу «Ознакомление → НачалоСмены → ездки»).
        val shiftStarted: Boolean
    ) : RoadmapUiState
}

// Подтверждение записанного действия: показываем, что именно зафиксировано и
// во сколько, и даём несколько секунд на отмену, пока событие не ушло в 1С.
data class ActionFeedback(
    val text: String,
    val undoEventId: String?
)

@HiltViewModel
class RoadmapViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val documents: DocumentRepository,
    private val queue: QueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val assignmentId: String = checkNotNull(savedStateHandle["assignmentId"])

    private val _uiState = MutableStateFlow<RoadmapUiState>(RoadmapUiState.Loading)
    val uiState: StateFlow<RoadmapUiState> = _uiState.asStateFlow()

    private val _feedback = MutableSharedFlow<ActionFeedback>(extraBufferCapacity = 1)
    val feedback: SharedFlow<ActionFeedback> = _feedback.asSharedFlow()

    private var driverId: String? = null
    private var flushJob: Job? = null

    init {
        viewModelScope.launch {
            val id = settings.driverId.first()
            if (id == null) {
                _uiState.value = RoadmapUiState.Error("Телефон не настроен. Обратитесь к диспетчеру.")
                return@launch
            }
            driverId = id

            val assignment = assignmentRepository.getCachedById(assignmentId)
            if (assignment == null) {
                _uiState.value = RoadmapUiState.Error("Разнарядка ещё не загружена. Откройте вкладку «Разнарядка».")
                return@launch
            }
            refresh(assignment)
            retryQueue()
        }
    }

    // Дёргается при каждом возврате на экран. Раньше roadmap читал только
    // локальный кэш и никогда не ходил в сеть заново — если 1С присылала
    // отмену рейса (та же разнарядка новой версией, подтверждено 1С-командой),
    // водитель не видел изменений, пока не перезапускал приложение, даже если
    // этот самый рейс был у него открыта на экране.
    fun refreshFromNetwork() {
        if (driverId == null) return
        viewModelScope.launch {
            val assignment = runCatching { assignmentRepository.getById(assignmentId) }.getOrNull() ?: return@launch
            refresh(assignment)
        }
    }

    private suspend fun refresh(assignment: AssignmentDto) {
        val events = eventQueue.observeForAssignment(assignment.id).first()
        val trips = assignment.trips
            .sortedBy { it.order }
            .map { trip ->
                // Отменённый шаг (cancelled) не в счёт — для UI это как будто
                // его и не было, водитель снова видит кнопку этого шага.
                val doneTypes = events
                    .filter { it.tripId == trip.id && !it.cancelled }
                    .map { it.type }
                    .toSet()
                TripUiState(trip, doneTypes)
            }
        _uiState.value = RoadmapUiState.Content(
            assignmentId = assignment.id,
            trips = trips,
            activeTripId = trips.firstOrNull { !it.isResolved }?.trip?.id,
            shiftStarted = events.any { it.type == EventTypes.NACHALO_SMENY && !it.cancelled }
        )
    }

    // Автор нашёл: кнопки цикла ездки срабатывали независимо от того, начата
    // смена или нет — проверки не было вообще. Здесь, а не только в UI
    // (RoadmapScreen прячет кнопки при !shiftStarted): UI-гейт можно обойти
    // случайным кадром рекомпозиции, а enqueue пишет реальный GUID-факт.
    private fun requireShiftStarted(): RoadmapUiState.Content? {
        val state = _uiState.value as? RoadmapUiState.Content ?: return null
        if (!state.shiftStarted) {
            emitFeedback("Сначала начните смену на вкладке «Разнарядка»", null)
            return null
        }
        return state
    }

    fun onTripAction(trip: TripDto, type: String) {
        val id = driverId ?: return
        val assignmentId = requireShiftStarted()?.assignmentId ?: return
        viewModelScope.launch {
            val eventId = eventQueue.enqueue(
                type = type,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id
            )
            reloadFromCache()
            emitFeedback(actionFeedbackText(type), eventId)
            scheduleFlush()
        }
    }

    fun onSryv(trip: TripDto, comment: String) {
        val id = driverId ?: return
        val assignmentId = requireShiftStarted()?.assignmentId ?: return
        viewModelScope.launch {
            val eventId = eventQueue.enqueue(
                type = EventTypes.SRYV,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id,
                comment = comment
            )
            reloadFromCache()
            emitFeedback("Рейс отмечен как сорванный", eventId)
            scheduleFlush()
        }
    }

    // Быстрая отмена сразу после нажатия (кнопка «Отменить» в снекбаре).
    // Работает только до фактической отправки. Само событие пишется в
    // очередь сразу — GUID и время фиксируются в момент нажатия (инвариант 1),
    // откладывается только отправка.
    fun onUndo(eventId: String) {
        viewModelScope.launch {
            // Сначала гасим отложенную отправку, чтобы событие не успело
            // уехать между нажатием «Отменить» и его отменой.
            flushJob?.cancel()
            eventQueue.cancelPending(eventId)
            reloadFromCache()
            // Остальная очередь не виновата — её всё равно надо дослать.
            scheduleFlush()
        }
    }

    // Кнопка «Отмена» в карточке рейса — доступна не только в первые
    // секунды после нажатия, а пока действительно не поздно (шаг ещё не
    // ушёл в 1С). Нужна на случай, когда за пару быстрых тапов подряд
    // водитель случайно проскочил на шаг вперёд: отменяет последний
    // выполненный шаг цикла и возвращает рейс на шаг назад.
    fun onStepBack(trip: TripDto) {
        val state = _uiState.value as? RoadmapUiState.Content ?: return
        val tripState = state.trips.find { it.trip.id == trip.id } ?: return
        val lastType = EventTypes.TRIP_CYCLE.lastOrNull { it in tripState.doneTypes } ?: return
        val assignmentId = state.assignmentId
        viewModelScope.launch {
            flushJob?.cancel()
            val cancelled = eventQueue.cancelStep(assignmentId, trip.id, lastType)
            reloadFromCache()
            if (cancelled) {
                emitFeedback("${actionFeedbackText(lastType)} — отменено", null)
            } else {
                emitFeedback("Шаг уже отправлен в 1С, отменить нельзя", null)
            }
            scheduleFlush()
        }
    }

    // tryEmit, а не emit: показ снекбара приостанавливает сборщик, и
    // приостановленная отправка фидбека задержала бы отправку самого
    // события в 1С. Потерять подсказку не страшно, задержать событие — да.
    private fun emitFeedback(text: String, eventId: String?) {
        _feedback.tryEmit(ActionFeedback(text = "$text — ${formatEventTime()}", undoEventId = eventId))
    }

    private fun scheduleFlush() {
        flushJob?.cancel()
        flushJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MILLIS)
            retryQueue()
        }
    }

    // Файл создаётся синхронно (просто File.createNewFile через File(...)),
    // сеть тут не участвует — можно дёргать прямо из Compose перед запуском
    // камеры.
    fun prepareCapture() = documents.createCaptureTarget()

    // Разгрузился фиксируется вместе с фото: сначала фото ставится в свою
    // очередь на загрузку (см. DocumentRepository — не блокирует событие,
    // если сети нет прямо сейчас), потом обычное событие Разгрузился.
    fun onPhotoCaptured(trip: TripDto, file: File) {
        val id = driverId ?: return
        val assignmentId = requireShiftStarted()?.assignmentId ?: return
        viewModelScope.launch {
            documents.enqueue(file = file, driverId = id, assignmentId = assignmentId, tripId = trip.id)
            eventQueue.enqueue(
                type = EventTypes.RAZGRUZILSYA,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id
            )
            reloadFromCache()
            // Отмены здесь нет: фото уже снято и лежит в своей очереди,
            // откат события оставил бы его висеть без рейса.
            emitFeedback(actionFeedbackText(EventTypes.RAZGRUZILSYA), null)
            retryQueue()
        }
    }

    // Разгрузка без фото: накладную не отдали, камера не сработала, телефон
    // сел. Без этого пути рейс нельзя закрыть вообще и встаёт вся смена,
    // поэтому причина уходит комментарием к событию, а не теряется.
    fun onUnloadWithoutPhoto(trip: TripDto, reason: String) {
        val id = driverId ?: return
        val assignmentId = requireShiftStarted()?.assignmentId ?: return
        viewModelScope.launch {
            val eventId = eventQueue.enqueue(
                type = EventTypes.RAZGRUZILSYA,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id,
                comment = "Без фото документа: $reason"
            )
            reloadFromCache()
            emitFeedback("Разгрузился, без фото", eventId)
            scheduleFlush()
        }
    }

    private suspend fun retryQueue() {
        queue.flush()
        reloadFromCache()
    }

    private suspend fun reloadFromCache() {
        val assignment = assignmentRepository.getCachedById(assignmentId) ?: return
        refresh(assignment)
    }
}
