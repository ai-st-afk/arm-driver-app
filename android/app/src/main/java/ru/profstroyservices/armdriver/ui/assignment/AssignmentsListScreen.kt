package ru.profstroyservices.armdriver.ui.assignment

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.profstroyservices.armdriver.ui.theme.statusActiveColor

enum class GateMode {
    // Вкладка «Разнарядка»: одна разнарядка — сразу она. Несколько — всегда
    // список (текущая первой): при замене экипажа 1С присылает новую рядом
    // со старой, и водитель должен видеть обе, а не застрять на одной.
    ASSIGNMENTS,

    // Вкладка «Мои рейсы»: только рейсы текущей разнарядки. Нет текущей —
    // пусто, пока диспетчер не пришлёт новую.
    TRIPS
}

@Composable
fun AssignmentsGate(
    mode: GateMode,
    onOpen: (assignmentId: String) -> Unit,
    onSelectFromList: (assignmentId: String) -> Unit,
    viewModel: AssignmentsListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold { innerPadding ->
        when (val state = uiState) {
            is AssignmentsListUiState.Loading -> Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { CircularProgressIndicator() }

            is AssignmentsListUiState.Loaded -> {
                val target = when (mode) {
                    GateMode.TRIPS -> state.current?.id
                    GateMode.ASSIGNMENTS -> state.assignments.singleOrNull()?.id
                }
                when {
                    target != null -> LaunchedEffect(target) { onOpen(target) }
                    mode == GateMode.TRIPS -> EmptyState(
                        text = "Нет активной разнарядки.\nРейсы появятся здесь, когда диспетчер пришлёт новую.",
                        modifier = Modifier.padding(innerPadding)
                    )
                    state.assignments.isEmpty() -> EmptyState(
                        text = "Разнарядок пока нет",
                        modifier = Modifier.padding(innerPadding)
                    )
                    else -> AssignmentsList(
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
private fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun AssignmentsList(
    assignments: List<AssignmentSummary>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(assignments, key = { it.id }) { summary ->
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
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = if (summary.isCurrent) BorderStroke(2.dp, statusActiveColor()) else null
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = "Разнарядка № ${summary.label}", style = MaterialTheme.typography.titleMedium)
            Text(text = "Дата выезда: ${summary.departureDay}", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = if (summary.isCurrent) "текущая · ${summary.statusLabel}" else summary.statusLabel,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
