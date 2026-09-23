package ru.profstroyservices.armdriver.data.repository

import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.TripDto
import java.time.OffsetDateTime

const val STATUS_CANCELLED = "Отменена"

// Разнарядка = смена: у каждой свои Ознакомление → НачалоСмены →
// ОкончаниеСмены (события уровня разнарядки, AGENTS.md). Отменить
// разнарядку или рейс водитель сам не может — это делает диспетчер в 1С,
// приложение только принимает новую версию.
enum class AssignmentPhase {
    NEW,
    ACCEPTED,
    IN_SHIFT,
    FINISHED,
    // Отменена диспетчером до начала смены. Если смена уже шла — остаётся
    // IN_SHIFT (все рейсы сняты), смену водитель закрывает сам.
    CANCELLED
}

data class TripProgress(val trip: TripDto, val doneTypes: Set<String>) {
    val isCancelled: Boolean get() = trip.status.equals(STATUS_CANCELLED, ignoreCase = true)
    val isCompleted: Boolean get() = EventTypes.RAZGRUZILSYA in doneTypes
    // Срыв водитель больше не отправляет (решает диспетчер), но события из
    // прежних версий приложения ещё могут лежать в базе.
    val isFailed: Boolean get() = EventTypes.SRYV in doneTypes
    // Снятый диспетчером рейс закрыт, даже если по нему уже были отметки —
    // так 1С снимает незавершённые рейсы при замене экипажа.
    val isResolved: Boolean get() = isCancelled || isCompleted || isFailed
    val nextAction: String? get() = if (isResolved) null else EventTypes.TRIP_CYCLE.firstOrNull { it !in doneTypes }
}

data class AssignmentState(
    val assignment: AssignmentDto,
    val phase: AssignmentPhase,
    val trips: List<TripProgress>,
    val shiftStartedAt: OffsetDateTime?,
    // Ознакомление было, но по прежней версии: 1С сбросила его, без нового
    // она отобьёт НачалоСмены («Смена начата без ознакомления с маршрутом»).
    val needsReacknowledge: Boolean,
    // Все незавершённые рейсы сняты диспетчером во время смены (замена
    // экипажа) — смену 1С закрыла сама.
    val endedByDispatcher: Boolean = false,
    // Когда данные последний раз реально пришли с шлюза — «Обновлено в HH:MM».
    val updatedAt: Long? = null
) {
    val id: String get() = assignment.id
    val label: String get() = assignment.number ?: assignment.id
    val cancelledByDispatcher: Boolean get() = assignment.status.equals(STATUS_CANCELLED, ignoreCase = true)
    val tripsResolved: Int get() = trips.count { it.isResolved }
    val allTripsResolved: Boolean get() = cancelledByDispatcher || trips.all { it.isResolved }
    val activeTrip: TripProgress? get() = if (cancelledByDispatcher) null else trips.firstOrNull { !it.isResolved }
    // Закончить смену можно только когда работы не осталось: все рейсы
    // выполнены или сняты диспетчером. Досрочно — только через диспетчера.
    val canEndShift: Boolean get() = phase == AssignmentPhase.IN_SHIFT && allTripsResolved
    val canMarkTrips: Boolean get() = phase == AssignmentPhase.IN_SHIFT && !cancelledByDispatcher
}

fun assignmentState(assignment: AssignmentDto, events: List<PendingEventEntity>): AssignmentState {
    val active = events.filter { it.assignmentId == assignment.id && !it.cancelled }
    fun has(type: String) = active.any { it.type == type }

    val acks = active.filter { it.type == EventTypes.OZNAKOMLENIE }
    // null — Ознакомление записано до того, как стали хранить версию;
    // считаем его действительным, чтобы не заставлять переподтверждать всё
    // после обновления приложения.
    val ackCurrent = acks.any { it.assignmentVersion == null || it.assignmentVersion >= assignment.version }
    val cancelled = assignment.status.equals(STATUS_CANCELLED, ignoreCase = true)

    val trips = assignment.trips
        .sortedBy { it.order }
        .map { trip -> TripProgress(trip, active.filter { it.tripId == trip.id }.map { it.type }.toSet()) }

    // 1С (5.4): при замене экипажа незавершённые ездки старой разнарядки
    // снимаются, а окончание смены 1С проставляет сама. ОкончаниеСмены в 1С
    // пишется «последнее пришедшее» — наше позднее нажатие перезаписало бы
    // верное время. Поэтому такая смена считается завершённой диспетчером,
    // водитель её не закрывает.
    val endedByDispatcher = has(EventTypes.NACHALO_SMENY) && !has(EventTypes.OKONCHANIE_SMENY) &&
        !cancelled && trips.isNotEmpty() && trips.all { it.isResolved } && trips.any { it.isCancelled }

    val phase = when {
        has(EventTypes.OKONCHANIE_SMENY) || endedByDispatcher -> AssignmentPhase.FINISHED
        has(EventTypes.NACHALO_SMENY) -> AssignmentPhase.IN_SHIFT
        cancelled -> AssignmentPhase.CANCELLED
        ackCurrent -> AssignmentPhase.ACCEPTED
        else -> AssignmentPhase.NEW
    }

    val shiftStartedAt = active.lastOrNull { it.type == EventTypes.NACHALO_SMENY }
        ?.let { runCatching { OffsetDateTime.parse(it.time) }.getOrDefault(OffsetDateTime.MIN) }

    return AssignmentState(
        assignment = assignment,
        phase = phase,
        trips = trips,
        shiftStartedAt = shiftStartedAt,
        needsReacknowledge = phase == AssignmentPhase.NEW && acks.isNotEmpty(),
        endedByDispatcher = endedByDispatcher
    )
}

// Текущая разнарядка — одна на всё приложение: по ней вкладка «Мои рейсы» и
// строка статуса сверху. Сначала та, где идёт смена (если их вдруг
// несколько — последняя начатая), иначе ближайшая ещё не начатая.
fun currentAssignment(states: List<AssignmentState>): AssignmentState? =
    states.filter { it.phase == AssignmentPhase.IN_SHIFT }.maxByOrNull { it.shiftStartedAt ?: OffsetDateTime.MIN }
        ?: states
            .filter { it.phase == AssignmentPhase.NEW || it.phase == AssignmentPhase.ACCEPTED }
            .minWithOrNull(compareBy({ it.assignment.departureDay }, { it.label }))
