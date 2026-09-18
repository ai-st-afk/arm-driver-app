package ru.profstroyservices.armdriver.ui.roadmap

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.EventTypes

private val actionLabels = mapOf(
    EventTypes.PRIBYL_NA_POGRUZKU to "Прибыл на погрузку",
    EventTypes.ZAGRUZILSYA_V_PUT to "Загрузился, в пути",
    EventTypes.PRIBYL_NA_RAZGRUZKU to "Прибыл на разгрузку",
    EventTypes.RAZGRUZILSYA to "Разгрузился"
)

@Composable
fun RoadmapScreen(viewModel: RoadmapViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var sryvTarget by remember { mutableStateOf<TripDto?>(null) }

    Scaffold { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = uiState) {
                is RoadmapUiState.Loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) { CircularProgressIndicator() }

                is RoadmapUiState.Error -> Text(
                    text = state.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )

                is RoadmapUiState.Content -> Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(state.trips) { tripState ->
                            TripCard(
                                state = tripState,
                                onAction = { type -> viewModel.onTripAction(tripState.trip, type) },
                                onSryv = { sryvTarget = tripState.trip }
                            )
                        }
                    }

                    Button(
                        onClick = viewModel::onEndShift,
                        enabled = !state.shiftEnded,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Text(if (state.shiftEnded) "Смена закончена" else "Закончить смену")
                    }
                }
            }
        }
    }

    sryvTarget?.let { trip ->
        SryvDialog(
            onConfirm = { comment ->
                viewModel.onSryv(trip, comment)
                sryvTarget = null
            },
            onDismiss = { sryvTarget = null }
        )
    }
}

@Composable
private fun TripCard(
    state: TripUiState,
    onAction: (String) -> Unit,
    onSryv: () -> Unit
) {
    val trip = state.trip
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = "Ездка ${trip.order}", style = MaterialTheme.typography.titleMedium)
            if (state.isCancelled) {
                Text(text = "снято", color = MaterialTheme.colorScheme.error)
            }
            Text(text = "Погрузка: ${trip.loadPoint.address ?: trip.loadPoint.name ?: "—"}")
            Text(text = "Разгрузка: ${trip.unloadPoint.address ?: trip.unloadPoint.name ?: "—"}")
            trip.trailer.plate?.let { Text(text = "Прицеп: $it") }
            trip.cargo.composition?.let { Text(text = "Груз: $it") }

            if (!state.isCancelled) {
                if (state.doneTypes.contains(EventTypes.RAZGRUZILSYA)) {
                    Text(text = "Ездка завершена", color = MaterialTheme.colorScheme.primary)
                } else if (state.doneTypes.contains(EventTypes.SRYV)) {
                    Text(text = "Ездка сорвана", color = MaterialTheme.colorScheme.error)
                } else {
                    state.nextAction?.let { next ->
                        Button(onClick = { onAction(next) }, modifier = Modifier.fillMaxWidth()) {
                            Text(actionLabels.getValue(next))
                        }
                    }
                    OutlinedButton(onClick = onSryv, modifier = Modifier.fillMaxWidth()) {
                        Text("Ездка сорвана")
                    }
                }
            }
        }
    }
}

@Composable
private fun SryvDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var comment by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Причина срыва") },
        text = {
            OutlinedTextField(
                value = comment,
                onValueChange = { comment = it },
                label = { Text("Комментарий (обязательно)") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(comment) }, enabled = comment.isNotBlank()) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}
