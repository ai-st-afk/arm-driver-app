package ru.profstroyservices.armdriver.ui.assignment

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssignmentScreen(
    // Задан только когда экран открыт из списка разнарядок (их несколько) —
    // тогда есть куда возвращаться. При единственной разнарядке список
    // пропускается насквозь (см. AssignmentsGate), стрелка назад тут вела
    // бы в никуда, поэтому её не показываем.
    onBack: (() -> Unit)? = null,
    viewModel: AssignmentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCancelDialog by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = {
            if (onBack != null) {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к списку разнарядок")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is AssignmentUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is AssignmentUiState.Error -> Text(
                text = state.message,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(innerPadding).padding(20.dp)
            )

            is AssignmentUiState.Content -> Column(
                // Баннер неотправленного сверху съедает высоту экрана — без
                // скролла низ (кнопки смены) обрезался и не долистывался.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppHeader()
                AssignmentContent(
                    state = state,
                    onAcknowledge = viewModel::onAcknowledge,
                    onStartShift = viewModel::onStartShift,
                    onEndShift = viewModel::onEndShift,
                    onRequestCancel = { showCancelDialog = true }
                )
            }
        }
    }

    if (showCancelDialog) {
        CancelAssignmentDialog(
            onConfirm = { reason ->
                viewModel.onCancelAssignment(reason)
                showCancelDialog = false
            },
            onDismiss = { showCancelDialog = false }
        )
    }
}

// Отказ от разнарядки самим водителем, до начала смены — не путать со
// «Срыв» на экране рейсов (тот про конкретный рейс, после начала смены).
// Причина обязательна: она уйдёт в 1С комментарием к событию.
@Composable
private fun CancelAssignmentDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Причина отказа") },
        text = {
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it },
                label = { Text("Комментарий (обязательно)") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) {
                Text("Подтвердить")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
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
    onEndShift: () -> Unit,
    onRequestCancel: () -> Unit
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
        LabeledField(label = "Рейсов на смену", value = assignment.trips.size.toString())
        LabeledField(label = "Статус смены", value = shiftStatusLabel(state))
    }

    state.updatedAt?.let { updatedAt ->
        Text(
            text = "Обновлено в ${formatUpdatedAt(updatedAt)}",
            style = MaterialTheme.typography.bodySmall
        )
    }

    // Отказался сам — дальше по этой разнарядке делать нечего: Ознакомлен/
    // Начать смену теряют смысл, показываем только статус.
    if (state.cancelledByDriver) {
        Text(
            text = "Вы отказались от этой разнарядки.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        return
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
                text = "Рейсы — во вкладке «Мои рейсы».",
                style = MaterialTheme.typography.bodySmall
            )
        }
    } else {
        // Доступно только до начала смены — после НачалоСмены отказ от
        // задания уже не имеет смысла, там свои шаги (Срыв по рейсу).
        // Визуально слабее основных кнопок — редкое и тяжёлое действие.
        TextButton(
            onClick = onRequestCancel,
            modifier = Modifier.fillMaxWidth().height(ButtonHeight)
        ) {
            Text("Отказаться от разнарядки", color = MaterialTheme.colorScheme.error)
        }
    }
}

private fun formatUpdatedAt(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale("ru")).format(Date(millis))

private fun shiftStatusLabel(state: AssignmentUiState.Content): String = when {
    state.cancelledByDriver -> "отказался от разнарядки"
    state.shiftEnded -> "смена завершена"
    state.shiftStarted -> "смена идёт"
    state.acknowledged -> "ознакомлен"
    else -> "не ознакомлен"
}
