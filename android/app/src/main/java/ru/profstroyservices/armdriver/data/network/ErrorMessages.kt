package ru.profstroyservices.armdriver.data.network

import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// Водителю нельзя показывать текст исключения: на экране оказывалось
// `Unable to resolve host "arm-driver..."` — английская техническая строка,
// по которой человек может только позвонить диспетчеру. Сами ошибки при
// этом продолжают логироваться как есть (AGENTS.md → никаких «тихих»
// fallback'ов), подменяется только то, что читает водитель.
fun Throwable.userMessage(): String = when (this) {
    is UnknownHostException, is ConnectException ->
        "Нет связи с сервером. Проверьте интернет."

    is SocketTimeoutException ->
        "Сервер не отвечает. Попробуйте ещё раз."

    is HttpException -> when (code()) {
        401, 403 -> "Телефон не настроен для работы. Обратитесь к диспетчеру."
        404 -> "Данные не найдены на сервере."
        in 500..599 -> "Сервер временно недоступен. Попробуйте позже."
        else -> "Сервер отказал в запросе (${code()})."
    }

    is IOException ->
        "Нет связи. Записи сохранены в телефоне и уйдут позже."

    else -> "Не удалось получить данные."
}
