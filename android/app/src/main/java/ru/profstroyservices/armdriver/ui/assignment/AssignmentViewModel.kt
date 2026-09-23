package ru.profstroyservices.armdriver.ui.assignment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.repository.openShiftStartedAt
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

private const val STATUS_CANCELLED = "Отменена"

sealed interface AssignmentUiState {
    data object Loading : AssignmentUiState
    data class Error(val message: String) : AssignmentUiState
    data class Content(
        val assignment: AssignmentDto,
        val acknowledged: Boolean,
        val shiftStarted: Boolean,
        val shiftEnded: Boolean,
        // Водитель отказался от разнарядки сам, до начала смены (не путать
        // со «Срыв» — тот про конкретный рейс и только после начала смены).
        val cancelledByDriver: Boolean,
        val updatedAt: Long?,
        // Разнарядка сама не шлёт статус завершения (в контракте с 1С его
        // нет) — считаем локально по рейсам: закрыт (Разгрузился), сорван
        // или снят диспетчером, и так по каждому. Автор попросил именно
        // так, без отдельной кнопки «Завершить разнарядку».
        val tripsCompleted: Int,
        val tripsTotal: Int
    ) : AssignmentUiState
}

data class ShiftSwitchPrompt(
    val openAssignmentIds: List<String>,
    val openAssignmentsLabel: String
)

@HiltViewModel
class AssignmentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val queue: QueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val assignmentId: String = checkNotNull(savedStateHandle["assignmentId"])

    private val _uiState = MutableStateFlow<AssignmentUiState>(AssignmentUiState.Loading)
    val uiState: StateFlow<AssignmentUiState> = _uiState.asStateFlow()

    private val _shiftSwitchPrompt = MutableStateFlow<ShiftSwitchPrompt?>(null)
    val shiftSwitchPrompt: StateFlow<ShiftSwitchPrompt?> = _shiftSwitchPrompt.asStateFlow()

    private var driverId: String? = null
    private var currentAssignment: AssignmentDto? = null

    init {
        load()
        // События по разнарядке пишут и другие экраны (плашка «Закончить
        // смену», рейсы) — без подписки статус здесь отставал до выхода с экрана.
        viewModelScope.launch {
            eventQueue.observeForAssignment(assignmentId).collect { events ->
                currentAssignment?.let { render(it, events) }
            }
        }
    }

    // Дёргается при каждом возврате на экран (в том числе при переключении
    // вкладок и возврате в приложение): диспетчер правит разнарядку до 17:00,
    // а push пока нет — без этого водитель смотрел бы на устаревшие данные.
    fun refresh() {
        if (driverId == null) return
        viewModelScope.launch {
            val fresh = runCatching { assignmentRepository.getById(assignmentId) }.getOrNull() ?: return@launch
            updateContent(fresh)
        }
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = AssignmentUiState.Loading
            val id = settings.driverId.first()
            if (id == null) {
                _uiState.value = AssignmentUiState.Error("Телефон не настроен. Обратитесь к диспетчеру.")
                return@launch
            }
            driverId = id

            val cached = assignmentRepository.getCachedById(assignmentId)
            val fresh = runCatching { assignmentRepository.getById(assignmentId) }.getOrNull()
            val assignment = fresh ?: cached

            if (assignment == null) {
                _uiState.value = AssignmentUiState.Error("Нет связи и нет сохранённой разнарядки. Проверьте интернет.")
                return@launch
            }

            updateContent(assignment)
        }
    }

    private suspend fun updateContent(assignment: AssignmentDto) {
        currentAssignment = assignment
        render(assignment, eventQueue.observeForAssignment(assignment.id).first())
    }

    private suspend fun render(assignment: AssignmentDto, events: List<PendingEventEntity>) {
        val tripsCompleted = assignment.trips.count { trip ->
            trip.status == STATUS_CANCELLED ||
                events.any {
                    it.tripId == trip.id && !it.cancelled &&
                        (it.type == EventTypes.RAZGRUZILSYA || it.type == EventTypes.SRYV)
                }
        }
        _uiState.value = AssignmentUiState.Content(
            assignment = assignment,
            acknowledged = events.any { it.type == EventTypes.OZNAKOMLENIE && !it.cancelled },
            shiftStarted = events.any { it.type == EventTypes.NACHALO_SMENY && !it.cancelled },
            shiftEnded = events.any { it.type == EventTypes.OKONCHANIE_SMENY && !it.cancelled },
            cancelledByDriver = events.any { it.type == EventTypes.OTKAZ_OT_RAZNARYADKI && !it.cancelled },
            updatedAt = assignmentRepository.getCachedUpdatedAt(assignment.id),
            tripsCompleted = tripsCompleted,
            tripsTotal = assignment.trips.size
        )
    }

    fun onAcknowledge() {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.OZNAKOMLENIE,
                driverId = id,
                assignmentId = state.assignment.id
            )
            updateContent(state.assignment)
            queue.flush()
        }
    }

    // Открытой может быть только одна смена. Иначе водитель, не закрыв смену
    // по предыдущей разнарядке, начинал следующую — и было непонятно, чьи
    // рейсы показывать и какую смену закрывает общая кнопка. Поэтому при
    // открытой другой смене сначала спрашиваем, закрыть ли её.
    fun onStartShift() {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            val others = assignmentRepository.getCachedList(id)
                .filter { it.id != state.assignment.id }
                .filter { openShiftStartedAt(eventQueue.observeForAssignment(it.id).first()) != null }
            if (others.isNotEmpty()) {
                _shiftSwitchPrompt.value = ShiftSwitchPrompt(
                    openAssignmentIds = others.map { it.id },
                    openAssignmentsLabel = others.joinToString { it.number ?: it.id }
                )
                return@launch
            }
            startShift(state.assignment, id)
        }
    }

    fun onConfirmShiftSwitch() {
        val prompt = _shiftSwitchPrompt.value ?: return
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        _shiftSwitchPrompt.value = null
        viewModelScope.launch {
            prompt.openAssignmentIds.forEach { otherId ->
                eventQueue.enqueue(
                    type = EventTypes.OKONCHANIE_SMENY,
                    driverId = id,
                    assignmentId = otherId
                )
            }
            startShift(state.assignment, id)
        }
    }

    fun onDismissShiftSwitch() {
        _shiftSwitchPrompt.value = null
    }

    private suspend fun startShift(assignment: AssignmentDto, driverId: String) {
        eventQueue.enqueue(
            type = EventTypes.NACHALO_SMENY,
            driverId = driverId,
            assignmentId = assignment.id
        )
        updateContent(assignment)
        queue.flush()
    }

    // Водитель отказывается от разнарядки сам, до начала смены — не звонит
    // диспетчеру, а сразу пишет причину. Доступно только пока смена не
    // начата: после НачалоСмены это уже другая ситуация («Срыв» по рейсу).
    fun onCancelAssignment(reason: String) {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        if (state.shiftStarted) return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.OTKAZ_OT_RAZNARYADKI,
                driverId = id,
                assignmentId = state.assignment.id,
                comment = reason
            )
            updateContent(state.assignment)
            queue.flush()
        }
    }
}
