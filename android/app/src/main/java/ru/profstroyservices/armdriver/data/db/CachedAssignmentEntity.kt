package ru.profstroyservices.armdriver.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Последняя разнарядка водителя, полученная от gateway — на случай
// офлайн-открытия приложения. Хранится как сериализованный JSON, а не
// разложенная по колонкам структура: сам gateway уже сделал валидацию
// и проекцию из XML, второй раз ту же работу здесь не переделываем.
// Не источник истины — 1С остаётся источником, это только локальный кэш.
@Entity(tableName = "cached_assignments")
data class CachedAssignmentEntity(
    @PrimaryKey val driverId: String,
    val assignmentJson: String,
    val version: Int,
    val updatedAt: Long
)
