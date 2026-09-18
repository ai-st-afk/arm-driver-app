package ru.profstroyservices.armdriver.ui.assignment

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
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface AssignmentUiState {
    data object Loading : AssignmentUiState
    data class Error(val message: String) : AssignmentUiState
    data class Content(
        val assignment: AssignmentDto,
        val acknowledged: Boolean,
        val shiftStarted: Boolean
    ) : AssignmentUiState
}

@HiltViewModel
class AssignmentViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AssignmentUiState>(AssignmentUiState.Loading)
    val uiState: StateFlow<AssignmentUiState> = _uiState.asStateFlow()

    private var driverId: String? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = AssignmentUiState.Loading
            val id = settings.driverId.first()
            if (id == null) {
                _uiState.value = AssignmentUiState.Error("GUID водителя не задан")
                return@launch
            }
            driverId = id

            val cached = assignmentRepository.getCached(id)
            val fresh = assignmentRepository.refresh(id).getOrNull()
            val assignment = fresh ?: cached

            if (assignment == null) {
                _uiState.value = AssignmentUiState.Error("Разнарядка недоступна: нет ни сети, ни кэша")
                return@launch
            }

            updateContent(assignment, id)
        }
    }

    private suspend fun updateContent(assignment: AssignmentDto, driverId: String) {
        val events: List<PendingEventEntity> = eventQueue.observeForAssignment(assignment.id).first()
        _uiState.value = AssignmentUiState.Content(
            assignment = assignment,
            acknowledged = events.any { it.type == EventTypes.OZNAKOMLENIE },
            shiftStarted = events.any { it.type == EventTypes.NACHALO_SMENY }
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
            updateContent(state.assignment, id)
        }
    }

    fun onStartShift(onStarted: () -> Unit) {
        val state = _uiState.value as? AssignmentUiState.Content ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.NACHALO_SMENY,
                driverId = id,
                assignmentId = state.assignment.id
            )
            updateContent(state.assignment, id)
            onStarted()
        }
    }
}
