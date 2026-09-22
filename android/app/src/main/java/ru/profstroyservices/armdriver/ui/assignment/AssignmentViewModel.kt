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
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface AssignmentUiState {
    data object Loading : AssignmentUiState
    data class Error(val message: String) : AssignmentUiState
    data class Content(
        val assignment: AssignmentDto,
        val acknowledged: Boolean,
        val shiftStarted: Boolean,
        val shiftEnded: Boolean,
        val updatedAt: Long?
    ) : AssignmentUiState
}

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

    private var driverId: String? = null

    init {
        load()
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
        val events: List<PendingEventEntity> = eventQueue.observeForAssignment(assignment.id).first()
        _uiState.value = AssignmentUiState.Content(
            assignment = assignment,
            acknowledged = events.any { it.type == EventTypes.OZNAKOMLENIE },
            shiftStarted = events.any { it.type == EventTypes.NACHALO_SMENY },
            shiftEnded = events.any { it.type == EventTypes.OKONCHANIE_SMENY },
            updatedAt = assignmentRepository.getCachedUpdatedAt(assignment.id)
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

    fun onStartShift() {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.NACHALO_SMENY,
                driverId = id,
                assignmentId = state.assignment.id
            )
            updateContent(state.assignment)
            queue.flush()
        }
    }

    fun onEndShift() {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.OKONCHANIE_SMENY,
                driverId = id,
                assignmentId = state.assignment.id
            )
            updateContent(state.assignment)
            queue.flush()
        }
    }
}
