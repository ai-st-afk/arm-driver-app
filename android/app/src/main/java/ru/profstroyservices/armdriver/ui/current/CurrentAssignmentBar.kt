package ru.profstroyservices.armdriver.ui.current

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.data.repository.AssignmentStateRepository
import ru.profstroyservices.armdriver.data.repository.currentAssignment
import ru.profstroyservices.armdriver.ui.assignment.statusLabel
import ru.profstroyservices.armdriver.ui.theme.statusActiveColor
import ru.profstroyservices.armdriver.ui.theme.statusActiveContainer
import javax.inject.Inject

// null — ещё не загрузилось; state = null — текущей разнарядки нет.
data class CurrentAssignment(
    val state: AssignmentState?,
    val totalAssignments: Int,
    val newSameDayAssignment: AssignmentState?
)

fun currentWithNewAssignment(all: List<AssignmentState>): CurrentAssignment {
    val current = currentAssignment(all)
    val newSameDay = current?.takeIf {
        it.phase == AssignmentPhase.IN_SHIFT ||
            it.phase == AssignmentPhase.FINISHED && !it.assignment.shiftClosed
    }?.let { open ->
        all.firstOrNull {
            it.id != open.id && it.assignment.departureDay == open.assignment.departureDay &&
                it.phase == AssignmentPhase.NEW
        }
    }
    return CurrentAssignment(current, all.size, newSameDay)
}

@HiltViewModel
class CurrentAssignmentViewModel @Inject constructor(
    private val states: AssignmentStateRepository
) : ViewModel() {

    val current: StateFlow<CurrentAssignment?> = states.observeStates()
        .map(::currentWithNewAssignment)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun refresh() {
        viewModelScope.launch { states.refresh() }
    }
}

// Строка сверху на всех вкладках: какая разнарядка сейчас текущая и в каком
// она состоянии. Действия по рейсам и ожидание закрытия смены показаны
// в самой разнарядке.
@Composable
fun CurrentAssignmentBar(state: AssignmentState, modifier: Modifier = Modifier) {
    val inShift = state.phase == AssignmentPhase.IN_SHIFT && !state.cancelledByDispatcher
    Text(
        text = "Разнарядка № ${state.label} — ${statusLabel(state)}",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (inShift) statusActiveColor() else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .background(if (inShift) statusActiveContainer() else MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    )
}
