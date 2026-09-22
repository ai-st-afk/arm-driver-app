package ru.profstroyservices.armdriver.ui.assignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

data class AssignmentSummary(
    val id: String,
    val number: String?,
    val departureDay: String,
    val statusLabel: String
)

sealed interface AssignmentsListUiState {
    data object Loading : AssignmentsListUiState
    data class Error(val message: String) : AssignmentsListUiState
    data class Loaded(val assignments: List<AssignmentSummary>) : AssignmentsListUiState
}

// Общий источник данных для корня таба «Разнарядка» и пикера в табе
// «Мои ездки» — за день у водителя может быть несколько разнарядок
// (Stage 8), оба таба показывают один и тот же список, только ведут по
// тапу в разные маршруты.
@HiltViewModel
class AssignmentsListViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<AssignmentsListUiState>(AssignmentsListUiState.Loading)
    val uiState: StateFlow<AssignmentsListUiState> = _uiState.asStateFlow()

    init {
        load(showLoading = true)
    }

    // Повторная загрузка при каждом возврате на вкладку и в приложение —
    // без неё список живёт с момента холодного старта (стек вкладки
    // восстанавливается вместе с ViewModel, init второй раз не сработает).
    fun refresh() = load(showLoading = false)

    private fun load(showLoading: Boolean) {
        viewModelScope.launch {
            if (showLoading) _uiState.value = AssignmentsListUiState.Loading
            val driverId = settings.driverId.first()
            if (driverId == null) {
                _uiState.value = AssignmentsListUiState.Error("Телефон не настроен. Обратитесь к диспетчеру.")
                return@launch
            }

            val fresh = assignmentRepository.refreshList(driverId).getOrNull()
            val assignments = fresh ?: assignmentRepository.getCachedList(driverId)
            val summaries = mutableListOf<AssignmentSummary>()
            for (assignment in assignments) summaries.add(assignment.toSummary())
            _uiState.value = AssignmentsListUiState.Loaded(summaries)
        }
    }

    private suspend fun AssignmentDto.toSummary(): AssignmentSummary {
        val events = eventQueue.observeForAssignment(id).first()
        val statusLabel = when {
            events.any { it.type == EventTypes.OKONCHANIE_SMENY } -> "смена завершена"
            events.any { it.type == EventTypes.NACHALO_SMENY } -> "смена идёт"
            events.any { it.type == EventTypes.OZNAKOMLENIE } -> "ознакомлен"
            else -> "не ознакомлен"
        }
        return AssignmentSummary(id = id, number = number, departureDay = departureDay, statusLabel = statusLabel)
    }
}
