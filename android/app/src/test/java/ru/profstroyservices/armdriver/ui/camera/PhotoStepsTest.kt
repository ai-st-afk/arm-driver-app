package ru.profstroyservices.armdriver.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.PhotoKind

class PhotoStepsTest {
    @Test
    fun clientRefusalUsesCargoPhotoAndAllowsMoreThanOne() {
        val steps = photoStepsFor(EventTypes.SRYV)!!
        assertEquals(listOf(PhotoKind.CARGO), steps.map { it.kind })
        assertEquals(true, steps.single().allowMore)
    }

    @Test
    fun `loading and unloading do not open camera`() {
        assertNull(photoStepsFor(EventTypes.ZAGRUZILSYA_V_PUT))
        assertNull(photoStepsFor(EventTypes.RAZGRUZILSYA))
    }

    @Test
    fun `steps without photos`() {
        assertNull(photoStepsFor(EventTypes.PRIBYL_NA_POGRUZKU))
        assertNull(photoStepsFor(EventTypes.PRIBYL_NA_RAZGRUZKU))
    }

    @Test
    fun `vehicle photos require four sides and free photo one`() {
        for (kind in listOf(PhotoKind.VEHICLE_KEYS, PhotoKind.VEHICLE_MEDIC)) {
            val steps = checkNotNull(photoStepsFor(kind.code))
            assertEquals(4, steps.size)
            assertEquals(listOf("Машина спереди", "Машина сзади", "Машина слева", "Машина справа"), steps.map { it.prompt })
            assertEquals(listOf(kind, kind, kind, kind), steps.map { it.kind })
        }
        assertEquals(listOf(PhotoKind.FREE), photoStepsFor(PhotoKind.FREE.code)?.map { it.kind })
    }

    @Test
    fun `next missing step skips what is already taken`() {
        val steps = checkNotNull(photoStepsFor(EventTypes.SRYV))
        assertEquals(0, nextMissingStep(steps, emptyList()))
        assertNull(nextMissingStep(steps, listOf(photo(PhotoKind.CARGO))))
    }

    private fun photo(kind: PhotoKind) = CapturedPhoto(kind, "/tmp/${kind.code}.jpg")
}
