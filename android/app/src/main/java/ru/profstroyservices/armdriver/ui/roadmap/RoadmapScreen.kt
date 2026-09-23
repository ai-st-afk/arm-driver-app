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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.EventTypes
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadmapScreen(
    // См. AssignmentScreen — задан только когда экран открыт из списка-пикера
    // (разнарядок несколько), иначе показывать нечего вести назад.
    onBack: (() -> Unit)? = null,
    viewModel: RoadmapViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var sryvTarget by remember { mutableStateOf<TripDto?>(null) }
    var expandedTripId by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshFromNetwork() }

    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    // Камера пишет фото в файл, подготовленный заранее (prepareCapture) —
    // это гарантирует "только с камеры", не из галереи: TakePicture ничего
    // не выбирает, только снимает и сохраняет по готовому Uri.
    var captureTarget by remember { mutableStateOf<Pair<TripDto, File>?>(null) }
    var noPhotoTarget by remember { mutableStateOf<TripDto?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val target = captureTarget
        captureTarget = null
        if (success && target != null) {
            viewModel.onPhotoCaptured(target.first, target.second)
        } else if (target != null) {
            // Раньше неудачная съёмка проходила молча: водитель жал кнопку,
            // ничего не происходило, и он не понимал, закрылся рейс или нет.
            noPhotoTarget = target.first
        }
    }

    val activeTripId = (uiState as? RoadmapUiState.Content)?.activeTripId
    // Активный рейс раскрыт сам: закрыл предыдущую — следующая открылась.
    LaunchedEffect(activeTripId) { expandedTripId = activeTripId }

    LaunchedEffect(Unit) {
        viewModel.feedback.collect { feedback ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            // showSnackbar с actionLabel по умолчанию держит уведомление
            // бесконечно (SnackbarDuration.Indefinite) — а тут нужно ровно
            // столько же, сколько у окна отмены во ViewModel, дальше событие
            // уже уходит в 1С и отменять поздно.
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
        topBar = {
            // Шапка видна всегда (не только когда есть куда возвращаться) —
            // водитель должен видеть, по какой разнарядке эти рейсы, даже
            // если она единственная и список-пикер был пропущен насквозь.
            val assignmentNumber = (uiState as? RoadmapUiState.Content)?.assignmentNumber
            TopAppBar(
                title = { Text(assignmentNumber?.let { "Разнарядка № $it" } ?: "") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад к списку разнарядок")
                        }
                    }
                }
            )
        },
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

                is RoadmapUiState.Content -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!state.shiftStarted) {
                        item {
                            Text(
                                text = "Смена не начата. Отметить рейс можно после «Начать смену» на вкладке «Разнарядка».",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    items(state.trips, key = { it.trip.id }) { tripState ->
                        TripRow(
                            state = tripState,
                            isActive = tripState.trip.id == state.activeTripId,
                            shiftStarted = state.shiftStarted,
                            expanded = tripState.trip.id == expandedTripId,
                            onToggle = {
                                expandedTripId = if (expandedTripId == tripState.trip.id) {
                                    null
                                } else {
                                    tripState.trip.id
                                }
                            },
                            onAction = { type ->
                                if (type == EventTypes.RAZGRUZILSYA) {
                                    val (file, uri) = viewModel.prepareCapture()
                                    captureTarget = tripState.trip to file
                                    cameraLauncher.launch(uri)
                                } else {
                                    viewModel.onTripAction(tripState.trip, type)
                                }
                            },
                            onSryv = { sryvTarget = tripState.trip },
                            onStepBack = { viewModel.onStepBack(tripState.trip) }
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

// Фото не получилось. Закрыть рейс всё равно надо — иначе встаёт смена,
// а диспетчер не сможет закрыть разнарядку. Причина обязательна: она уйдёт
// в 1С комментарием, чтобы отсутствие накладной было объяснено.
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
                    text = "Можно переснять или закрыть разгрузку без фото, " +
                        "указав причину.",
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
            TextButton(
                onClick = { onConfirmWithoutPhoto(reason) },
                enabled = reason.isNotBlank()
            ) {
                Text("Без фото")
            }
        }
    )
}

@Composable
private fun TripRow(
    state: TripUiState,
    isActive: Boolean,
    shiftStarted: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAction: (String) -> Unit,
    onSryv: () -> Unit,
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
                ExpandedDetails(
                    state = state,
                    shiftStarted = shiftStarted,
                    onAction = onAction,
                    onSryv = onSryv,
                    onStepBack = onStepBack
                )
            }
        }
    }
}

@Composable
private fun CollapsedHeader(state: TripUiState, isActive: Boolean) {
    val trip = state.trip
    val activeColor = statusActiveColor()

    Row(verticalAlignment = Alignment.CenterVertically) {
        // Точка маршрута: номер по <Порядок>, не идентификатор (инвариант 3).
        Surface(
            shape = CircleShape,
            color = if (isActive) activeColor else MaterialTheme.colorScheme.outline,
            contentColor = if (isActive) Color.White else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(text = trip.order.toString(), style = MaterialTheme.typography.titleMedium)
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
                    Text(
                        text = " · $plan",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpandedDetails(
    state: TripUiState,
    shiftStarted: Boolean,
    onAction: (String) -> Unit,
    onSryv: () -> Unit,
    onStepBack: () -> Unit
) {
    val trip = state.trip

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabeledField(
            label = "Погрузка",
            value = pointLabel(trip.loadPoint),
            onClick = navigateAction(trip.loadPoint)
        )
        LabeledField(
            label = "Разгрузка",
            value = pointLabel(trip.unloadPoint),
            onClick = navigateAction(trip.unloadPoint)
        )
        trip.customer?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Заказчик", value = it) }
        trip.trailer.plate?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Прицеп", value = it) }
        trip.cargo.composition?.takeIf { it.isNotBlank() }?.let { LabeledField(label = "Груз", value = it) }
        cargoValue(trip.cargo.quantity, trip.cargo.unit)?.let { LabeledField(label = "Количество", value = it) }
        cargoValue(trip.cargo.weight, "т")?.let { LabeledField(label = "Вес", value = it) }
        cargoValue(trip.cargo.volume, "м³")?.let { LabeledField(label = "Объём", value = it) }
    }

    if (state.isCancelled) return

    if (state.doneTypes.contains(EventTypes.RAZGRUZILSYA)) {
        Text(text = "Рейс завершён", color = CompletedGreen, fontWeight = FontWeight.SemiBold)
        return
    }
    if (state.doneTypes.contains(EventTypes.SRYV)) {
        Text(
            text = "Рейс сорван",
            color = MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.SemiBold
        )
        return
    }

    if (!shiftStarted) {
        Text(
            text = "Начните смену, чтобы отмечать этапы этого рейса.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // Если шаг всё же успел записаться до начала смены (например, с
        // версии до этого фикса) — дать его откатить, а не оставлять
        // висеть без возможности исправить.
        if (state.doneTypes.isNotEmpty()) {
            OutlinedButton(
                onClick = onStepBack,
                modifier = Modifier.fillMaxWidth().height(SecondaryActionHeight)
            ) {
                Text("Отмена", style = MaterialTheme.typography.labelLarge)
            }
        }
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

    // Есть хотя бы один пройденный шаг цикла (RAZGRUZILSYA/SRYV сюда не
    // доходят — при них выше уже return) — значит случайный повторный тап
    // мог продвинуть рейс дальше, чем нужно. «Отмена» откатывает последний
    // шаг и остаётся в «Истории» с пометкой, а не пропадает бесследно.
    if (state.doneTypes.isNotEmpty()) {
        OutlinedButton(
            onClick = onStepBack,
            modifier = Modifier.fillMaxWidth().height(SecondaryActionHeight)
        ) {
            Text("Отмена", style = MaterialTheme.typography.labelLarge)
        }
    }

    // Срыв — редкое и тяжёлое действие, поэтому визуально слабее основного,
    // чтобы в него не попадали случайно.
    TextButton(
        onClick = onSryv,
        modifier = Modifier.fillMaxWidth().height(SecondaryActionHeight)
    ) {
        Text("Рейс сорван", color = MaterialTheme.colorScheme.error)
    }
}

// Открываем адрес в Яндекс.Картах явным intent'ом на их пакет — в контракте
// с 1С координат точек нет, только текстовый адрес, а по тексту Яндекс.
// Навигатор маршрут не строит (нужны координаты), в отличие от Яндекс.Карт,
// которые умеют поиск по тексту. Если Яндекса нет — общий geo: intent,
// его понимают 2ГИС и Google Maps, откроется что установлено у водителя.
@Composable
private fun navigateAction(point: PointDto): (() -> Unit)? {
    val context = LocalContext.current
    val address = firstNotBlank(point.address, point.name) ?: return null
    return {
        val encoded = Uri.encode(address)
        val yandex = Intent(Intent.ACTION_VIEW, Uri.parse("yandexmaps://maps.yandex.ru/?text=$encoded")).apply {
            setPackage("ru.yandex.yandexmaps")
        }
        val openedYandex = runCatching { context.startActivity(yandex) }.isSuccess
        if (!openedYandex) {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$encoded"))
            runCatching { context.startActivity(fallback) }
        }
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

private fun tripStatusLabel(state: TripUiState, isActive: Boolean): String = when {
    state.isCancelled -> "снято"
    state.doneTypes.contains(EventTypes.RAZGRUZILSYA) -> "завершена"
    state.doneTypes.contains(EventTypes.SRYV) -> "сорвана"
    isActive -> "сейчас"
    else -> "ожидает"
}

@Composable
private fun tripStatusColor(state: TripUiState, isActive: Boolean, activeColor: Color): Color = when {
    state.isCancelled || state.doneTypes.contains(EventTypes.SRYV) -> MaterialTheme.colorScheme.error
    state.doneTypes.contains(EventTypes.RAZGRUZILSYA) -> CompletedGreen
    isActive -> activeColor
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
