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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.ui.components.CallDispatcherButton
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
    // тогда есть куда возвращаться.
    onBack: (() -> Unit)? = null,
    viewModel: AssignmentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var confirmEndShift by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Scaffold(
        topBar = {
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
                    (uiState as? AssignmentUiState.Content)?.let { StatusBadge(it.state) }
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
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                AppHeader()
                AssignmentContent(
                    content = state,
                    onAcknowledge = viewModel::onAcknowledge,
                    onStartShift = viewModel::onStartShift,
                    onEndShift = { confirmEndShift = true }
                )
            }
        }
    }

    if (confirmEndShift) {
        val label = (uiState as? AssignmentUiState.Content)?.state?.label
        AlertDialog(
            onDismissRequest = { confirmEndShift = false },
            title = { Text("Закончить смену?") },
            text = { Text("Смена по разнарядке № $label будет закончена. Отменить это нельзя.") },
            confirmButton = {
                Button(onClick = {
                    confirmEndShift = false
                    viewModel.onEndShift()
                }) { Text("Закончить смену") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmEndShift = false }) { Text("Отмена") }
            }
        )
    }
}

// Зелёный — смена идёт, красный — всё остальное. Цвет не единственный
// признак — рядом всегда текст.
@Composable
private fun StatusBadge(state: AssignmentState) {
    val active = state.phase == AssignmentPhase.IN_SHIFT && !state.cancelledByDispatcher
    val color = if (active) statusActiveColor() else MaterialTheme.colorScheme.error
    val container = if (active) statusActiveContainer() else MaterialTheme.colorScheme.errorContainer
    Surface(shape = RoundedCornerShape(50), color = container, modifier = Modifier.padding(end = 16.dp)) {
        Text(
            text = statusLabel(state),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

fun statusLabel(state: AssignmentState): String = when (state.phase) {
    AssignmentPhase.NEW -> if (state.needsReacknowledge) "изменена" else "не принята"
    AssignmentPhase.ACCEPTED -> "принята"
    AssignmentPhase.IN_SHIFT -> if (state.cancelledByDispatcher) "отменена диспетчером" else "смена идёт"
    AssignmentPhase.FINISHED -> if (state.endedByDispatcher) "рейсы сняты диспетчером" else "смена завершена"
    AssignmentPhase.CANCELLED -> "отменена диспетчером"
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
    content: AssignmentUiState.Content,
    onAcknowledge: () -> Unit,
    onStartShift: () -> Unit,
    onEndShift: () -> Unit
) {
    val state = content.state
    val assignment = state.assignment

    Text(text = "Разнарядка № ${state.label}", style = MaterialTheme.typography.headlineSmall)

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        LabeledField(label = "Дата выезда", value = assignment.departureDay)
        LabeledField(label = "Водитель", value = assignment.driver.name ?: assignment.driver.id)
        LabeledField(
            label = "Машина",
            value = "${assignment.vehicle.name ?: ""} ${assignment.vehicle.plate ?: ""}".trim()
        )
        LabeledField(label = "Рейсов на смену", value = state.trips.size.toString())
    }

    state.updatedAt?.let { updatedAt ->
        Text(text = "Обновлено в ${formatUpdatedAt(updatedAt)}", style = MaterialTheme.typography.bodySmall)
    }

    when (state.phase) {
        AssignmentPhase.CANCELLED -> {
            Notice("Разнарядка отменена диспетчером.${reasonSuffix(state)}")
            CallDispatcherButton()
        }

        AssignmentPhase.NEW, AssignmentPhase.ACCEPTED -> {
            if (state.needsReacknowledge) {
                Notice("Диспетчер изменил разнарядку. Проверьте рейсы и подтвердите заново.")
            }
            Button(
                onClick = onAcknowledge,
                enabled = state.phase == AssignmentPhase.NEW,
                modifier = Modifier.fillMaxWidth().height(ButtonHeight)
            ) {
                // Тип события в контракте с 1С — `Ознакомление`.
                Text(
                    if (state.phase == AssignmentPhase.NEW) "Ознакомился и принял" else "Разнарядка принята",
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Button(
                onClick = onStartShift,
                enabled = state.phase == AssignmentPhase.ACCEPTED && content.otherOpenShiftLabel == null,
                modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Начать смену", style = MaterialTheme.typography.labelLarge)
            }
            content.otherOpenShiftLabel?.let { other ->
                Text(
                    text = "Сначала закончите смену по разнарядке № $other.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            CallDispatcherButton()
        }

        AssignmentPhase.IN_SHIFT -> {
            if (state.cancelledByDispatcher) {
                Notice("Разнарядка отменена диспетчером.${reasonSuffix(state)} Закончите смену.")
            } else {
                Text(
                    text = tripsProgressLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (state.canEndShift) {
                Button(
                    onClick = onEndShift,
                    modifier = Modifier.fillMaxWidth().height(ButtonHeight)
                ) {
                    Text("Закончить смену", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                Text(
                    text = "Рейсы — во вкладке «Мои рейсы». «Закончить смену» появится, когда все рейсы будут закрыты.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            CallDispatcherButton()
        }

        AssignmentPhase.FINISHED -> Text(
            text = if (state.endedByDispatcher) {
                "Диспетчер снял оставшиеся рейсы, смена по этой разнарядке закрыта."
            } else {
                "Смена завершена. ${tripsProgressLabel(state)}"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.error
    )
}

private fun reasonSuffix(state: AssignmentState): String =
    state.assignment.cancelReason?.takeIf { it.isNotBlank() }?.let { " Причина: $it." } ?: ""

private fun formatUpdatedAt(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale("ru")).format(Date(millis))

// Дробь вместо фраз «первый закончен»/«ожидание следующего» — верна при
// любом числе рейсов. Снятые диспетчером считаются закрытыми.
fun tripsProgressLabel(state: AssignmentState): String = when {
    state.trips.isEmpty() -> "Рейсов нет"
    state.tripsResolved >= state.trips.size -> "Все рейсы закрыты"
    else -> "Рейсы: ${state.tripsResolved} из ${state.trips.size} закрыто"
}
