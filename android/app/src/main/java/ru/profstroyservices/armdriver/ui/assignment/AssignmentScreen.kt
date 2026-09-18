package ru.profstroyservices.armdriver.ui.assignment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.data.network.AssignmentDto

@Composable
fun AssignmentScreen(
    onOpenRoadmap: () -> Unit,
    viewModel: AssignmentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (val state = uiState) {
                is AssignmentUiState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }

                is AssignmentUiState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error
                )

                is AssignmentUiState.Content -> AssignmentContent(
                    state = state,
                    onAcknowledge = viewModel::onAcknowledge,
                    onStartShift = { viewModel.onStartShift(onOpenRoadmap) },
                    onOpenRoadmap = onOpenRoadmap
                )
            }
        }
    }
}

@Composable
private fun AssignmentContent(
    state: AssignmentUiState.Content,
    onAcknowledge: () -> Unit,
    onStartShift: () -> Unit,
    onOpenRoadmap: () -> Unit
) {
    val assignment: AssignmentDto = state.assignment

    Text(text = "Разнарядка № ${assignment.number ?: assignment.id}", style = MaterialTheme.typography.headlineSmall)
    Text(text = "Дата выезда: ${assignment.departureDay}", style = MaterialTheme.typography.bodyMedium)
    Text(
        text = "Водитель: ${assignment.driver.name ?: assignment.driver.id}",
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text = "Машина: ${assignment.vehicle.name ?: ""} ${assignment.vehicle.plate ?: ""}".trim(),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(text = "Ездок в разнарядке: ${assignment.trips.size}", style = MaterialTheme.typography.bodyMedium)

    Button(
        onClick = onAcknowledge,
        enabled = !state.acknowledged,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (state.acknowledged) "Ознакомлен" else "Ознакомлен?")
    }

    Button(
        onClick = onStartShift,
        enabled = state.acknowledged && !state.shiftStarted,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Начать смену")
    }

    if (state.shiftStarted) {
        Button(onClick = onOpenRoadmap, modifier = Modifier.fillMaxWidth()) {
            Text("К списку ездок")
        }
    }
}
