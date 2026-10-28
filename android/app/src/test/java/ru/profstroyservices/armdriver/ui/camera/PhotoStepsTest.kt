package ru.profstroyservices.armdriver.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.profstroyservices.armdriver.data.repository.CapturedPhoto
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.PhotoKind

class PhotoStepsTest {

    @Test
    fun `loading requires truck rear and side`() {
        assertEquals(
            listOf(PhotoKind.TRUCK_REAR, PhotoKind.TRUCK_SIDE),
            photoStepsFor(EventTypes.ZAGRUZILSYA_V_PUT)?.map { it.kind }
        )
    }

    @Test
    fun `unloading requires truck rear, side and document`() {
        assertEquals(
            listOf(PhotoKind.TRUCK_REAR, PhotoKind.TRUCK_SIDE, PhotoKind.DOCUMENT),
            photoStepsFor(EventTypes.RAZGRUZILSYA)?.map { it.kind }
        )
    }

    @Test
    fun `only the document allows extra sheets`() {
        assertEquals(listOf(false, false, true), photoStepsFor(EventTypes.RAZGRUZILSYA)?.map { it.allowMore })
    }

    @Test
    fun `steps without photos`() {
        assertNull(photoStepsFor(EventTypes.PRIBYL_NA_POGRUZKU))
        assertNull(photoStepsFor(EventTypes.PRIBYL_NA_RAZGRUZKU))
    }

    @Test
    fun `next missing step skips what is already taken`() {
        val steps = checkNotNull(photoStepsFor(EventTypes.RAZGRUZILSYA))
        assertEquals(0, nextMissingStep(steps, emptyList()))
        assertEquals(2, nextMissingStep(steps, listOf(photo(PhotoKind.TRUCK_REAR), photo(PhotoKind.TRUCK_SIDE))))
        assertEquals(1, nextMissingStep(steps, listOf(photo(PhotoKind.TRUCK_REAR), photo(PhotoKind.DOCUMENT))))
    }

    @Test
    fun `nothing missing when every kind has a photo`() {
        val steps = checkNotNull(photoStepsFor(EventTypes.RAZGRUZILSYA))
        val all = listOf(PhotoKind.TRUCK_REAR, PhotoKind.TRUCK_SIDE, PhotoKind.DOCUMENT, PhotoKind.DOCUMENT).map(::photo)
        assertNull(nextMissingStep(steps, all))
    }

    private fun photo(kind: PhotoKind) = CapturedPhoto(kind, "/tmp/${kind.code}.jpg")
}
