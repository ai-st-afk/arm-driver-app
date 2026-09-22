package ru.profstroyservices.armdriver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Фото документа с разгрузки, снятое с камеры. Живёт локально до успешной
// загрузки на gateway (uploaded=true) — так же, как pending_events не
// удаляются до accepted:true. filePath — путь к файлу в приватном
// хранилище приложения (см. FileProvider), не content:// Uri из intent'а.
@Entity(tableName = "pending_photos")
data class PendingPhotoEntity(
    @PrimaryKey val id: String,
    val tripId: String,
    val driverId: String,
    val assignmentId: String,
    val filePath: String,
    val uploaded: Boolean = false,
    // Причина последнего отказа — тот же смысл, что lastError у событий
    // (см. PendingEventEntity). Раньше отказ загрузки фото проглатывался
    // молча: водитель видел голую цифру «не отправлено» без объяснения.
    val lastError: String? = null,
    // id — UUID, по нему «последнее» не определить. Нужно для
    // observeLastRejected — показывать причину самой свежей попытки.
    val enqueuedAt: Long = System.currentTimeMillis()
)
