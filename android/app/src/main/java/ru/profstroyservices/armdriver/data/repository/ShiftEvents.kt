package ru.profstroyservices.armdriver.data.repository

import ru.profstroyservices.armdriver.data.db.PendingEventEntity
import java.time.OffsetDateTime

// НачалоСмены/ОкончаниеСмены — события уровня разнарядки (AGENTS.md), у
// каждой разнарядки своя смена. Открыта = есть НачалоСмены и нет
// ОкончаниеСмены (отменённые шаги не в счёт). Время начала нужно, чтобы
// из нескольких открытых везде одинаково выбирать текущую — последнюю начатую.
fun openShiftStartedAt(events: List<PendingEventEntity>): OffsetDateTime? {
    if (events.any { it.type == EventTypes.OKONCHANIE_SMENY && !it.cancelled }) return null
    val start = events.lastOrNull { it.type == EventTypes.NACHALO_SMENY && !it.cancelled } ?: return null
    return runCatching { OffsetDateTime.parse(start.time) }.getOrDefault(OffsetDateTime.MIN)
}
