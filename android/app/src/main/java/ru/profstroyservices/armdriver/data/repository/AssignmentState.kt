package ru.profstroyservices.armdriver.data.repository

import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.TripDto
import java.time.OffsetDateTime
import java.time.LocalDate
import java.time.ZoneId

const val STATUS_CANCELLED = "Отменена"
const val STATUS_ASSIGNED = "Назначена"
const val STATUS_FAILED = "Сорвана"
const val STATUS_REPLACED = "Заменена"
val DRIVER_TIME_ZONE: ZoneId = ZoneId.of("Europe/Kirov")

// Признаки смены приходят в разнарядке; отдельного GUID смены в контракте нет.
// Старые мобильные ОкончаниеСмены учитываются для уже записанных событий.
// Новое закрытие смены приходит из 1С через СменаЗакрыта.
enum class AssignmentPhase {
    NEW,
    ACCEPTED,
    IN_SHIFT,
    FINISHED,
    REPLACED,
    // Отменена диспетчером до начала смены. Если смена уже шла — остаётся
    // IN_SHIFT до закрытия смены в 1С.
    CANCELLED
}

data class TripProgress(val trip: TripDto, val doneTypes: Set<String>, val failureSent: Boolean = false, val returnedAfterFailure: Boolean = false) {
    val isCancelled: Boolean get() = trip.status.equals(STATUS_CANCELLED, ignoreCase = true)
    val isInactive: Boolean get() = !trip.status.equals(STATUS_ASSIGNED, ignoreCase = true) &&
        !trip.status.equals("Активна", ignoreCase = true)
    val isCompleted: Boolean get() = EventTypes.RAZGRUZILSYA in doneTypes
    val cargoInVehicle: Boolean get() = trip.cargoInVehicle == true ||
        (EventTypes.ZAGRUZILSYA_V_PUT in doneTypes && !isCompleted)
    val isFailed: Boolean get() = trip.status.equals(STATUS_FAILED, ignoreCase = true) || failureSent
    val isFailurePending: Boolean get() = !isFailed && EventTypes.SRYV in doneTypes
    // Снятый диспетчером рейс закрыт, даже если по нему уже были отметки —
    // так 1С снимает незавершённые рейсы при замене экипажа.
    val isResolved: Boolean get() = isInactive || isCompleted || isFailed || isFailurePending || trip.nextStage == ""
    val nextAction: String? get() {
        if (isResolved) return null
        val contracted = when (trip.nextStage) {
            null -> EventTypes.TRIP_CYCLE.first()
            else -> trip.nextStage
        }
        val start = EventTypes.TRIP_CYCLE.indexOf(contracted)
        if (start < 0) return null
        return EventTypes.TRIP_CYCLE.drop(start).firstOrNull { it !in doneTypes }
    }
}

data class AssignmentState(
    val assignment: AssignmentDto,
    val phase: AssignmentPhase,
    val trips: List<TripProgress>,
    val shiftStartedAt: OffsetDateTime?,
    // Ознакомление было, но по прежней версии: 1С сбросила его, без нового
    // она отобьёт НачалоСмены («Смена начата без ознакомления с маршрутом»).
    val needsReacknowledge: Boolean,
    val changedUnloadTripIds: Set<String> = emptySet(),
    // Хотя бы один рейс снят диспетчером (замена экипажа и т.п.) — просто
    // для текста на экране; отмена ездки не закрывает смену.
    val hasTripsCancelledByDispatcher: Boolean = false,
    // Когда данные последний раз реально пришли с шлюза — «Обновлено в HH:MM».
    val updatedAt: Long? = null
) {
    val id: String get() = assignment.id
    val label: String get() = assignment.number ?: assignment.id
    val cancelledByDispatcher: Boolean get() = assignment.status.equals(STATUS_CANCELLED, ignoreCase = true)
    val replaced: Boolean get() = assignment.status.equals(STATUS_REPLACED, ignoreCase = true)
    val tripsResolved: Int get() = trips.count { it.isResolved }
    val allTripsResolved: Boolean get() = cancelledByDispatcher || trips.all { it.isResolved }
    // Вернувшаяся после Срыва ездка не выдёргивает водителя из следующей:
    // её можно выбрать вручную, либо она откроется, когда остальные завершены.
    val activeTrip: TripProgress? get() = if (cancelledByDispatcher || replaced) null else
        trips.firstOrNull { !it.isResolved && !it.returnedAfterFailure }
            ?: trips.firstOrNull { !it.isResolved }
    val canMarkTrips: Boolean get() = phase == AssignmentPhase.IN_SHIFT && !cancelledByDispatcher &&
        (!assignment.shiftStarted || assignment.keysIssued)
    fun canMarkTripStep(tripId: String, type: String): Boolean {
        if (!canMarkTrips) return false
        val progress = trips.firstOrNull { it.trip.id == tripId } ?: return false
        return !progress.isResolved &&
            (progress.trip.id == activeTrip?.trip?.id || progress.returnedAfterFailure) &&
            progress.nextAction == type
    }
    // Механик ещё не выдал ключи своим АРМ — "Начать смену" недоступна,
    // даже если разнарядка принята (нововведение 2026-10-13).
    val canStartShift: Boolean get() = phase == AssignmentPhase.ACCEPTED && assignment.keysIssued
    fun canStartShiftOn(today: LocalDate): Boolean = canStartShift &&
        runCatching { LocalDate.parse(assignment.departureDay) }.getOrNull() == today
}

fun assignmentState(
    assignment: AssignmentDto,
    events: List<PendingEventEntity>,
    ackRequiredVersion: Int = assignment.version,
    changedUnloadTripIds: Set<String> = emptySet()
): AssignmentState {
    val active = events.filter { it.assignmentId == assignment.id && !it.cancelled && it.lastError == null }
    fun has(type: String) = active.any { it.type == type }

    val acks = active.filter { it.type == EventTypes.OZNAKOMLENIE }
    // null — Ознакомление записано до того, как стали хранить версию;
    // считаем его действительным, чтобы не заставлять переподтверждать всё
    // после обновления приложения.
    val ackCurrent = acks.any { it.assignmentVersion == null || it.assignmentVersion >= ackRequiredVersion }
    val cancelled = assignment.status.equals(STATUS_CANCELLED, ignoreCase = true)
    val replaced = assignment.status.equals(STATUS_REPLACED, ignoreCase = true)

    val trips = assignment.trips
        .sortedBy { it.order }
        .map { trip ->
            val tripEvents = active.filter { it.tripId == trip.id }
            val previousFailure = tripEvents
                .filter { it.type == EventTypes.SRYV && it.sent &&
                    it.assignmentVersion != null && it.assignmentVersion < assignment.version }
                .maxByOrNull { it.time }
                ?.takeIf { trip.status.equals(STATUS_ASSIGNED, ignoreCase = true) }
            // После перенаправления 1С сохраняет погрузку, но стирает прибытие
            // на прежний адрес. Старый Срыв остаётся в истории, не в прогрессе.
            val effectiveEvents = tripEvents.filterNot { event ->
                previousFailure != null && ((event.type == EventTypes.SRYV && event.sent &&
                    event.assignmentVersion != null && event.assignmentVersion < assignment.version) ||
                    event.type == EventTypes.PRIBYL_NA_RAZGRUZKU && event.time <= previousFailure.time)
            }
            val serverDone = buildSet {
                trip.stages?.arrivedLoad?.let { add(EventTypes.PRIBYL_NA_POGRUZKU) }
                trip.stages?.loaded?.let { add(EventTypes.ZAGRUZILSYA_V_PUT) }
                trip.stages?.arrivedUnload?.let { add(EventTypes.PRIBYL_NA_RAZGRUZKU) }
                trip.stages?.unloaded?.let { add(EventTypes.RAZGRUZILSYA) }
            }
            TripProgress(
                trip, effectiveEvents.map { it.type }.toSet() + serverDone,
                effectiveEvents.any { it.type == EventTypes.SRYV && it.sent },
                previousFailure != null
            )
        }

    val phase = when {
        replaced -> AssignmentPhase.REPLACED
        assignment.shiftClosed -> AssignmentPhase.FINISHED
        has(EventTypes.OKONCHANIE_SMENY) -> AssignmentPhase.FINISHED
        assignment.shiftStarted || has(EventTypes.NACHALO_SMENY) -> AssignmentPhase.IN_SHIFT
        cancelled -> AssignmentPhase.CANCELLED
        ackCurrent -> AssignmentPhase.ACCEPTED
        else -> AssignmentPhase.NEW
    }

    val shiftStartedAt = active.lastOrNull { it.type == EventTypes.NACHALO_SMENY }
        ?.let { runCatching { OffsetDateTime.parse(it.time) }.getOrDefault(OffsetDateTime.MIN) }
        ?: assignment.shiftStartedAt?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    return AssignmentState(
        assignment = assignment,
        phase = phase,
        trips = trips,
        shiftStartedAt = shiftStartedAt,
        needsReacknowledge = !ackCurrent && acks.isNotEmpty(),
        changedUnloadTripIds = changedUnloadTripIds,
        hasTripsCancelledByDispatcher = trips.any { it.isCancelled }
    )
}

fun sameDriverReplacement(state: AssignmentState, states: List<AssignmentState>): Boolean =
    state.assignment.basis?.id?.let { previousId ->
        states.any { it.id == previousId && it.assignment.driver.id == state.assignment.driver.id }
    } == true

// Текущая разнарядка — одна на всё приложение: по ней вкладка «Мои рейсы» и
// строка статуса сверху. Сначала та, где идёт смена (если их вдруг
// несколько — последняя начатая), иначе ближайшая ещё не начатая.
// При равных дате/номере или времени начала выбираем по GUID: INSERT OR
// REPLACE в Room меняет порядок строк, который не должен переключать рейсы.
fun currentAssignment(states: List<AssignmentState>): AssignmentState? {
    val inShift = states.filter { it.phase == AssignmentPhase.IN_SHIFT }
    val inherited = inShift.filter { it.assignment.shiftStarted && sameDriverReplacement(it, states) }
    val latestInherited = inherited.filter { candidate ->
        inherited.none { it.assignment.basis?.id == candidate.id }
    }
    if (latestInherited.size > 1) return null
    val linkedReplacement = states.filter { it.phase == AssignmentPhase.REPLACED }.mapNotNull { old ->
        states.firstOrNull { candidate ->
            candidate.id == old.assignment.replacement?.id &&
                candidate.assignment.driver.id == old.assignment.driver.id &&
                !candidate.assignment.shiftClosed &&
                candidate.phase != AssignmentPhase.REPLACED && candidate.phase != AssignmentPhase.CANCELLED
        }
    }.distinctBy { it.id }
    if (linkedReplacement.size > 1) return null
    return latestInherited.singleOrNull()
        ?: inShift
        .maxWithOrNull(compareBy({ it.shiftStartedAt ?: OffsetDateTime.MIN }, { it.id }))
        ?: linkedReplacement.singleOrNull()
        ?: states.filter { it.phase == AssignmentPhase.FINISHED && !it.assignment.shiftClosed }
            .maxWithOrNull(compareBy({ it.assignment.departureDay }, { it.id }))
        ?: states
            .filter { it.phase == AssignmentPhase.NEW || it.phase == AssignmentPhase.ACCEPTED }
            .minWithOrNull(compareBy({ it.assignment.departureDay }, { it.label }, { it.id }))
}
