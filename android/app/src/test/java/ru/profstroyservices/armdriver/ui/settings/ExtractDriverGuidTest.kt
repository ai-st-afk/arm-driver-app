package ru.profstroyservices.armdriver.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtractDriverGuidTest {

    private val guid = "1cb0cea4-e22b-45b6-b43b-8ce59aa77d30"

    @Test
    fun `plain guid is accepted`() {
        assertEquals(guid, extractDriverGuid(guid))
    }

    @Test
    fun `surrounding whitespace and text are tolerated`() {
        assertEquals(guid, extractDriverGuid("  Водитель: Костенко П.\nGUID: $guid\n"))
    }

    @Test
    fun `uppercase is normalized to how 1C writes guids`() {
        assertEquals(guid, extractDriverGuid(guid.uppercase()))
    }

    @Test
    fun `text without guid is rejected`() {
        assertNull(extractDriverGuid("https://example.com/promo"))
    }

    // Два разных GUID — неизвестно, какой из них водитель.
    @Test
    fun `two different guids are ambiguous`() {
        assertNull(extractDriverGuid("$guid d77eefe2-c6ce-11f1-9835-d85ed35a8f26"))
    }

    @Test
    fun `same guid repeated is still one guid`() {
        assertEquals(guid, extractDriverGuid("$guid / $guid"))
    }

    // Кусок длинной hex-строки не должен сойти за GUID.
    @Test
    fun `guid embedded in longer hex run is rejected`() {
        assertNull(extractDriverGuid("a$guid"))
        assertNull(extractDriverGuid("${guid}f"))
    }
}
