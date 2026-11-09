package ru.profstroyservices.armdriver.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import ru.profstroyservices.armdriver.data.repository.AssignmentPhase
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.assignmentState
import ru.profstroyservices.armdriver.data.repository.currentAssignment
import ru.profstroyservices.armdriver.data.repository.sameDriverReplacement

class ReplacementDeliveryTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun assignment(name: String): AssignmentDto = json.decodeFromString(
        AssignmentDto.serializer(),
        javaClass.getResource("/$name")!!.readText()
    )

    @Test
    fun `gateway JSON changes working route without a second start event`() {
        val old = assignment("replacement-old.json")
        val new = assignment("replacement-new.json")
        val startedOld = PendingEventEntity(
            id = "88888888-8888-4888-8888-888888888888",
            type = EventTypes.NACHALO_SMENY,
            driverId = old.driver.id,
            assignmentId = old.id,
            tripId = null,
            time = "2026-11-09T07:00:00+03:00",
            comment = ""
        )
        val oldLoadingFact = startedOld.copy(
            id = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            type = EventTypes.ZAGRUZILSYA_V_PUT,
            tripId = old.trips.single().id,
            time = "2026-11-09T07:30:00+03:00"
        )
        val oldState = assignmentState(old, listOf(startedOld, oldLoadingFact))
        val newState = assignmentState(new, emptyList())

        assertEquals(AssignmentPhase.REPLACED, oldState.phase)
        assertEquals(AssignmentPhase.IN_SHIFT, newState.phase)
        assertTrue(sameDriverReplacement(newState, listOf(oldState, newState)))
        assertEquals(new.id, currentAssignment(listOf(oldState, newState))?.id)
        assertTrue(EventTypes.ZAGRUZILSYA_V_PUT in oldState.trips.single().doneTypes)
        assertTrue(newState.trips.single().doneTypes.isEmpty())
        assertFalse(newState.canStartShift)
        assertFalse(newState.canMarkTrips)

        val afterKeys = assignmentState(new.copy(version = 3, keysIssued = true), emptyList())
        assertTrue(afterKeys.canMarkTrips)
        assertEquals(EventTypes.PRIBYL_NA_POGRUZKU, afterKeys.activeTrip?.nextAction)
    }

    @Test
    fun `same JSON with a different driver does not inherit old shift`() {
        val old = assignmentState(assignment("replacement-old.json"), emptyList())
        val new = assignment("replacement-new.json").copy(
            driver = PersonDto(id = "99999999-9999-4999-8999-999999999999"),
            shiftStarted = false,
            keysIssued = true
        )
        val newState = assignmentState(new, emptyList())
        assertFalse(sameDriverReplacement(newState, listOf(old, newState)))
        assertEquals(AssignmentPhase.NEW, newState.phase)
        assertFalse(newState.canMarkTrips)
    }

    @Test
    fun `loaded replacement continues at unloading without loading again`() {
        val loaded = assignment("replacement-loaded.json")
        val state = assignmentState(loaded, emptyList())
        val trip = state.trips.single()
        assertEquals(AssignmentPhase.IN_SHIFT, state.phase)
        assertTrue(trip.cargoInVehicle)
        assertTrue(EventTypes.ZAGRUZILSYA_V_PUT in trip.doneTypes)
        assertEquals(EventTypes.PRIBYL_NA_RAZGRUZKU, trip.nextAction)
        assertFalse(state.canMarkTripStep(trip.trip.id, EventTypes.ZAGRUZILSYA_V_PUT))
        assertTrue(state.canMarkTripStep(trip.trip.id, EventTypes.PRIBYL_NA_RAZGRUZKU))

        val arrival = PendingEventEntity(
            id = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            type = EventTypes.PRIBYL_NA_RAZGRUZKU,
            driverId = loaded.driver.id,
            assignmentId = loaded.id,
            tripId = trip.trip.id,
            time = "2026-11-09T11:20:00+03:00",
            comment = ""
        )
        val afterArrival = assignmentState(loaded, listOf(arrival))
        assertEquals(EventTypes.RAZGRUZILSYA, afterArrival.activeTrip?.nextAction)
        assertTrue(afterArrival.canMarkTripStep(trip.trip.id, EventTypes.RAZGRUZILSYA))
    }

    @Test
    fun `new driver waits for own start but keeps transferred loading stage`() {
        val loaded = assignment("replacement-loaded.json").copy(
            driver = PersonDto(id = "99999999-9999-4999-8999-999999999999"),
            shiftStarted = false,
            shiftStartedAt = null
        )
        val beforeStart = assignmentState(loaded, emptyList())
        assertEquals(AssignmentPhase.NEW, beforeStart.phase)
        assertFalse(beforeStart.canMarkTrips)
        assertEquals(EventTypes.PRIBYL_NA_RAZGRUZKU, beforeStart.activeTrip?.nextAction)

        val start = PendingEventEntity(
            id = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            type = EventTypes.NACHALO_SMENY,
            driverId = loaded.driver.id,
            assignmentId = loaded.id,
            tripId = null,
            time = "2026-11-09T10:30:00+03:00",
            comment = ""
        )
        val afterStart = assignmentState(loaded, listOf(start))
        assertTrue(afterStart.canMarkTrips)
        assertEquals(EventTypes.PRIBYL_NA_RAZGRUZKU, afterStart.activeTrip?.nextAction)
    }

    @Test
    fun `local loading remains visible while one c packet is behind`() {
        val packet = assignment("replacement-new.json").copy(keysIssued = true)
        val arrival = PendingEventEntity(
            id = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            type = EventTypes.PRIBYL_NA_POGRUZKU,
            driverId = packet.driver.id,
            assignmentId = packet.id,
            tripId = packet.trips.single().id,
            time = "2026-11-09T08:25:00+03:00",
            comment = ""
        )
        val loaded = arrival.copy(
            id = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
            type = EventTypes.ZAGRUZILSYA_V_PUT,
            time = "2026-11-09T08:50:00+03:00"
        )
        val state = assignmentState(packet, listOf(arrival, loaded))
        assertTrue(state.activeTrip!!.cargoInVehicle)
        assertEquals(EventTypes.PRIBYL_NA_RAZGRUZKU, state.activeTrip?.nextAction)
    }
}
