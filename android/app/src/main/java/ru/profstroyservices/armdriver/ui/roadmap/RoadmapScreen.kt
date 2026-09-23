package ru.profstroyservices.armdriver.ui.roadmap

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.AssignmentState
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.TripProgress
import ru.profstroyservices.armdriver.ui.components.CallDispatcherButton
import ru.profstroyservices.armdriver.ui.components.LabeledField
import ru.profstroyservices.armdriver.ui.components.cargoValue
import ru.profstroyservices.armdriver.ui.components.firstNotBlank
import ru.profstroyservices.armdriver.ui.components.formatTime
import ru.profstroyservices.armdriver.ui.components.pointLabel
import ru.profstroyservices.armdriver.ui.components.pointShortLabel
import ru.profstroyservices.armdriver.ui.theme.statusActiveColor
import ru.profstroyservices.armdriver.ui.theme.statusActiveContainer
import java.io.File

// Основное действие крупнее обычной кнопки: по нему бьют в перчатках и в
// тряске, промах здесь стоит лишнего события в 1С.
private val PrimaryActionHeight = 64.dp
private val SecondaryActionHeight = 56.dp
private val CompletedGreen = Color(0xFF2E7D32)

// Совпадает с UNDO_WINDOW_MILLIS во ViewModel: пока снекбар виден, отмена
// ещё возможна, событие ещё не отправлено.
private const val FeedbackAutoDismissMillis = 5_000L

private val actionLabels = mapOf(
    EventTypes.PRIBYL_NA_POGRUZKU to "Прибыл на погрузку",
    EventTypes.ZAGRUZILSYA_V_PUT to "Загрузился, в пути",
    EventTypes.PRIBYL_NA_RAZGRUZKU to "Прибыл на разгрузку",
    EventTypes.RAZGRUZILSYA to "Разгрузился (фото документа)"
)

// Во вкладке «Мои рейсы» всегда только текущая разнарядка (см.
// AssignmentsGate), поэтому стрелки назад тут нет.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadmapScreen(viewModel: RoadmapViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    var expandedTripId by remember { mutableStateOf<String?>(null) }
    var confirmEndShift by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshFromNetwork() }

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // Камера пишет фото в файл, подготовленный заранее (prepareCapture) —
    // это гарантирует "только с камеры", не из галереи.
    var captureTarget by remember { mutableStateOf<Pair<TripDto, File>?>(null) }
    var noPhotoTarget by remember { mutableStateOf<TripDto?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = captureTarget
        captureTarget = null
        if (success && target != null) {
            viewModel.onPhotoCaptured(target.first, target.second)
        } else if (target != null) {
            noPhotoTarget = target.first
        }
    }

    val content = (uiState as? RoadmapUiState.Content)?.state
    val activeTripId = content?.activeTrip?.trip?.id
    // Активный рейс раскрыт сам: закрыл предыдущий — следующий открылся.
    LaunchedEffect(activeTripId) { expandedTripId = activeTripId }

    LaunchedEffect(Unit) {
        viewModel.feedback.collect { feedback ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // showSnackbar с actionLabel по умолчанию держит уведомление
            // бесконечно — а нужно ровно столько, сколько окно отмены.
            val autoDismiss = launch {
                delay(FeedbackAutoDismissMillis)
                snackbarHostState.currentSnackbarData?.dismiss()
            }
            val result = snackbarHostState.showSnackbar(
                message = feedback.text,
                actionLabel = feedback.undoEventId?.let { "Отменить" },
                withDismissAction = false,
                duration = SnackbarDuration.Indefinite
            )
            autoDismiss.cancel()
            if (result == SnackbarResult.ActionPerformed) {
                feedback.undoEventId?.let(viewModel::onUndo)
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(content?.let { "Разнарядка № ${it.label}" } ?: "") }) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
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

                is RoadmapUiState.Content -> RoadmapList(
                    state = state.state,
                    expandedTripId = expandedTripId,
                    onToggle = { tripId -> expandedTripId = if (expandedTripId == tripId) null else tripId },
                    onAction = { trip, type ->
                        if (type == EventTypes.RAZGRUZILSYA) {
                            val (file, uri) = viewModel.prepareCapture()
                            captureTarget = trip to file
                            cameraLauncher.launch(uri)
                        } else {
                            viewModel.onTripAction(trip, type)
                        }
                    },
                    onStepBack = viewModel::onStepBack,
                    onEndShift = { confirmEndShift = true }
                )
            }
        }
    }

    if (confirmEndShift) {
        AlertDialog(
            onDismissRequest = { confirmEndShift = false },
            title = { Text("Закончить смену?") },
            text = { Text("Смена по разнарядке № ${content?.label} будет закончена. Отменить это нельзя.") },
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

    noPhotoTarget?.let { trip ->
        NoPhotoDialog(
            onRetryPhoto = {
                noPhotoTarget = null
                val (file, uri) = viewModel.prepareCapture()
                captureTarget = trip to file
                cameraLauncher.launch(uri)
            },
            onConfirmWithoutPhoto = { reason ->
                viewModel.onUnloadWithoutPhoto(trip, reason)
                noPhotoTarget = null
            },
            onDismiss = { noPhotoTarget = null }
        )
    }
}

@Composable
private fun RoadmapList(
    state: AssignmentState,
    expandedTripId: String?,
    onToggle: (String) -> Unit,
    onAction: (TripDto, String) -> Unit,
    onStepBack: (TripDto) -> Unit,
    onEndShift: () -> Unit
) {
    val banner = when {
        state.cancelledByDispatcher && state.phase == AssignmentPhase.IN_SHIFT ->
            "Разнарядка отменена диспетчером. Закончите смену."
        state.cancelledByDispatcher -> "Разнарядка отменена диспетчером."
        state.endedByDispatcher -> "Диспетчер снял оставшиеся рейсы, смена по этой разнарядке закрыта."
        state.phase == AssignmentPhase.FINISHED -> "Смена по этой разнарядке закончена."
        state.phase != AssignmentPhase.IN_SHIFT ->
            "Смена не начата. Отметить рейс можно после «Начать смену» на вкладке «Разнарядка»."
        else -> null
    }
    val lockedHint = when {
        state.canMarkTrips -> null
        state.phase == AssignmentPhase.FINISHED -> "Смена закончена — отмечать этапы больше нельзя."
        state.cancelledByDispatcher -> "Разнарядка отменена — отмечать этапы нельзя."
        else -> "Начните смену, чтобы отмечать этапы этого рейса."
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        banner?.let { text ->
            item(key = "banner") {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
        items(state.trips, key = { it.trip.id }) { progress ->
            TripRow(
                state = progress,
                isActive = progress.trip.id == state.activeTrip?.trip?.id,
                lockedHint = lockedHint,
                expanded = progress.trip.id == expandedTripId,
                onToggle = { onToggle(progress.trip.id) },
                onAction = { type -> onAction(progress.trip, type) },
                onStepBack = { onStepBack(progress.trip) }
            )
        }
        // Смену заканчивают руками, когда работы не осталось: все рейсы
        // выполнены или сняты диспетчером (конец смены — отдельный факт от
        // последней разгрузки, AGENTS.md).
        if (state.canEndShift) {
            item(key = "end_shift") {
                Button(
                    onClick = onEndShift,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(PrimaryActionHeight)
                ) {
                    Text("Закончить смену", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        if (state.phase != AssignmentPhase.FINISHED) {
            item(key = "dispatcher") {
                CallDispatcherButton(text = "Проблема с рейсом — позвонить диспетчеру")
            }
        }
    }
}

// Фото не получилось. Закрыть рейс всё равно надо — иначе встаёт смена.
// Причина обязательна: она уйдёт в 1С комментарием, чтобы отсутствие
// накладной было объяснено.
@Composable
private fun NoPhotoDialog(
    onRetryPhoto: () -> Unit,
    onConfirmWithoutPhoto: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var reason by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Фото не сделано") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Можно переснять или закрыть разгрузку без фото, указав причину.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Причина (обязательно)") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = onRetryPhoto) { Text("Переснять") }
        },
        dismissButton = {
            TextButton(onClick = { onConfirmWithoutPhoto(reason) }, enabled = reason.isNotBlank()) {
                Text("Без фото")
            }
        }
    )
}

@Composable
private fun TripRow(
    state: TripProgress,
    isActive: Boolean,
    // Не null — отмечать этапы сейчас нельзя (смена не начата, закончена
    // или разнарядка отменена).
    lockedHint: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (String) -> Unit,
    onStepBack: () -> Unit
) {
    val activeColor = statusActiveColor()

    Card(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) statusActiveContainer() else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(
            width = if (isActive) 2.dp else 1.dp,
            color = if (isActive) activeColor else MaterialTheme.colorScheme.outline
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CollapsedHeader(state = state, isActive = isActive)

            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                ExpandedDetails(state = state, lockedHint = lockedHint, onAction = onAction, onStepBack = onStepBack)
            }
        }
    }
}

@Composable
private fun CollapsedHeader(state: TripProgress, isActive: Boolean) {
    val trip = state.trip
    val activeColor = statusActiveColor()
    val failed = state.isCancelled || state.isFailed

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Точка маршрута: выполненный — галочка, снятый — крестик, активный —
        // номер на зелёном, впереди — номер на сером. Рядом всегда статус словом.
        Surface(
            shape = CircleShape,
            color = when {
                state.isCompleted || isActive -> activeColor
                failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
            contentColor = if (state.isCompleted || failed || isActive) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                when {
                    state.isCompleted -> Icon(Icons.Filled.Check, contentDescription = "Выполнен")
                    failed -> Icon(Icons.Filled.Close, contentDescription = "Снят")
                    else -> Text(text = trip.order.toString(), style = MaterialTheme.typography.titleMedium)
                }
            }
        }

        Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
            Text(
                text = "${pointShortLabel(trip.loadPoint)} → ${pointShortLabel(trip.unloadPoint)}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tripStatusLabel(state, isActive),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tripStatusColor(state, isActive, activeColor)
                )
                planTimeLabel(trip)?.let { plan ->
                    Text(text = " · $plan", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ExpandedDetails(
    state: TripProgress,
    lockedHint: String?,
    onAction: (String) -> Unit,
    onStepBack: () -> Unit
) {
    val trip = state.trip

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledField(label = "Погрузка", value = pointLabel(trip.loadPoint), onClick = navigateAction(trip.loadPoint))
        LabeledField(label = "Разгрузка", value = pointLabel(trip.unloadPoint), onClick = navigateAction(trip.unloadPoint))
        trip.customer?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Заказчик", value = it) }
        trip.trailer.plate?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Прицеп", value = it) }
        trip.cargo.composition?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Груз", value = it) }
        cargoValue(trip.cargo.quantity, trip.cargo.unit)?.let { LabeledField(label = "Количество", value = it) }
        cargoValue(trip.cargo.weight, "т")?.let { LabeledField(label = "Вес", value = it) }
        cargoValue(trip.cargo.volume, "м³")?.let { LabeledField(label = "Объём", value = it) }
    }

    when {
        // Снят диспетчером — даже если по нему уже были отметки (так 1С
        // снимает незавершённые рейсы при замене экипажа).
        state.isCancelled -> {
            Text(text = "Рейс снят диспетчером", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            return
        }
        state.isCompleted -> {
            Text(text = "Рейс выполнен", color = CompletedGreen, fontWeight = FontWeight.SemiBold)
            return
        }
        state.isFailed -> {
            Text(text = "Рейс сорван", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            return
        }
    }

    if (lockedHint != null) {
        Text(text = lockedHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    state.nextAction?.let { next ->
        Button(
            onClick = { onAction(next) },
            modifier = Modifier.fillMaxWidth().height(PrimaryActionHeight),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text(actionLabels.getValue(next), style = MaterialTheme.typography.labelLarge)
        }
    }

    // Случайный лишний тап мог продвинуть рейс дальше, чем нужно. «Отмена»
    // откатывает последний шаг (пока он не ушёл в 1С) и остаётся в
    // «Истории» с пометкой, а не пропадает бесследно.
    if (state.doneTypes.isNotEmpty()) {
        OutlinedButton(onClick = onStepBack, modifier = Modifier.fillMaxWidth().height(SecondaryActionHeight)) {
            Text("Отмена", style = MaterialTheme.typography.labelLarge)
        }
    }
}

// Общий geo: intent без привязки к приложению — в контракте с 1С только
// текстовый адрес, а geo:...?q= понимают и Яндекс.Карты, и 2ГИС, и Google
// Maps. Если навигаторов несколько, Android сам покажет выбор.
@Composable
private fun navigateAction(point: PointDto): (() -> Unit)? {
    val context = LocalContext.current
    val address = firstNotBlank(point.address, point.name) ?: return null
    return {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}"))
        runCatching { context.startActivity(intent) }
    }
}

private fun planTimeLabel(trip: TripDto): String? {
    val load = formatTime(trip.planLoad)
    val unload = formatTime(trip.planUnload)
    return when {
        load != null && unload != null -> "$load — $unload"
        load != null -> load
        unload != null -> unload
        else -> null
    }
}

private fun tripStatusLabel(state: TripProgress, isActive: Boolean): String = when {
    state.isCancelled -> "снят"
    state.isCompleted -> "выполнен"
    state.isFailed -> "сорван"
    isActive -> "сейчас"
    else -> "ожидает"
}

@Composable
private fun tripStatusColor(state: TripProgress, isActive: Boolean, activeColor: Color): Color = when {
    state.isCancelled || state.isFailed -> MaterialTheme.colorScheme.error
    state.isCompleted -> CompletedGreen
    isActive -> activeColor
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
