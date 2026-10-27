package ru.profstroyservices.armdriver.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.db.toEntity
import ru.profstroyservices.armdriver.ui.components.dialNumber

class CargoContactsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `gateway fixture and local cache preserve cargo totals and contacts`() {
        val raw = javaClass.getResource("/assignment-cargo-contacts.json")!!.readText()
        val assignment = json.decodeFromString<AssignmentDto>(raw)
        assertEquals(assignment, assignment.toEntity("driver", 123L).toAssignmentDto())
        assertEquals("18.500", assignment.shiftTotal!!.weight)
        assertEquals(2, assignment.shiftTotal!!.positions.items.size)
        val trip = assignment.trips[0]
        assertEquals("4.000", trip.cargo.positions!!.items[0].quantity)
        assertEquals("шт", trip.cargo.positions!!.items[0].unit)
        assertEquals(4, trip.contacts.size)
        assertEquals("Разгрузка", trip.contacts[0].purpose)
        assertEquals(2, trip.contacts[0].phones.size)
        assertEquals("8 (8332) 40-55-50", trip.contacts[0].phones[0].display)
        assertEquals("+78332405550", trip.contacts[0].phones[0].dialNumber())
        assertTrue(trip.contacts[2].phones.isEmpty())
        assertEquals("", trip.contacts[3].name)
        assertEquals("405550", trip.contacts[3].phones[0].dialNumber())
        assertNull(trip.contacts[3].phones[1].dialNumber())
        val cancelled = assignment.trips[1]
        assertEquals("Отменена", cancelled.status)
        assertNotNull(cancelled.cargo.positions)
        assertTrue(cancelled.cargo.positions!!.items.isEmpty())
        assertTrue(cancelled.contacts.isEmpty())
    }

    @Test
    fun `old cache without extension remains readable and empty positions stay explicit`() {
        val old = """{"id":"a","version":1,"departure_day":"2026-10-14","status":"Активна",
            "driver":{"id":"d"},"vehicle":{"id":"v"},"trips":[
            {"id":"t","order":1,"status":"Назначена","load_point":{},"unload_point":{},
            "cargo":{"composition":"Старое описание","quantity":"10.000","unit":"шт"}}]}"""
        val assignment = json.decodeFromString<AssignmentDto>(old)
        assertNull(assignment.shiftTotal)
        assertNull(assignment.trips[0].cargo.positions)
        assertTrue(assignment.trips[0].contacts.isEmpty())
        val empty = json.decodeFromString<CargoDto>("""{"composition":"Старое описание","positions":{}}""")
        assertNotNull(empty.positions)
        assertTrue(empty.positions!!.items.isEmpty())
    }

    @Test
    fun `same product with different units is preserved as separate positions`() {
        val total = json.decodeFromString<CargoTotalDto>("""{"positions":{"items":[
            {"product_id":"same","name":"Груз","unit":"шт","quantity":"2.000","weight":"0.000","volume":"0.000"},
            {"product_id":"same","name":"Груз","unit":"м³","quantity":"3.000","weight":"0.000","volume":"0.000"}
            ]},"weight":"99.000","volume":"88.000"}""")
        assertEquals(listOf("шт", "м³"), total.positions.items.map { it.unit })
        assertEquals("99.000", total.weight)
    }

    @Test
    fun `dialing never derives a number from display text or accepts dial commands`() {
        for (number in listOf("", " ", "8 (8332) 40-55-50", "*100#", "+7;123", "++123", "tel:123")) {
            assertNull(ContactPhoneDto("+7 912 345-67-89", number).dialNumber())
        }
        assertEquals("+79123456789", ContactPhoneDto("мобильный", "+79123456789").dialNumber())
        assertEquals("405550", ContactPhoneDto("40-55-50", "405550").dialNumber())
    }
}
