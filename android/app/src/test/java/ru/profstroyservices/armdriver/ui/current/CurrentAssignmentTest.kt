package ru.profstroyservices.armdriver.ui.current

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.VehicleDto
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.assignmentState

class CurrentAssignmentTest {
    private fun assignment(id: String, day: String) = AssignmentDto(
        id = id,
        version = 1,
        number = id,
        departureDay = day,
        status = "Активна",
        driver = PersonDto(id = "driver"),
        vehicle = VehicleDto(id = "vehicle"),
        trips = emptyList()
    )

    private fun event(id: String, type: String) = PendingEventEntity(
        id = "$id-$type",
        type = type,
        driverId = "driver",
        assignmentId = id,
        tripId = null,
        time = "2026-11-06T08:00:00+03:00",
        comment = ""
    )

    @Test
    fun `new assignment on current shift day is highlighted until acknowledged`() {
        val old = assignmentState(
            assignment("OLD", "2026-11-06"),
            listOf(event("OLD", EventTypes.OZNAKOMLENIE), event("OLD", EventTypes.NACHALO_SMENY))
        )
        val new = assignmentState(assignment("NEW", "2026-11-06"), emptyList())
        assertEquals("NEW", currentWithNewAssignment(listOf(old, new)).newSameDayAssignment?.id)

        val acknowledged = assignmentState(
            assignment("NEW", "2026-11-06"),
            listOf(event("NEW", EventTypes.OZNAKOMLENIE))
        )
        assertNull(currentWithNewAssignment(listOf(old, acknowledged)).newSameDayAssignment)
    }

    @Test
    fun `tomorrow assignment does not interrupt current shift`() {
        val old = assignmentState(
            assignment("OLD", "2026-11-06"),
            listOf(event("OLD", EventTypes.OZNAKOMLENIE), event("OLD", EventTypes.NACHALO_SMENY))
        )
        val tomorrow = assignmentState(assignment("TOMORROW", "2026-11-07"), emptyList())
        assertNull(currentWithNewAssignment(listOf(old, tomorrow)).newSameDayAssignment)
    }
}
