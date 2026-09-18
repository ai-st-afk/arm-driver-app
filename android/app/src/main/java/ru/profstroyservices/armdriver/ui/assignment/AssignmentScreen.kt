package ru.profstroyservices.armdriver.ui.assignment

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.R
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.ui.components.LabeledField

private val ButtonHeight = 56.dp

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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppHeader()

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
private fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = "АРМ водителя",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 10.dp)
        )
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

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LabeledField(label = "Дата выезда", value = assignment.departureDay)
        LabeledField(
            label = "Водитель",
            value = assignment.driver.name ?: assignment.driver.id
        )
        LabeledField(
            label = "Машина",
            value = "${assignment.vehicle.name ?: ""} ${assignment.vehicle.plate ?: ""}".trim()
        )
        LabeledField(label = "Ездок в разнарядке", value = assignment.trips.size.toString())
    }

    Button(
        onClick = onAcknowledge,
        enabled = !state.acknowledged,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight)
    ) {
        Text(if (state.acknowledged) "Ознакомлен" else "Ознакомлен?", style = MaterialTheme.typography.labelLarge)
    }

    Button(
        onClick = onStartShift,
        enabled = state.acknowledged && !state.shiftStarted,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
    ) {
        Text("Начать смену", style = MaterialTheme.typography.labelLarge)
    }

    if (state.shiftStarted) {
        Button(
            onClick = onOpenRoadmap,
            modifier = Modifier.fillMaxWidth().height(ButtonHeight)
        ) {
            Text("К списку ездок", style = MaterialTheme.typography.labelLarge)
        }
    }
}
