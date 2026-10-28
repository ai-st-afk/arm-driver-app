package ru.profstroyservices.armdriver.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseDriverQrTest {

    private val guid = "1cb0cea4-e22b-45b6-b43b-8ce59aa77d30"

    @Test
    fun `guid and name on separate lines`() {
        assertEquals(DriverQr(guid, "Костенко Павел Сергеевич"), parseDriverQr("$guid\nКостенко Павел Сергеевич"))
    }

    @Test
    fun `guid and name separated by semicolon in any order`() {
        assertEquals(DriverQr(guid, "Костенко Павел"), parseDriverQr("Костенко Павел;$guid"))
    }

    @Test
    fun `plain guid has no name`() {
        assertEquals(DriverQr(guid, null), parseDriverQr(guid))
    }

    @Test
    fun `uppercase guid is normalized to how 1C writes guids`() {
        assertEquals(guid, parseDriverQr(guid.uppercase())?.driverId)
    }

    @Test
    fun `text without guid is rejected`() {
        assertNull(parseDriverQr("https://example.com/promo"))
    }

    // Два разных GUID — неизвестно, какой из них водитель.
    @Test
    fun `two different guids are ambiguous`() {
        assertNull(parseDriverQr("$guid\nd77eefe2-c6ce-11f1-9835-d85ed35a8f26"))
    }

    // Кусок длинной hex-строки не должен сойти за GUID.
    @Test
    fun `guid embedded in longer hex run is rejected`() {
        assertNull(parseDriverQr("a$guid"))
        assertNull(parseDriverQr("${guid}f"))
    }

    // Лишние строки — непонятно, какая из них ФИО: GUID берём, ФИО — нет.
    @Test
    fun `several extra lines give no name`() {
        assertEquals(DriverQr(guid, null), parseDriverQr("$guid\nКостенко\nПавел"))
    }

    @Test
    fun `extra line without letters is not a name`() {
        assertEquals(DriverQr(guid, null), parseDriverQr("$guid\n00-0000123"))
    }
}
