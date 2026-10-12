package ru.profstroyservices.armdriver.data.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorMessagesTest {

    @Test
    fun `http error with gateway code surfaces that code`() {
        val body = """{"error":"ONE_C_BASE_URL не настроен","code":"one_c_not_configured"}"""
            .toResponseBody("application/json".toMediaType())
        val exception = HttpException(Response.error<Any>(503, body))

        assertEquals(
            "Сервер временно недоступен. Попробуйте позже. (one_c_not_configured)",
            exception.userMessage()
        )
    }

    @Test
    fun `http error without parseable body falls back to http status code`() {
        val body = "".toResponseBody("text/plain".toMediaType())
        val exception = HttpException(Response.error<Any>(404, body))

        assertEquals("Данные не найдены на сервере. (http_404)", exception.userMessage())
    }

    @Test
    fun `unknown host has a stable code`() {
        assertEquals(
            "Нет связи с сервером. Проверьте интернет. (no_connection)",
            UnknownHostException().userMessage()
        )
    }

    @Test
    fun `timeout has a stable code`() {
        assertEquals("Сервер не отвечает. Попробуйте ещё раз. (timeout)", SocketTimeoutException().userMessage())
    }

    @Test
    fun `generic io exception has a stable code`() {
        assertEquals(
            "Нет связи. Записи сохранены в телефоне и уйдут позже. (io_error)",
            IOException().userMessage()
        )
    }
}
