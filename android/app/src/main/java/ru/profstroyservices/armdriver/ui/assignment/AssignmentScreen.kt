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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.profstroyservices.armdriver.R
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.ui.components.LabeledField
import ru.profstroyservices.armdriver.ui.theme.statusActiveColor
import ru.profstroyservices.armdriver.ui.theme.statusActiveContainer
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
            // Шапка видна всегда — короткий цветной статус смены в углу
            // заменяет собой строку «Статус смены» в теле экрана, которая
            // не менялась синхронно с прогрессом рейсов и вводила в
            // заблуждение (см. DEVLOG).
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к списку разнарядок")
                        }
                    }
                },
                actions = {
                    (uiState as? AssignmentUiState.Content)?.let { content ->
                        ShiftStatusBadge(content)
                    }
                }
            )
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

// Зелёный — смена идёт, красным — любое другое состояние (не начата,
// завершена, отказ). Цвет не единственный признак — рядом всегда текст.
@Composable
private fun ShiftStatusBadge(state: AssignmentUiState.Content) {
    val active = state.shiftStarted && !state.shiftEnded
    val color = if (active) statusActiveColor() else MaterialTheme.colorScheme.error
    val container = if (active) statusActiveContainer() else MaterialTheme.colorScheme.errorContainer
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        modifier = Modifier.padding(end = 16.dp)
    ) {
        Text(
            text = shiftStatusLabel(state),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
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
        // «Закончить смену» теперь не здесь — она общая на все вкладки
        // (см. ActiveShiftBar в MainScaffold), не привязана к тому, какую
        // разнарядку водитель сейчас смотрит.
        Text(
            text = tripsProgressLabel(state),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Рейсы — во вкладке «Мои рейсы».",
            style = MaterialTheme.typography.bodySmall
        )
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

// Разнарядка не шлёт отдельного статуса завершения — считаем сами по
// рейсам (Разгрузился/Срыв/снята диспетчером = закрыт). Дробь вместо
// отдельных фраз «первый закончен»/«ожидание следующего» — верно при любом
// числе рейсов, не только при двух.
private fun tripsProgressLabel(state: AssignmentUiState.Content): String = when {
    state.tripsTotal == 0 -> "Рейсов нет"
    state.tripsCompleted >= state.tripsTotal -> "Все рейсы завершены"
    else -> "Рейсы: ${state.tripsCompleted} из ${state.tripsTotal} выполнено"
}
