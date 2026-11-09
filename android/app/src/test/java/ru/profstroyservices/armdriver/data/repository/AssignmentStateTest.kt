package ru.profstroyservices.armdriver.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.AssignmentLinkDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.network.VehicleDto
import java.time.LocalDate

class AssignmentStateTest {

    @Test
    fun sameNumberAndDayDoNotChangeCurrentWhenCacheOrderChanges() {
        val first = assignmentState(assignment("A").copy(number = "00000000001"), emptyList())
        val second = assignmentState(assignment("B").copy(number = "00000000001"), emptyList())
        val third = assignmentState(assignment("C").copy(number = "00000000001"), emptyList())
        for (order in listOf(
            listOf(first, second, third), listOf(second, third, first), listOf(third, first, second),
            listOf(third, second, first), listOf(second, first, third), listOf(first, third, second)
        )) {
            assertEquals("A", currentAssignment(order)?.id)
        }
    }

    @Test
    fun equalShiftStartTimesDoNotChangeCurrentWhenCacheOrderChanges() {
        val sameTime = "2026-10-15T10:00:00+03:00"
        val first = assignmentState(assignment("A"), listOf(event("A", EventTypes.NACHALO_SMENY, time = sameTime)))
        val second = assignmentState(assignment("B"), listOf(event("B", EventTypes.NACHALO_SMENY, time = sameTime)))
        assertEquals(currentAssignment(listOf(first, second))?.id, currentAssignment(listOf(second, first))?.id)
    }

    private var eventSeq = 0

    private fun trip(id: String, order: Int, status: String = "Активна") =
        TripDto(id = id, order = order, status = status, loadPoint = PointDto(), unloadPoint = PointDto())

    private fun assignment(
        id: String,
        version: Int = 1,
        status: String = "Активна",
        day: String = "2026-09-22",
        trips: List<TripDto> = listOf(trip("$id-t1", 1), trip("$id-t2", 2)),
        keysIssued: Boolean = false
    ) = AssignmentDto(
        id = id,
        version = version,
        number = id,
        departureDay = day,
        status = status,
        driver = PersonDto(id = "driver"),
        vehicle = VehicleDto(id = "vehicle"),
        trips = trips,
        keysIssued = keysIssued
    )

    private fun event(
        assignmentId: String,
        type: String,
        tripId: String? = null,
        version: Int? = 1,
        cancelled: Boolean = false,
        sent: Boolean = false,
        time: String = "2026-09-22T0${eventSeq % 10}:00:00+03:00"
    ) = PendingEventEntity(
        id = "e${eventSeq++}",
        type = type,
        driverId = "driver",
        assignmentId = assignmentId,
        tripId = tripId,
        time = time,
        comment = "",
        cancelled = cancelled,
        sent = sent,
        assignmentVersion = version
    )

    private fun completeTrip(assignmentId: String, tripId: String) =
        EventTypes.TRIP_CYCLE.map { event(assignmentId, it, tripId) }

    @Test
    fun newAssignmentWaitsForAcknowledge() {
        val state = assignmentState(assignment("A"), emptyList())
        assertEquals(AssignmentPhase.NEW, state.phase)
        assertFalse(state.needsReacknowledge)
        assertFalse(state.canMarkTrips)
    }

    @Test
    fun acknowledgeOfCurrentVersionAccepts() {
        val state = assignmentState(assignment("A"), listOf(event("A", EventTypes.OZNAKOMLENIE)))
        assertEquals(AssignmentPhase.ACCEPTED, state.phase)
    }

    // Нововведение 2026-10-13: механик выдаёт ключи своим АРМ, отдельно от
    // диспетчера — до этого "Начать смену" недоступна, даже если
    // разнарядка принята (architecture.md §14).
    // Нововведение 2026-10-13: мы автоматически подтверждаем новую версию,
    // если разница между ней и старой — только ключи, чтобы водитель не
    // жал "Ознакомился и принял" заново из-за флага механика.
    @Test
    fun keysOnlyVersionBumpIsTrivial() {
        val old = assignment("A", version = 1, keysIssued = false)
        val new = assignment("A", version = 2, keysIssued = true)
        assertTrue(isTrivialKeysVersionBump(old, new))
    }

    @Test
    fun routeChangeAlongsideVersionBumpIsNotTrivial() {
        val old = assignment("A", version = 1, keysIssued = false)
        val new = assignment("A", version = 2, keysIssued = true, trips = listOf(trip("A-t1", 1), trip("A-t3", 3)))
        assertFalse(isTrivialKeysVersionBump(old, new))
    }

    @Test
    fun sameVersionIsNotABumpAtAll() {
        val old = assignment("A", version = 1, keysIssued = false)
        val new = assignment("A", version = 1, keysIssued = true)
        assertFalse(isTrivialKeysVersionBump(old, new))
    }

    @Test
    fun olderVersionIsNeverTrivial() {
        val old = assignment("A", version = 2, keysIssued = true)
        val new = assignment("A", version = 1, keysIssued = false)
        assertFalse(isTrivialKeysVersionBump(old, new))
    }

    @Test
    fun acceptedWithoutKeysCannotStartShift() {
        val state = assignmentState(
            assignment("A", keysIssued = false),
            listOf(event("A", EventTypes.OZNAKOMLENIE))
        )
        assertEquals(AssignmentPhase.ACCEPTED, state.phase)
        assertFalse(state.canStartShift)
    }

    @Test
    fun acceptedWithKeysCanStartShift() {
        val state = assignmentState(
            assignment("A", keysIssued = true),
            listOf(event("A", EventTypes.OZNAKOMLENIE))
        )
        assertEquals(AssignmentPhase.ACCEPTED, state.phase)
        assertTrue(state.canStartShift)
        assertTrue(state.canStartShiftOn(LocalDate.parse("2026-09-22")))
    }

    @Test
    fun acceptedTomorrowCannotStartShiftToday() {
        val state = assignmentState(
            assignment("A", day = "2026-09-23", keysIssued = true),
            listOf(event("A", EventTypes.OZNAKOMLENIE))
        )
        assertFalse(state.canStartShiftOn(LocalDate.parse("2026-09-22")))
        assertTrue(state.canStartShiftOn(LocalDate.parse("2026-09-23")))
        assertFalse(state.canStartShiftOn(LocalDate.parse("2026-09-24")))
    }

    @Test
    fun invalidDepartureDayDoesNotAllowStart() {
        val state = assignmentState(
            assignment("A", day = "2026-09-", keysIssued = true),
            listOf(event("A", EventTypes.OZNAKOMLENIE))
        )
        assertFalse(state.canStartShiftOn(LocalDate.parse("2026-09-22")))
    }

    // Ключи выданы, но разнарядка ещё не принята (не было Ознакомления) —
    // фаза важнее: начинать смену рано вне зависимости от ключей.
    @Test
    fun newAssignmentCannotStartShiftEvenWithKeys() {
        val state = assignmentState(assignment("A", keysIssued = true), emptyList())
        assertEquals(AssignmentPhase.NEW, state.phase)
        assertFalse(state.canStartShift)
    }

    // 1С: новая версия до начала смены сбрасывает Ознакомление.
    @Test
    fun newVersionBeforeShiftRequiresAcknowledgeAgain() {
        val state = assignmentState(
            assignment("A", version = 2),
            listOf(event("A", EventTypes.OZNAKOMLENIE, version = 1))
        )
        assertEquals(AssignmentPhase.NEW, state.phase)
        assertTrue(state.needsReacknowledge)
    }

    @Test
    fun unfinishedTripRemainsActive() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY)) +
                completeTrip("A", "A-t1")
        )
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(state.canMarkTrips)
        assertFalse(state.allTripsResolved)
        assertEquals("A-t2", state.activeTrip?.trip?.id)
        assertEquals(1, state.tripsResolved)
    }

    @Test
    fun failedTripFromEventUnblocksNextTripOffline() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY), event("A", EventTypes.SRYV, "A-t1"))
        )
        assertTrue(state.trips.first().isFailurePending)
        assertEquals("A-t2", state.activeTrip?.trip?.id)
    }

    @Test
    fun reroutedFailedTripKeepsLoadingButRequiresArrivalAtNewAddress() {
        val events = listOf(
            event("A", EventTypes.OZNAKOMLENIE),
            event("A", EventTypes.NACHALO_SMENY),
            event("A", EventTypes.PRIBYL_NA_POGRUZKU, "A-t1"),
            event("A", EventTypes.ZAGRUZILSYA_V_PUT, "A-t1"),
            event("A", EventTypes.PRIBYL_NA_RAZGRUZKU, "A-t1"),
            event("A", EventTypes.SRYV, "A-t1", sent = true)
        )
        val rerouted = assignment("A", version = 2, trips = listOf(
            trip("A-t1", 1, STATUS_ASSIGNED).copy(unloadPoint = PointDto(address = "Новый адрес")),
            trip("A-t2", 2, STATUS_ASSIGNED)
        ))
        val state = assignmentState(rerouted, events)
        val returned = state.trips.first()
        assertTrue(returned.returnedAfterFailure)
        assertFalse(returned.isFailed)
        assertTrue(EventTypes.ZAGRUZILSYA_V_PUT in returned.doneTypes)
        assertFalse(EventTypes.PRIBYL_NA_RAZGRUZKU in returned.doneTypes)
        assertEquals(EventTypes.PRIBYL_NA_RAZGRUZKU, returned.nextAction)
        assertEquals("A-t2", state.activeTrip?.trip?.id)
        assertTrue(state.canMarkTripStep("A-t1", EventTypes.PRIBYL_NA_RAZGRUZKU))
        assertFalse(state.canMarkTripStep("A-t1", EventTypes.RAZGRUZILSYA))
        assertTrue(state.needsReacknowledge)

        val arrived = assignmentState(
            rerouted,
            events + event("A", EventTypes.PRIBYL_NA_RAZGRUZKU, "A-t1", version = 2)
        )
        assertEquals("A-t2", arrived.activeTrip?.trip?.id)
        assertTrue(arrived.canMarkTripStep("A-t1", EventTypes.RAZGRUZILSYA))
    }

    @Test
    fun newArrivalAfterRerouteAdvancesToUnloading() {
        val events = listOf(
            event("A", EventTypes.PRIBYL_NA_POGRUZKU, "A-t1"),
            event("A", EventTypes.ZAGRUZILSYA_V_PUT, "A-t1"),
            event("A", EventTypes.PRIBYL_NA_RAZGRUZKU, "A-t1"),
            event("A", EventTypes.SRYV, "A-t1", sent = true),
            event("A", EventTypes.PRIBYL_NA_RAZGRUZKU, "A-t1", version = 2)
        )
        val state = assignmentState(assignment("A", version = 2, trips = listOf(trip("A-t1", 1, STATUS_ASSIGNED))), events)
        assertEquals(EventTypes.RAZGRUZILSYA, state.trips.single().nextAction)
    }

    @Test
    fun unknownAndFailedStatusesAreInactive() {
        val state = assignmentState(assignment("A", trips = listOf(trip("x", 1, "Сорвана"), trip("y", 2, "НовыйСтатус"))), emptyList())
        assertTrue(state.trips.all { it.isResolved })
        assertNull(state.activeTrip)
    }

    @Test
    fun mechanicCanCloseReplacementShiftWithoutDriverEndEvent() {
        val closed = assignmentState(
            assignment("A").copy(keysAccepted = true, shiftClosed = true),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )
        assertEquals(AssignmentPhase.FINISHED, closed.phase)
        assertFalse(closed.canMarkTrips)
        assertNull(currentAssignment(listOf(closed)))
    }

    @Test
    fun allTripsResolvedWhenCompletedButShiftWaitsForMedic() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY)) +
                completeTrip("A", "A-t1") + completeTrip("A", "A-t2")
        )
        assertFalse(state.needsReacknowledge)
        assertTrue(state.allTripsResolved)
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertNull(state.activeTrip)
    }

    // Замена экипажа (1С): незавершённые рейсы старой разнарядки приходят
    // «Отменена», в том числе уже начатый — он закрыт, а не висит. Закрытие
    // смены приходит отдельно из 1С после подтверждения медика.
    @Test
    fun tripCancelledByDispatcherMidwayIsResolvedWhileShiftStaysOpen() {
        val assignment = assignment("A", version = 2, trips = listOf(trip("A-t1", 1), trip("A-t2", 2, "Отменена")))
        val state = assignmentState(
            assignment,
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.PRIBYL_NA_POGRUZKU, "A-t2")
            ) + completeTrip("A", "A-t1")
        )
        val cancelledTrip = state.trips.single { it.trip.id == "A-t2" }
        assertTrue(cancelledTrip.isResolved)
        assertNull(cancelledTrip.nextAction)
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(state.hasTripsCancelledByDispatcher)
        assertTrue(state.needsReacknowledge)
        assertTrue(state.allTripsResolved)
        assertEquals(AssignmentPhase.IN_SHIFT, assignmentState(assignment, listOf(event("A", EventTypes.OZNAKOMLENIE, version = 2), event("A", EventTypes.NACHALO_SMENY)) + completeTrip("A", "A-t1")).phase)
    }

    @Test
    fun legacyDriverEndEventStillMarksShiftFinished() {
        val assignment = assignment("A", version = 2, trips = listOf(trip("A-t1", 1, "Отменена"), trip("A-t2", 2, "Отменена")))
        val state = assignmentState(
            assignment,
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.OKONCHANIE_SMENY)
            )
        )
        assertEquals(AssignmentPhase.FINISHED, state.phase)
    }

    // Отмена одной ездки при оставшихся — смена продолжается как обычно.
    @Test
    fun singleTripCancelledKeepsShiftGoing() {
        val assignment = assignment("A", version = 2, trips = listOf(trip("A-t1", 1, "Отменена"), trip("A-t2", 2)))
        val state = assignmentState(
            assignment,
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertEquals("A-t2", state.activeTrip?.trip?.id)
        assertFalse(state.allTripsResolved)
    }

    @Test
    fun assignmentCancelledBeforeShift() {
        val state = assignmentState(assignment("A", status = "Отменена"), listOf(event("A", EventTypes.OZNAKOMLENIE)))
        assertEquals(AssignmentPhase.CANCELLED, state.phase)
        assertFalse(state.canMarkTrips)
    }

    @Test
    fun assignmentCancelledDuringShiftWaitsForClosureFromOneC() {
        val state = assignmentState(
            assignment("A", status = "Отменена"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(state.cancelledByDispatcher)
        assertFalse(state.canMarkTrips)
        assertTrue(state.allTripsResolved)
    }

    @Test
    fun endedShiftIsFinished() {
        val state = assignmentState(
            assignment("A"),
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.OKONCHANIE_SMENY)
            )
        )
        assertEquals(AssignmentPhase.FINISHED, state.phase)
        assertFalse(state.canMarkTrips)
    }

    @Test
    fun undoneStepIsIgnored() {
        val state = assignmentState(
            assignment("A"),
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.PRIBYL_NA_POGRUZKU, "A-t1", cancelled = true)
            )
        )
        assertEquals(EventTypes.PRIBYL_NA_POGRUZKU, state.trips.first().nextAction)
    }

    @Test
    fun currentIsAssignmentInShift() {
        val inShift = assignmentState(
            assignment("A", day = "2026-09-23"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )
        val waiting = assignmentState(assignment("B", day = "2026-09-22"), emptyList())
        assertEquals("A", currentAssignment(listOf(waiting, inShift))?.id)
    }

    @Test
    fun currentIsEarliestWaitingWhenNoShift() {
        val later = assignmentState(assignment("B", day = "2026-09-23"), emptyList())
        val earlier = assignmentState(assignment("A", day = "2026-09-22"), emptyList())
        assertEquals("A", currentAssignment(listOf(later, earlier))?.id)
    }

    @Test
    fun finishedRemainsCurrentUntilMedicClosesShift() {
        val finished = assignmentState(
            assignment("A"),
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.OKONCHANIE_SMENY)
            )
        )
        val cancelled = assignmentState(assignment("B", status = "Отменена"), emptyList())
        assertEquals("A", currentAssignment(listOf(finished, cancelled))?.id)
        assertNull(currentAssignment(listOf(finished.copy(assignment = finished.assignment.copy(shiftClosed = true)), cancelled)))
    }

    // Замена экипажа: старая (незавершённые сняты) и новая пришли почти
    // разом. Текущей остаётся старая до закрытия её смены в 1С.
    @Test
    fun crewReplacementKeepsOldCurrentUntilOneCClosesShift() {
        val old = assignment("OLD", version = 2, trips = listOf(trip("OLD-t1", 1), trip("OLD-t2", 2, "Отменена")))
        val new = assignment("NEW", trips = listOf(trip("NEW-t1", 1)))
        val oldEvents = listOf(event("OLD", EventTypes.OZNAKOMLENIE), event("OLD", EventTypes.NACHALO_SMENY)) +
            completeTrip("OLD", "OLD-t1")

        val before = listOf(assignmentState(old, oldEvents), assignmentState(new, emptyList()))
        assertEquals("OLD", currentAssignment(before)?.id)
        assertTrue(before.first().needsReacknowledge)

        val after = listOf(
            assignmentState(old.copy(shiftClosed = true), oldEvents),
            assignmentState(new, emptyList())
        )
        assertEquals("NEW", currentAssignment(after)?.id)
    }

    @Test
    fun sameDriverReplacementContinuesOnNewAssignmentWithoutNewStartEvent() {
        val old = assignmentState(
            assignment("OLD", status = STATUS_REPLACED, trips = listOf(trip("OLD-t1", 1, STATUS_CANCELLED)))
                .copy(replacement = AssignmentLinkDto("NEW", "37")),
            listOf(event("OLD", EventTypes.OZNAKOMLENIE), event("OLD", EventTypes.NACHALO_SMENY))
        )
        val newDto = assignment("NEW", keysIssued = false)
            .copy(basis = AssignmentLinkDto("OLD", "36"), shiftStarted = true)
        val waitingKeys = assignmentState(newDto, emptyList())
        assertEquals(AssignmentPhase.REPLACED, old.phase)
        assertFalse(old.canMarkTrips)
        assertEquals(AssignmentPhase.IN_SHIFT, waitingKeys.phase)
        assertFalse(waitingKeys.canStartShift)
        assertFalse(waitingKeys.canMarkTrips)
        assertEquals("NEW", currentAssignment(listOf(old, waitingKeys))?.id)

        val issued = assignmentState(newDto.copy(version = 2, keysIssued = true), emptyList())
        assertTrue(issued.canMarkTrips)
        assertEquals(EventTypes.PRIBYL_NA_POGRUZKU, issued.activeTrip?.nextAction)
        assertEquals("NEW", currentAssignment(listOf(issued, old))?.id)
    }

    @Test
    fun inheritedReplacementWinsWhenOldPacketIsDelayed() {
        val old = assignmentState(
            assignment("OLD"),
            listOf(event("OLD", EventTypes.NACHALO_SMENY))
        )
        val new = assignmentState(
            assignment("NEW", keysIssued = true)
                .copy(basis = AssignmentLinkDto("OLD", "36"), shiftStarted = true),
            emptyList()
        )
        assertEquals("NEW", currentAssignment(listOf(old, new))?.id)
    }

    @Test
    fun replacementLinkSelectsNewRouteButMissingStartFlagKeepsTripsLocked() {
        val old = assignmentState(
            assignment("OLD", status = STATUS_REPLACED)
                .copy(replacement = AssignmentLinkDto("NEW", "37")),
            listOf(event("OLD", EventTypes.NACHALO_SMENY))
        )
        val new = assignmentState(
            assignment("NEW", keysIssued = true).copy(basis = AssignmentLinkDto("OLD", "36")),
            emptyList()
        )
        assertEquals("NEW", currentAssignment(listOf(old, new))?.id)
        assertTrue(sameDriverReplacement(new, listOf(old, new)))
        assertEquals(AssignmentPhase.NEW, new.phase)
        assertFalse(new.canMarkTrips)
    }

    @Test
    fun differentDriverReplacementStillRequiresOwnStart() {
        val new = assignmentState(
            assignment("NEW", keysIssued = true).copy(basis = AssignmentLinkDto("OLD", "36")),
            emptyList()
        )
        assertEquals(AssignmentPhase.NEW, new.phase)
        assertFalse(new.canMarkTrips)
        val acknowledged = assignmentState(new.assignment, listOf(event("NEW", EventTypes.OZNAKOMLENIE)))
        assertEquals(AssignmentPhase.ACCEPTED, acknowledged.phase)
        assertTrue(acknowledged.canStartShift)
    }

    @Test
    fun oldDriverSeesReplacedAssignmentOnlyInHistoryUntilMedicClosesShift() {
        val oldDto = assignment("OLD", status = STATUS_REPLACED)
            .copy(replacement = AssignmentLinkDto("NEW", "37"))
        val waitingMedic = assignmentState(
            oldDto,
            listOf(event("OLD", EventTypes.NACHALO_SMENY))
        )
        val tomorrow = assignmentState(assignment("TOMORROW", day = "2026-09-23"), emptyList())
        assertNull(currentAssignment(listOf(waitingMedic)))
        assertEquals("TOMORROW", currentAssignment(listOf(waitingMedic, tomorrow))?.id)
        assertFalse(waitingMedic.canMarkTrips)

        val closed = assignmentState(oldDto.copy(shiftClosed = true), emptyList())
        assertEquals("TOMORROW", currentAssignment(listOf(closed, tomorrow))?.id)
    }

    @Test
    fun closedReplacementLinkCannotTakeOverNextShift() {
        val old = assignmentState(
            assignment("OLD", status = STATUS_REPLACED)
                .copy(replacement = AssignmentLinkDto("NEW", "37"), shiftClosed = true),
            emptyList()
        )
        val finishedReplacement = assignmentState(
            assignment("NEW").copy(basis = AssignmentLinkDto("OLD", "36"), shiftStarted = true, shiftClosed = true),
            emptyList()
        )
        val next = assignmentState(
            assignment("NEXT", day = "2026-09-23"),
            listOf(event("NEXT", EventTypes.NACHALO_SMENY))
        )
        assertEquals("NEXT", currentAssignment(listOf(old, finishedReplacement, next))?.id)
    }

}
