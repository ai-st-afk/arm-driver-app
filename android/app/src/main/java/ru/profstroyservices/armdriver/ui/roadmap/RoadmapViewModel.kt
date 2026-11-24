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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.update.AppUpdater
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.data.repository.AssignmentStateRepository
import ru.profstroyservices.armdriver.data.repository.AssignmentPhotoCompletion
import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.DocumentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.repository.PhotoKind
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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

sealed interface RoadmapUiState {
    data object Loading : RoadmapUiState
    data class Error(val message: String) : RoadmapUiState
    data class Content(val state: AssignmentState) : RoadmapUiState
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
    private val states: AssignmentStateRepository,
    private val eventQueue: EventQueueRepository,
    private val documents: DocumentRepository,
    private val photoCompletion: AssignmentPhotoCompletion,
    private val queue: QueueRepository,
    private val settings: DriverSettingsRepository,
    private val updater: AppUpdater
) : ViewModel() {

    private val assignmentId: String = checkNotNull(savedStateHandle["assignmentId"])

    private val refreshing = MutableStateFlow(true)

    // Реактивно от Room: новая версия разнарядки (диспетчер снял рейс) или
    // событие с другого экрана видны здесь сразу, без ручной перезагрузки.
    val uiState: StateFlow<RoadmapUiState> =
        combine(states.observeState(assignmentId), refreshing) { state, refreshing ->
            when {
                state != null -> RoadmapUiState.Content(state)
                refreshing -> RoadmapUiState.Loading
                else -> RoadmapUiState.Error("Разнарядка не найдена. Откройте вкладку «Разнарядка».")
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, RoadmapUiState.Loading)

    private val _feedback = MutableSharedFlow<ActionFeedback>(extraBufferCapacity = 1)
    val feedback: SharedFlow<ActionFeedback> = _feedback.asSharedFlow()

    private var flushJob: Job? = null

    init {
        viewModelScope.launch { queue.flush() }
    }

    fun refreshFromNetwork() {
        viewModelScope.launch {
            states.refreshOne(assignmentId)
            refreshing.value = false
        }
    }

    private fun currentState(): AssignmentState? = (uiState.value as? RoadmapUiState.Content)?.state

    // Проверка и здесь, а не только в UI: кнопку может открыть случайный
    // кадр рекомпозиции, а enqueue пишет реальный факт с GUID.
    private fun markableState(): AssignmentState? {
        val state = currentState() ?: return null
        if (state.canMarkTrips) return state
        emitFeedback(
            when {
                state.phase == AssignmentPhase.REPLACED -> "Разнарядка заменена. Откройте новую разнарядку"
                state.cancelledByDispatcher -> "Разнарядка отменена диспетчером"
                state.phase == AssignmentPhase.FINISHED -> "Смена по этой разнарядке уже закончена"
                state.assignment.shiftStarted && !state.assignment.keysIssued -> "Дождитесь выдачи ключей от новой машины"
                else -> "Сначала начните смену на вкладке «Разнарядка»"
            },
            null
        )
        return null
    }

    fun onTripAction(trip: TripDto, type: String) {
        onTripAction(trip.id, type)
    }

    fun onTripAction(tripId: String, type: String, comment: String = "") {
        if (type == EventTypes.PRIBYL_NA_POGRUZKU && updater.blocksNewTrip()) {
            emitFeedback("Сначала установите обязательное обновление приложения", null)
            return
        }
        val state = markableState() ?: return
        if (!state.canMarkTripStep(tripId, type)) return
        viewModelScope.launch {
            if (!photoCompletion.isDone(state.id, PhotoKind.VEHICLE_KEYS)) {
                emitFeedback("Сначала сфотографируйте машину с четырёх сторон после выдачи ключей", null)
                return@launch
            }
            val eventId = enqueue(state, type, tripId, comment.take(500)) ?: return@launch
            emitFeedback(actionFeedbackText(type), eventId)
            scheduleFlush()
        }
    }

    fun onTripFailure(tripId: String, reason: String, comment: String, reasonCode: String?, photos: List<CapturedPhoto>) {
        val state = markableState()
        val progress = state?.trips?.firstOrNull { it.trip.id == tripId }
        val cleanReason = reason.trim()
        val cleanComment = comment.trim()
        if (progress == null || progress.isResolved || progress.isInactive || cleanReason.isEmpty() || cleanReason.length > 200 ||
            cleanComment.length > 500 || (progress.isClientRefusalAvailable && reasonCode !in setOf("КлиентНеГотов", "Качество", "Прочее")) ||
            (!progress.isClientRefusalAvailable && reasonCode != null) || (reasonCode == "Прочее" && cleanComment.isEmpty()) ||
            (reasonCode == "Качество" && photos.none { it.kind == ru.profstroyservices.armdriver.data.repository.PhotoKind.CARGO })
        ) {
            photos.forEach { File(it.path).delete() }
            return
        }
        viewModelScope.launch {
            val driverId = settings.driverId.first() ?: return@launch
            val eventId = enqueue(state, EventTypes.SRYV, tripId, comment = cleanComment, reason = cleanReason, reasonCode = reasonCode)
            if (eventId == null) {
                photos.forEach { File(it.path).delete() }
                return@launch
            }
            try {
                if (photos.isNotEmpty()) {
                    documents.enqueueTripPhotos(photos.map { File(it.path) }, driverId, state.id, tripId, eventId, PhotoKind.CARGO)
                }
            } catch (error: Exception) {
                eventQueue.cancelPending(eventId)
                documents.discardForEvent(eventId)
                photos.forEach { File(it.path).delete() }
                emitFeedback("Фото не сохранилось, повторите отказ", null)
                return@launch
            }
            emitFeedback("Не могу выполнить рейс", null)
            flushJob?.cancel()
            queue.flush()
        }
    }

    // Быстрая отмена сразу после нажатия (кнопка «Отменить» в снекбаре).
    // Работает только до фактической отправки. Само событие пишется в
    // очередь сразу — GUID и время фиксируются в момент нажатия (инвариант 1),
    // откладывается только отправка.
    fun onUndo(eventId: String) {
        viewModelScope.launch {
            flushJob?.cancel()
            eventQueue.cancelPending(eventId)
            scheduleFlush()
        }
    }

    // Кнопка «Отмена» в карточке рейса: случайный лишний тап продвинул рейс
    // на шаг вперёд — откатываем последний шаг, пока он не ушёл в 1С. Это
    // исправление своей ошибки, а не отмена рейса (её делает диспетчер).
    fun holdUpdates(): () -> Unit = updater.holdInstall()

    fun onStepBack(trip: TripDto) {
        val state = currentState() ?: return
        val progress = state.trips.find { it.trip.id == trip.id } ?: return
        val lastType = EventTypes.TRIP_CYCLE.lastOrNull { it in progress.doneTypes } ?: return
        viewModelScope.launch {
            flushJob?.cancel()
            val cancelledId = eventQueue.cancelStep(state.id, trip.id, lastType)
            cancelledId?.let { documents.discardForEvent(it) }
            emitFeedback(
                if (cancelledId != null) "${actionFeedbackText(lastType)} — отменено" else "Шаг уже отправлен в 1С, отменить нельзя",
                null
            )
            scheduleFlush()
        }
    }

    private suspend fun enqueue(state: AssignmentState, type: String, tripId: String?, comment: String = "", reason: String? = null, reasonCode: String? = null): String? {
        val driverId = settings.driverId.first() ?: return null
        return eventQueue.enqueue(
            type = type,
            driverId = driverId,
            assignmentId = state.id,
            assignmentVersion = state.assignment.version,
            tripId = tripId,
            comment = comment,
            reason = reason,
            reasonCode = reasonCode
        )
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
            queue.flush()
        }
    }
}
