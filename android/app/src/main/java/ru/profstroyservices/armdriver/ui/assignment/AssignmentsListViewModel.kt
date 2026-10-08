package ru.profstroyservices.armdriver.ui.assignment

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.data.repository.AssignmentStateRepository
import ru.profstroyservices.armdriver.data.repository.currentAssignment
import javax.inject.Inject

data class AssignmentSummary(
    val id: String,
    val label: String,
    val departureDay: String,
    val statusLabel: String,
    val isCurrent: Boolean
)

sealed interface AssignmentsListUiState {
    data object Loading : AssignmentsListUiState
    data class Loaded(
        val assignments: List<AssignmentSummary>,
        val current: AssignmentState?
    ) : AssignmentsListUiState
}

@HiltViewModel
class AssignmentsListViewModel @Inject constructor(
    private val states: AssignmentStateRepository
) : ViewModel() {

    private val refreshing = MutableStateFlow(true)

    val uiState: StateFlow<AssignmentsListUiState> =
        combine(states.observeStates(), refreshing) { all, refreshing ->
            // Пока первый запрос не вернулся и кэш пуст — это загрузка, а не
            // «разнарядок нет».
            if (all.isEmpty() && refreshing) return@combine AssignmentsListUiState.Loading
            val current = currentAssignment(all)
            // Хронологически (лента по дате выезда), текущая и более живая
            // фаза — выше при совпадении дня (например, старая и новая при
            // замене экипажа в один день).
            val ordered = all.sortedWith(
                compareBy<AssignmentState>({ it.assignment.departureDay }, { it.id != current?.id }, { phaseOrder(it.phase) })
            )
            AssignmentsListUiState.Loaded(
                assignments = ordered.map { state ->
                    AssignmentSummary(
                        id = state.id,
                        label = state.label,
                        departureDay = state.assignment.departureDay,
                        statusLabel = statusLabel(state),
                        isCurrent = state.id == current?.id
                    )
                },
                current = current
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AssignmentsListUiState.Loading)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            states.refresh()
            refreshing.value = false
        }
    }

    private fun phaseOrder(phase: AssignmentPhase): Int = when (phase) {
        AssignmentPhase.IN_SHIFT -> 0
        AssignmentPhase.NEW, AssignmentPhase.ACCEPTED -> 1
        AssignmentPhase.FINISHED, AssignmentPhase.CANCELLED -> 2
    }
}
