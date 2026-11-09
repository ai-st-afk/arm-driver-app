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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import ru.profstroyservices.armdriver.data.repository.DRIVER_TIME_ZONE
import ru.profstroyservices.armdriver.ui.components.CallDispatcherButton
import ru.profstroyservices.armdriver.ui.components.CargoDetails
import ru.profstroyservices.armdriver.ui.components.LabeledField
import ru.profstroyservices.armdriver.ui.components.TripContacts
import ru.profstroyservices.armdriver.ui.components.formatTime
import ru.profstroyservices.armdriver.ui.components.pointLabel
import ru.profstroyservices.armdriver.ui.components.pointShortLabel
import ru.profstroyservices.armdriver.ui.theme.statusActiveColor
import ru.profstroyservices.armdriver.ui.theme.statusActiveContainer
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Duration
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

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
    val today by produceState(initialValue = LocalDate.now(DRIVER_TIME_ZONE)) {
        while (true) {
            val now = ZonedDateTime.now(DRIVER_TIME_ZONE)
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay(DRIVER_TIME_ZONE)
            delay(Duration.between(now, midnight).toMillis().coerceAtLeast(1_000L))
            value = LocalDate.now(DRIVER_TIME_ZONE)
        }
    }

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
                    today = today,
                    onAcknowledge = viewModel::onAcknowledge,
                    onStartShift = viewModel::onStartShift
                )
            }
        }
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
    AssignmentPhase.REPLACED -> "заменена"
    AssignmentPhase.FINISHED -> if (state.assignment.shiftClosed) "смена закрыта" else "сдача смены"
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
    today: LocalDate,
    onAcknowledge: () -> Unit,
    onStartShift: () -> Unit
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
        if (state.phase != AssignmentPhase.NEW && state.phase != AssignmentPhase.ACCEPTED) {
            state.trips.forEach { progress ->
                progress.trip.trailer.plate?.takeIf { it.isNotBlank() }?.let { plate ->
                    LabeledField(
                        label = "Прицеп · рейс ${progress.trip.order}" + if (progress.isCancelled) " (снят)" else "",
                        value = plate
                    )
                }
            }
        }
    }

    if (isFutureAssignment(assignment.departureDay, today)) {
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = if (isTomorrowAssignment(assignment.departureDay, today)) {
                        "Разнарядка на завтра. Это не текущая смена."
                    } else {
                        "Будущая разнарядка. Это не текущая смена."
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Сначала посмотрите все ездки ниже, затем нажмите «Ознакомился и принял».",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }

    if (state.phase == AssignmentPhase.NEW || state.phase == AssignmentPhase.ACCEPTED) {
        UpcomingTrips(state)
    }

    Text("Итого за смену", style = MaterialTheme.typography.titleMedium)
    val shiftTotal = assignment.shiftTotal
    if (shiftTotal == null) {
        Text("Итог за смену ещё не передан", style = MaterialTheme.typography.bodyMedium)
    } else {
        CargoDetails(shiftTotal.positions, shiftTotal.weight, shiftTotal.volume)
    }

    state.updatedAt?.let { updatedAt ->
        Text(text = "Обновлено в ${formatUpdatedAt(updatedAt)}", style = MaterialTheme.typography.bodySmall)
    }

    when (state.phase) {
        AssignmentPhase.REPLACED -> {
            val next = assignment.replacement?.number ?: assignment.replacement?.id
            Notice("Разнарядка заменена${next?.let { " на № $it" } ?: ""}. Рейсы этой разнарядки больше не отмечаются.")
            Text(
                text = if (assignment.shiftClosed) "Закрытие по этой разнарядке подтверждено 1С."
                else if (content.replacementForDriver) "Это прежняя разнарядка. Работайте по новой после выдачи ключей от машины."
                else "Это прежняя разнарядка. Если работа на сегодня завершена, сдайте ключи и пройдите медика. Если ждёте новую машину — уточните у диспетчера.",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AssignmentPhase.CANCELLED -> {
            Notice("Разнарядка отменена диспетчером.${reasonSuffix(state)}")
            CallDispatcherButton()
        }

        AssignmentPhase.NEW, AssignmentPhase.ACCEPTED -> {
            if (content.continuesSameDriverShift && !assignment.shiftStarted) {
                Notice("Разнарядка назначена взамен прежней, но 1С ещё не передала «СменаНачата=true». Дождитесь обновления; этапы рейсов пока недоступны.")
            }
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
                enabled = state.canStartShiftOn(today) && content.otherOpenShiftLabel == null &&
                    !content.continuesSameDriverShift,
                modifier = Modifier.fillMaxWidth().height(ButtonHeight),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text(if (content.continuesSameDriverShift) "Ожидаем продолжение смены от 1С" else "Начать смену",
                    style = MaterialTheme.typography.labelLarge)
            }
            // Механик выдаёт ключи своим АРМ, отдельно от диспетчера —
            // до этого "Начать смену" недоступна, даже если разнарядка уже
            // принята (нововведение 2026-10-13, architecture.md §14).
            if (state.phase == AssignmentPhase.ACCEPTED && !assignment.keysIssued) {
                Text(
                    text = "Ожидайте выдачи ключей механиком.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.phase == AssignmentPhase.ACCEPTED && assignment.keysIssued && !state.canStartShiftOn(today)) {
                val departureDay = runCatching { LocalDate.parse(assignment.departureDay) }.getOrNull()
                Text(
                    text = when {
                        departureDay == null -> "Некорректная дата выезда в разнарядке. Обратитесь к диспетчеру."
                        departureDay.isAfter(today) -> "Начать смену можно только в день выезда — ${assignment.departureDay}."
                        else -> "Дата выезда ${assignment.departureDay} прошла. Уточните разнарядку у диспетчера."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            content.otherOpenShiftLabel?.let { other ->
                Text(
                    text = "Сначала дождитесь закрытия смены по разнарядке № $other в 1С.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            CallDispatcherButton()
        }

        AssignmentPhase.IN_SHIFT -> {
            if (content.continuesSameDriverShift && assignment.shiftStarted && !assignment.keysIssued) {
                Notice("Разнарядка продолжает начатую смену. Дождитесь выдачи ключей от новой машины механиком.")
            }
            if (state.needsReacknowledge) {
                Notice("Маршрут изменён. Проверьте ездки и подтвердите ознакомление заново.")
                Button(onClick = onAcknowledge, modifier = Modifier.fillMaxWidth().height(ButtonHeight)) {
                    Text("Ознакомлен")
                }
            }
            if (state.cancelledByDispatcher) {
                Notice("Разнарядка отменена диспетчером.${reasonSuffix(state)} Дождитесь закрытия смены в 1С после осмотра медика.")
            } else {
                Text(
                    text = tripsProgressLabel(state),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                // Диспетчер мог снять часть рейсов сам (замена экипажа и
                // т.п.) — поясняем галочку/крестик у рейса без фактов водителя.
                if (state.hasTripsCancelledByDispatcher) {
                    Text(
                        text = "Часть рейсов сняли диспетчером.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                text = if (state.allTripsResolved) {
                    if (assignment.keysAccepted) "Рейсы завершены. Пройдите осмотр у медика. Смену закроет 1С после его подтверждения."
                    else "Рейсы завершены. Сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С."
                } else if (assignment.keysAccepted) {
                    "Ключи сданы, но есть незавершённые рейсы. Для каждого укажите «Не могу выполнить рейс» с причиной или дождитесь отмены диспетчером. После этого медик сможет закрыть смену."
                } else "После рейсов сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С.",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            CallDispatcherButton()
        }

        AssignmentPhase.FINISHED -> Text(
            text = when {
                assignment.shiftClosed -> "Смена закрыта"
                assignment.keysAccepted -> "Ключи сданы, ожидайте подтверждения медика и закрытия смены в 1С"
                else -> "Сдайте ключи механику и пройдите осмотр у медика. Смену закроет 1С"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

internal fun isFutureAssignment(departureDay: String, today: LocalDate = LocalDate.now(DRIVER_TIME_ZONE)): Boolean =
    runCatching { LocalDate.parse(departureDay).isAfter(today) }.getOrDefault(false)

internal fun isTomorrowAssignment(departureDay: String, today: LocalDate = LocalDate.now(DRIVER_TIME_ZONE)): Boolean =
    runCatching { LocalDate.parse(departureDay) == today.plusDays(1) }.getOrDefault(false)

@Composable
private fun UpcomingTrips(state: AssignmentState) {
    Text("Ездки по плану", style = MaterialTheme.typography.titleMedium)
    Text(
        text = "Это только просмотр. Отмечать этапы нужно после начала смены во вкладке «Мои рейсы».",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (state.trips.isEmpty()) {
        Text("Ездки ещё не переданы", style = MaterialTheme.typography.bodyMedium)
        return
    }
    state.trips.forEach { progress ->
        val trip = progress.trip
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "${trip.order}. ${pointShortLabel(trip.loadPoint)} → ${pointShortLabel(trip.unloadPoint)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (progress.isCancelled) {
                    Text(
                        text = "Рейс отменён" + (trip.cancelReason?.let { ": $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (progress.isFailed) Text("Не выполнена" + (trip.cancelReason?.let { ": $it" } ?: ""))
                if (progress.isInactive && !progress.isCancelled && !progress.isFailed) Text("Неактивна: ${trip.status}")
                tripPlanLabel(trip.planLoad, trip.planUnload)?.let {
                    LabeledField(label = "Время по плану", value = it)
                }
                LabeledField(label = "Погрузка", value = pointLabel(trip.loadPoint))
                LabeledField(label = "Разгрузка", value = pointLabel(trip.unloadPoint))
                if (trip.id in state.changedUnloadTripIds) {
                    Notice("Изменён адрес разгрузки: ${pointLabel(trip.unloadPoint)}")
                }
                trip.trailer.plate?.takeIf { it.isNotBlank() }?.let {
                    LabeledField(label = "Прицеп", value = it)
                }
                trip.customer?.takeIf { it.isNotBlank() }?.let {
                    LabeledField(label = "Заказчик", value = it)
                }
                CargoDetails(trip.cargo.positions, trip.cargo.weight, trip.cargo.volume, trip.cargo.composition)
                TripContacts(trip.contacts)
            }
        }
    }
}

private fun tripPlanLabel(planLoad: String?, planUnload: String?): String? {
    val load = formatTime(planLoad)
    val unload = formatTime(planUnload)
    return when {
        load != null && unload != null -> "$load — $unload"
        load != null -> load
        unload != null -> unload
        else -> null
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
