package ru.profstroyservices.armdriver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Локальная очередь событий. GUID (id) генерируется на телефоне в момент
// события (инвариант из AGENTS.md), не тут. Строка живёт, пока событие не
// подтверждено 1С (accepted: true) — тогда удаляется поштучно, а не всей
// пачкой по факту HTTP 200 (иначе потеряем отбитые события).
@Entity(tableName = "pending_events")
data class PendingEventEntity(
    @PrimaryKey val id: String,
    val type: String,
    val driverId: String,
    val assignmentId: String,
    val tripId: String?,
    val time: String,
    val comment: String,
    val sent: Boolean = false,
    // Текст отказа 1С по этому событию (поштучный ответ, инвариант 2).
    // Водителю без него видно только «не отправлено», а не что чинить.
    val lastError: String? = null,
    // Водитель отменил шаг сам (кнопка «Отмена» — случайный повторный тап
    // и т.п.), пока событие ещё не ушло в 1С. Строку не удаляем, а помечаем:
    // 1) отмена должна остаться видна в «Истории», а не пропасть бесследно;
    // 2) doneTypes/sendPending читают эту же таблицу и обязаны отменённый
    //    шаг игнорировать, как будто его и не было.
    val cancelled: Boolean = false
)
