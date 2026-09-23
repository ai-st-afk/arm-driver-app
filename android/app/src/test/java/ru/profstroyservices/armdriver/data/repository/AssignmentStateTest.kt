package ru.profstroyservices.armdriver.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.network.VehicleDto

class AssignmentStateTest {

    private var eventSeq = 0

    private fun trip(id: String, order: Int, status: String = "Активна") =
        TripDto(id = id, order = order, status = status, loadPoint = PointDto(), unloadPoint = PointDto())

    private fun assignment(
        id: String,
        version: Int = 1,
        status: String = "Активна",
        day: String = "2026-09-22",
        trips: List<TripDto> = listOf(trip("$id-t1", 1), trip("$id-t2", 2))
    ) = AssignmentDto(
        id = id,
        version = version,
        number = id,
        departureDay = day,
        status = status,
        driver = PersonDto(id = "driver"),
        vehicle = VehicleDto(id = "vehicle"),
        trips = trips
    )

    private fun event(
        assignmentId: String,
        type: String,
        tripId: String? = null,
        version: Int? = 1,
        cancelled: Boolean = false,
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
    fun shiftCannotEndWhileTripsRemain() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY)) +
                completeTrip("A", "A-t1")
        )
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(state.canMarkTrips)
        assertFalse(state.canEndShift)
        assertEquals("A-t2", state.activeTrip?.trip?.id)
        assertEquals(1, state.tripsResolved)
    }

    @Test
    fun shiftCanEndWhenAllTripsDone() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY)) +
                completeTrip("A", "A-t1") + completeTrip("A", "A-t2")
        )
        assertTrue(state.canEndShift)
        assertNull(state.activeTrip)
    }

    // Замена экипажа (1С): незавершённые рейсы старой разнарядки приходят
    // «Отменена», в том числе уже начатый — он закрыт, а не висит. 1С
    // подтвердила: ОкончаниеСмены не привязано к рейсам и его по-прежнему
    // можно (и нужно предлагать) отправить самому — кнопку не прячем, даже
    // когда все рейсы сняты диспетчером. Диспетчер закрывает смену вручную
    // только если водитель сам этого не сделает.
    @Test
    fun tripCancelledByDispatcherMidwayIsClosedButShiftStillEndableByDriver() {
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
        assertTrue(state.canEndShift)
    }

    @Test
    fun driverCanStillEndShiftAfterSendingOkonchanieSmeny() {
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
        assertFalse(state.canEndShift)
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
        assertFalse(state.canEndShift)
    }

    @Test
    fun assignmentCancelledBeforeShift() {
        val state = assignmentState(assignment("A", status = "Отменена"), listOf(event("A", EventTypes.OZNAKOMLENIE)))
        assertEquals(AssignmentPhase.CANCELLED, state.phase)
        assertFalse(state.canMarkTrips)
    }

    @Test
    fun assignmentCancelledDuringShiftMustBeClosedByDriver() {
        val state = assignmentState(
            assignment("A", status = "Отменена"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(state.cancelledByDispatcher)
        assertFalse(state.canMarkTrips)
        assertTrue(state.canEndShift)
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
        assertFalse(state.canEndShift)
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
    fun finishedAndCancelledAreNeverCurrent() {
        val finished = assignmentState(
            assignment("A"),
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.OKONCHANIE_SMENY)
            )
        )
        val cancelled = assignmentState(assignment("B", status = "Отменена"), emptyList())
        assertNull(currentAssignment(listOf(finished, cancelled)))
    }

    // Замена экипажа: старая (незавершённые сняты) и новая пришли почти
    // разом. Текущей остаётся старая, пока водитель сам не закончит по ней
    // смену (1С подтвердила: кнопку не прячем, водитель заканчивает сам,
    // время с телефона точнее вписанного диспетчером вручную) — только
    // после этого текущей становится новая.
    @Test
    fun crewReplacementKeepsOldCurrentUntilDriverEndsShift() {
        val old = assignment("OLD", version = 2, trips = listOf(trip("OLD-t1", 1), trip("OLD-t2", 2, "Отменена")))
        val new = assignment("NEW", trips = listOf(trip("NEW-t1", 1)))
        val oldEvents = listOf(event("OLD", EventTypes.OZNAKOMLENIE), event("OLD", EventTypes.NACHALO_SMENY)) +
            completeTrip("OLD", "OLD-t1")

        val before = listOf(assignmentState(old, oldEvents), assignmentState(new, emptyList()))
        assertEquals("OLD", currentAssignment(before)?.id)
        assertTrue(before.first().canEndShift)

        val after = listOf(
            assignmentState(old, oldEvents + event("OLD", EventTypes.OKONCHANIE_SMENY)),
            assignmentState(new, emptyList())
        )
        assertEquals("NEW", currentAssignment(after)?.id)
    }
}
