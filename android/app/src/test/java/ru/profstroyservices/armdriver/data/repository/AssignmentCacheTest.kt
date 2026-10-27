package ru.profstroyservices.armdriver.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.db.toEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.PersonDto
import ru.profstroyservices.armdriver.data.network.PointDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.network.VehicleDto

class AssignmentCacheTest {
    private val assignment = AssignmentDto(
        id = "assignment", version = 2, departureDay = "2026-10-16", status = "Активна",
        driver = PersonDto("driver"), vehicle = VehicleDto("vehicle"),
        trips = listOf(TripDto("trip", 1, "Активна", loadPoint = PointDto(), unloadPoint = PointDto()))
    )

    @Test
    fun `same or older version does not write or rebuild cached assignment`() {
        val cached = assignment.toEntity("driver", 100)
        assertNull(updatedAssignment(assignment, cached, "driver", 200))
        assertNull(updatedAssignment(assignment.copy(version = 1), cached, "driver", 200))
        assertNull(updatedAssignment(assignment.copy(keysIssued = true), cached, "driver", 200))
    }

    @Test
    fun `new keys version is applied and vanished trips remain visible`() {
        val fresh = assignment.copy(version = 3, keysIssued = true, trips = emptyList())
        val result = updatedAssignment(fresh, assignment.toEntity("driver", 100), "driver", 200)!!
        val decoded = result.toAssignmentDto()
        assertEquals(true, decoded.keysIssued)
        assertEquals(3, decoded.version)
        assertEquals(200L, result.updatedAt)
        assertEquals("trip", decoded.trips.single().id)
        assertEquals(STATUS_CANCELLED, decoded.trips.single().status)
    }
}
