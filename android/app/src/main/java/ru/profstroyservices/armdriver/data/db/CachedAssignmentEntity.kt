package ru.profstroyservices.armdriver.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Разнарядки водителя, полученные от gateway — на случай офлайн-открытия
// приложения. За день у водителя может быть несколько разнарядок (см.
// Stage 8), поэтому первичный ключ — id разнарядки, а не driverId. Хранится
// как сериализованный JSON, а не разложенная по колонкам структура: сам
// gateway уже сделал валидацию и проекцию из XML, второй раз ту же работу
// здесь не переделываем. Не источник истины — 1С остаётся источником, это
// только локальный кэш.
@Entity(tableName = "cached_assignments", indices = [Index("driverId")])
data class CachedAssignmentEntity(
    @PrimaryKey val id: String,
    val driverId: String,
    val assignmentJson: String,
    val version: Int,
    val updatedAt: Long
)
