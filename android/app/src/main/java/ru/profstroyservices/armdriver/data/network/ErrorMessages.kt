package ru.profstroyservices.armdriver.data.network

import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Отдельный от DI-инстанса Json (NetworkModule) — здесь нужен только разбор
// errorBody() тела ошибки, тащить для этого Hilt в чистую функцию-расширение
// Throwable лишнее.
private val errorJson = Json { ignoreUnknownKeys = true }

// Код ошибки — то, на что водитель может сослаться диспетчеру/нам вместо
// пересказа текста своими словами. У HTTP-ошибок это code из ErrorResponse
// шлюза (errorResponse в server.go); если тело не распарсилось — код самого
// HTTP-статуса, чтобы код был у каждой ошибки без исключений.
private fun HttpException.errorCode(): String {
    val body = response()?.errorBody()?.let { runCatching { it.string() }.getOrNull() }
    val parsed = body?.let { runCatching { errorJson.decodeFromString<ErrorResponse>(it) }.getOrNull() }
    return parsed?.code ?: "http_${code()}"
}

// Водителю нельзя показывать текст исключения: на экране оказывалось
// `Unable to resolve host "arm-driver..."` — английская техническая строка,
// по которой человек может только позвонить диспетчеру. Сами ошибки при
// этом продолжают логироваться как есть (AGENTS.md → никаких «тихих»
// fallback'ов), подменяется только то, что читает водитель. Код в скобках —
// у каждой ветки свой, чтобы при жалобе была конкретная зацепка, а не
// только фраза.
fun Throwable.userMessage(): String = when (this) {
    is UnknownHostException, is ConnectException ->
        "Нет связи с сервером. Проверьте интернет. (no_connection)"

    is SocketTimeoutException ->
        "Сервер не отвечает. Попробуйте ещё раз. (timeout)"

    is HttpException -> {
        val message = when (code()) {
            401, 403 -> "Телефон не настроен для работы. Обратитесь к диспетчеру."
            404 -> "Данные не найдены на сервере."
            in 500..599 -> "Сервер временно недоступен. Попробуйте позже."
            else -> "Сервер отказал в запросе."
        }
        "$message (${errorCode()})"
    }

    is IOException ->
        "Нет связи. Записи сохранены в телефоне и уйдут позже. (io_error)"

    else -> "Не удалось получить данные. (unknown_error)"
}
