package ru.profstroyservices.armdriver.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MappersTest {

    private fun entity(
        latitude: Double? = null,
        longitude: Double? = null,
        locationAccuracy: Int? = null,
        locationFixTime: String? = null
    ) = PendingEventEntity(
        id = "e1",
        type = "Ознакомление",
        driverId = "driver",
        assignmentId = "a1",
        tripId = null,
        time = "2026-10-08T12:00:00+03:00",
        comment = "",
        latitude = latitude,
        longitude = longitude,
        locationAccuracy = locationAccuracy,
        locationFixTime = locationFixTime
    )

    @Test
    fun `geo block included when coordinates present`() {
        val request = entity(
            latitude = 58.603521,
            longitude = 49.668014,
            locationAccuracy = 12,
            locationFixTime = "2026-10-08T11:59:30+03:00"
        ).toEventRequest()

        assertEquals(58.603521, request.geo!!.latitude, 0.0)
        assertEquals(49.668014, request.geo!!.longitude, 0.0)
        assertEquals(12, request.geo!!.accuracy)
        assertEquals("2026-10-08T11:59:30+03:00", request.geo!!.fixTime)
    }

    @Test
    fun `geo block omitted when no fix was recorded`() {
        val request = entity().toEventRequest()

        assertNull(request.geo)
    }

    @Test
    fun `geo block omitted when only latitude is present`() {
        val request = entity(latitude = 58.603521).toEventRequest()

        assertNull(request.geo)
    }

    @Test
    fun `accuracy and fix time are optional on the geo block`() {
        val request = entity(latitude = 58.603521, longitude = 49.668014).toEventRequest()

        assertEquals(58.603521, request.geo!!.latitude, 0.0)
        assertNull(request.geo!!.accuracy)
        assertNull(request.geo!!.fixTime)
    }
}
