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
import ru.profstroyservices.armdriver.ui.components.tripsSummary
import javax.inject.Inject

data class AssignmentSummary(
    val id: String,
    val label: String,
    val departureDay: String,
    val statusLabel: String,
    val isCurrent: Boolean,
    // Принята, но смена ещё не начата — список подсвечивает такие жёлтым,
    // чтобы сразу было видно, что это предстоит, а не прошлое/текущее.
    val isPending: Boolean,
    // Только для истории (FINISHED/CANCELLED) — водитель видит, что это
    // была за поездка, без открытия самой разнарядки.
    val tripsSummary: String?
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
            assignmentsListState(all)
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
}

// Вынесено из ViewModel как чистая функция — тестируется без Room/DataStore,
// по образцу currentAssignment() в AssignmentState.kt.
fun assignmentsListState(all: List<AssignmentState>): AssignmentsListUiState.Loaded {
    val current = currentAssignment(all)
    // Новые дни выезда — сверху (водителю актуальнее «сегодня/завтра», чем
    // то, что уже прошло); внутри одного дня текущая и более живая фаза —
    // выше (например, старая и новая при замене экипажа в один день).
    val ordered = all.sortedWith(
        compareByDescending<AssignmentState> { it.assignment.departureDay }
            .thenBy { it.id != current?.id }
            .thenBy { phaseOrder(it.phase) }
    )
    return AssignmentsListUiState.Loaded(
        assignments = ordered.map { state ->
            AssignmentSummary(
                id = state.id,
                label = state.label,
                departureDay = state.assignment.departureDay,
                statusLabel = statusLabel(state),
                isCurrent = state.id == current?.id,
                isPending = state.phase == AssignmentPhase.NEW || state.phase == AssignmentPhase.ACCEPTED,
                tripsSummary = if (state.phase == AssignmentPhase.FINISHED || state.phase == AssignmentPhase.CANCELLED) {
                    tripsSummary(state.trips.map { it.trip })
                } else {
                    null
                }
            )
        },
        current = current
    )
}

private fun phaseOrder(phase: AssignmentPhase): Int = when (phase) {
    AssignmentPhase.IN_SHIFT -> 0
    AssignmentPhase.NEW, AssignmentPhase.ACCEPTED -> 1
    AssignmentPhase.FINISHED, AssignmentPhase.CANCELLED -> 2
}
