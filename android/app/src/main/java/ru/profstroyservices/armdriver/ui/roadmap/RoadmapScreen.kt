package ru.profstroyservices.armdriver.ui.roadmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.EventTypes

private val ButtonHeight = 56.dp
private val CompletedGreen = Color(0xFF2E7D32)

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
                    modifier = Modifier.padding(20.dp)
                )

                is RoadmapUiState.Content -> Column(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
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
                        modifier = Modifier.padding(20.dp).fillMaxWidth().height(ButtonHeight)
                    ) {
                        Text(
                            if (state.shiftEnded) "Смена закончена" else "Закончить смену",
                            style = MaterialTheme.typography.labelLarge
                        )
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
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = "Ездка ${trip.order}", style = MaterialTheme.typography.titleMedium)
            if (state.isCancelled) {
                Text(text = "снято", color = MaterialTheme.colorScheme.error)
            }
            Text(text = "Погрузка: ${trip.loadPoint.address ?: trip.loadPoint.name ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "Разгрузка: ${trip.unloadPoint.address ?: trip.unloadPoint.name ?: "—"}", style = MaterialTheme.typography.bodyMedium)
            trip.trailer.plate?.let { Text(text = "Прицеп: $it", style = MaterialTheme.typography.bodyMedium) }
            trip.cargo.composition?.let { Text(text = "Груз: $it", style = MaterialTheme.typography.bodyMedium) }

            if (!state.isCancelled) {
                if (state.doneTypes.contains(EventTypes.RAZGRUZILSYA)) {
                    Text(text = "Ездка завершена", color = CompletedGreen)
                } else if (state.doneTypes.contains(EventTypes.SRYV)) {
                    Text(text = "Ездка сорвана", color = MaterialTheme.colorScheme.error)
                } else {
                    state.nextAction?.let { next ->
                        Button(
                            onClick = { onAction(next) },
                            modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                        ) {
                            Text(actionLabels.getValue(next), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    OutlinedButton(onClick = onSryv, modifier = Modifier.fillMaxWidth().height(ButtonHeight)) {
                        Text("Ездка сорвана", style = MaterialTheme.typography.labelLarge)
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
