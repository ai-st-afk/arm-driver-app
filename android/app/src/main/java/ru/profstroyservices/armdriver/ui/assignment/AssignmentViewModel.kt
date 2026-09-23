package ru.profstroyservices.armdriver.ui.assignment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.data.repository.AssignmentStateRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface AssignmentUiState {
    data object Loading : AssignmentUiState
    data class Error(val message: String) : AssignmentUiState
    data class Content(
        val state: AssignmentState,
        // Смена открыта по другой разнарядке — эту начать нельзя, пока та не
        // закончена: активной бывает только одна разнарядка.
        val otherOpenShiftLabel: String?
    ) : AssignmentUiState
}

@HiltViewModel
class AssignmentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val states: AssignmentStateRepository,
    private val eventQueue: EventQueueRepository,
    private val queue: QueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val assignmentId: String = checkNotNull(savedStateHandle["assignmentId"])

    // Первый сетевой запрос ещё идёт — пока разнарядки нет в кэше, это
    // «загрузка», а не «не найдена».
    private val refreshing = MutableStateFlow(true)

    val uiState: StateFlow<AssignmentUiState> =
        combine(states.observeStates(), refreshing) { all, refreshing ->
            val state = all.firstOrNull { it.id == assignmentId }
            when {
                state != null -> AssignmentUiState.Content(
                    state = state,
                    otherOpenShiftLabel = all
                        .firstOrNull { it.id != assignmentId && it.phase == AssignmentPhase.IN_SHIFT }
                        ?.label
                )
                refreshing -> AssignmentUiState.Loading
                else -> AssignmentUiState.Error("Нет связи и нет сохранённой разнарядки. Проверьте интернет.")
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentUiState.Loading)

    init {
        refresh()
    }

    // При каждом возврате на экран: диспетчер правит разнарядку, новая
    // версия должна быть видна без перезапуска.
    fun refresh() {
        viewModelScope.launch {
            states.refreshOne(assignmentId)
            refreshing.value = false
        }
    }

    fun onAcknowledge() = send(EventTypes.OZNAKOMLENIE) { it.phase == AssignmentPhase.NEW }

    fun onStartShift() = send(EventTypes.NACHALO_SMENY) { it.phase == AssignmentPhase.ACCEPTED }

    fun onEndShift() = send(EventTypes.OKONCHANIE_SMENY) { it.canEndShift }

    private fun send(type: String, allowed: (AssignmentState) -> Boolean) {
        val content = uiState.value as? AssignmentUiState.Content ?: return
        if (!allowed(content.state)) return
        if (type == EventTypes.NACHALO_SMENY && content.otherOpenShiftLabel != null) return
        viewModelScope.launch {
            val driverId = settings.driverId.first() ?: return@launch
            eventQueue.enqueue(
                type = type,
                driverId = driverId,
                assignmentId = content.state.id,
                assignmentVersion = content.state.assignment.version
            )
            queue.flush()
        }
    }
}
