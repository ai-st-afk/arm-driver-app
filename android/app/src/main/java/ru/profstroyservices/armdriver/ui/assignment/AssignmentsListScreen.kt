package ru.profstroyservices.armdriver.ui.assignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect

// Общий вход для корня таба «Разнарядка» и таба «Мои рейсы»: обе вкладки
// смотрят на один и тот же список разнарядок водителя за окно (см.
// ASSIGNMENT_LIST_WINDOW_HOURS на backend) и различаются только тем, куда
// ведут — на карточку разнарядки или сразу на roadmap. Если разнарядка
// одна (обычный случай), список не показываем вообще — сразу переходим
// дальше через onSingle; список-пикер нужен только когда их несколько.
@Composable
fun AssignmentsGate(
    onSingle: (assignmentId: String) -> Unit,
    onSelectFromList: (assignmentId: String) -> Unit,
    // Только для таба «Мои рейсы»: если смена уже начата по одной из
    // разнарядок, список-пикер незачем показывать — водителю нужны только
    // её рейсы, а не выбор из всех разнарядок за день заново.
    autoSelectActive: Boolean = false,
    viewModel: AssignmentsListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold { innerPadding ->
        when (val state = uiState) {
            is AssignmentsListUiState.Loading -> AssignmentsListLoading(
                modifier = Modifier.padding(innerPadding)
            )

            is AssignmentsListUiState.Error -> AssignmentsListError(
                message = state.message,
                modifier = Modifier.padding(innerPadding)
            )

            is AssignmentsListUiState.Loaded -> {
                // Текущая смена — последняя начатая из открытых, так же как в
                // ShiftViewModel, иначе плашка и рейсы показывали бы разное.
                val target = state.assignments.singleOrNull()
                    ?: state.assignments.takeIf { autoSelectActive }
                        ?.filter { it.openShiftStartedAt != null }
                        ?.maxByOrNull { it.openShiftStartedAt!! }
                if (target != null) {
                    LaunchedEffect(target.id) { onSingle(target.id) }
                } else {
                    AssignmentsListContent(
                        assignments = state.assignments,
                        onSelect = onSelectFromList,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun AssignmentsListContent(
    assignments: List<AssignmentSummary>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (assignments.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = "Разнарядок пока нет", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(assignments) { summary ->
            AssignmentSummaryCard(summary = summary, onClick = { onSelect(summary.id) })
        }
    }
}

@Composable
private fun AssignmentSummaryCard(summary: AssignmentSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "Разнарядка № ${summary.number ?: summary.id}",
                style = MaterialTheme.typography.titleMedium
            )
            Text(text = "Дата выезда: ${summary.departureDay}", style = MaterialTheme.typography.bodyMedium)
            Text(text = summary.statusLabel, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AssignmentsListLoading(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AssignmentsListError(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.padding(20.dp)
    )
}
