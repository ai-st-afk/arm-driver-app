package ru.profstroyservices.armdriver.ui.assignment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.network.VehicleDto
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.assignmentState

class AssignmentsListViewModelTest {

    private var eventSeq = 0

    private fun trip(id: String, order: Int, loadName: String = "", unloadName: String = "") = TripDto(
        id = id,
        order = order,
        status = "Активна",
        loadPoint = PointDto(name = loadName),
        unloadPoint = PointDto(name = unloadName)
    )

    private fun assignment(
        id: String,
        status: String = "Активна",
        day: String = "2026-09-22",
        trips: List<TripDto> = listOf(trip("$id-t1", 1))
    ) = AssignmentDto(
        id = id,
        version = 1,
        number = id,
        departureDay = day,
        status = status,
        driver = PersonDto(id = "driver"),
        vehicle = VehicleDto(id = "vehicle"),
        trips = trips
    )

    private fun event(assignmentId: String, type: String, tripId: String? = null) = PendingEventEntity(
        id = "e${eventSeq++}",
        type = type,
        driverId = "driver",
        assignmentId = assignmentId,
        tripId = tripId,
        time = "2026-09-22T0${eventSeq % 10}:00:00+03:00",
        comment = ""
    )

    @Test
    fun `newer departure day sorts above older`() {
        val older = assignmentState(assignment("A", day = "2026-09-20"), emptyList())
        val newer = assignmentState(assignment("B", day = "2026-09-22"), emptyList())

        val ids = assignmentsListState(listOf(older, newer)).assignments.map { it.id }

        assertEquals(listOf("B", "A"), ids)
    }

    @Test
    fun `new or accepted assignment is marked pending`() {
        val new = assignmentState(assignment("A"), emptyList())
        val accepted = assignmentState(assignment("B"), listOf(event("B", EventTypes.OZNAKOMLENIE)))

        val summaries = assignmentsListState(listOf(new, accepted)).assignments.associateBy { it.id }

        assertTrue(summaries["A"]!!.isPending)
        assertTrue(summaries["B"]!!.isPending)
    }

    @Test
    fun `in shift assignment is not pending`() {
        val state = assignmentState(
            assignment("A"),
            listOf(event("A", EventTypes.OZNAKOMLENIE), event("A", EventTypes.NACHALO_SMENY))
        )

        val summary = assignmentsListState(listOf(state)).assignments.single()

        assertEquals(false, summary.isPending)
    }

    @Test
    fun `finished assignment carries a trips summary`() {
        val a = assignment(
            "A",
            trips = listOf(trip("A-t1", 1, loadName = "Киров", unloadName = "Мураши"))
        )
        val state = assignmentState(
            a,
            listOf(
                event("A", EventTypes.OZNAKOMLENIE),
                event("A", EventTypes.NACHALO_SMENY),
                event("A", EventTypes.OKONCHANIE_SMENY)
            )
        )

        val summary = assignmentsListState(listOf(state)).assignments.single()

        assertEquals("Киров → Мураши", summary.tripsSummary)
    }

    @Test
    fun `active assignment has no trips summary`() {
        val state = assignmentState(assignment("A"), emptyList())

        val summary = assignmentsListState(listOf(state)).assignments.single()

        assertNull(summary.tripsSummary)
    }
}
