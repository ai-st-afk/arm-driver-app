package ru.profstroyservices.armdriver.ui.components

import ru.profstroyservices.armdriver.data.network.PointDto
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale("ru"))

// 1С присылает легально пустые элементы (`<Наименование/>`, `<Адрес/>`) —
// в боевой разнарядке так пришла разгрузка целиком. Пустая строка это не
// null, поэтому обычный `?:` тут не спасает и на экране появляется дыра.
fun firstNotBlank(vararg values: String?): String? =
    values.firstOrNull { !it.isNullOrBlank() }

fun pointLabel(point: PointDto): String =
    firstNotBlank(point.address, point.name) ?: "Адрес не указан"

fun pointShortLabel(point: PointDto): String =
    firstNotBlank(point.name, point.address) ?: "Адрес не указан"

// Время плана — ISO-8601 со смещением. Кривое значение не «чиним» молча
// (AGENTS.md → работа с XML), просто не показываем.
fun formatTime(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    return runCatching { OffsetDateTime.parse(iso).format(timeFormatter) }.getOrNull()
}

// В истории водитель видел сырое `2026-09-21T07:34:12+03:00`. Ему нужно
// «когда», а не машинная метка времени.
fun formatEventDateTime(iso: String?): String {
    if (iso.isNullOrBlank()) return ""
    val parsed = runCatching { OffsetDateTime.parse(iso) }.getOrNull() ?: return iso
    val date = parsed.toLocalDate()
    val today = LocalDate.now()
    val time = parsed.format(timeFormatter)
    return when (date) {
        today -> "Сегодня, $time"
        today.minusDays(1) -> "Вчера, $time"
        else -> "${date.format(dateFormatter)}, $time"
    }
}

// Нулевые значения груза 1С шлёт как "0"/"0.000" — водителю это мусор.
fun cargoValue(value: String?, unit: String? = null): String? {
    if (value.isNullOrBlank()) return null
    val number = value.replace(',', '.').toDoubleOrNull()
    if (number != null && number == 0.0) return null
    return if (unit.isNullOrBlank()) value else "$value $unit"
}
