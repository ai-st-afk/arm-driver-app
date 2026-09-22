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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.profstroyservices.armdriver.R
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.ui.components.LabeledField
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// По этим кнопкам бьют в перчатках и в тряске — они крупнее обычных.
private val ButtonHeight = 64.dp

@Composable
fun AssignmentScreen(viewModel: AssignmentViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

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
                    onStartShift = viewModel::onStartShift,
                    onEndShift = viewModel::onEndShift
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
    onEndShift: () -> Unit
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
        LabeledField(label = "Ездок на смену", value = assignment.trips.size.toString())
        LabeledField(label = "Статус смены", value = shiftStatusLabel(state))
    }

    state.updatedAt?.let { updatedAt ->
        Text(
            text = "Обновлено в ${formatUpdatedAt(updatedAt)}",
            style = MaterialTheme.typography.bodySmall
        )
    }

    Button(
        onClick = onAcknowledge,
        enabled = !state.acknowledged,
        modifier = Modifier.fillMaxWidth().height(ButtonHeight)
    ) {
        // Подпись — «принял», потому что для диспетчера это и есть приём
        // разнарядки. Тип события в контракте с 1С остаётся `Ознакомление`.
        Text(
            if (state.acknowledged) "Разнарядка принята" else "Ознакомился и принял",
            style = MaterialTheme.typography.labelLarge
        )
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
            onClick = onEndShift,
            enabled = !state.shiftEnded,
            modifier = Modifier.fillMaxWidth().height(ButtonHeight)
        ) {
            Text(
                if (state.shiftEnded) "Смена завершена" else "Закончить смену",
                style = MaterialTheme.typography.labelLarge
            )
        }

        if (!state.shiftEnded) {
            Text(
                text = "Ездки — во вкладке «Мои ездки».",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun formatUpdatedAt(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale("ru")).format(Date(millis))

private fun shiftStatusLabel(state: AssignmentUiState.Content): String = when {
    state.shiftEnded -> "смена завершена"
    state.shiftStarted -> "смена идёт"
    state.acknowledged -> "ознакомлен"
    else -> "не ознакомлен"
}
