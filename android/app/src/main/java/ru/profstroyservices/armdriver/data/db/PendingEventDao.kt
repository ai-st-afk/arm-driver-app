package ru.profstroyservices.armdriver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingEventDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(event: PendingEventEntity)

    @Query("SELECT * FROM pending_events ORDER BY time")
    fun observeAll(): Flow<List<PendingEventEntity>>

    @Query("SELECT * FROM pending_events WHERE sent = 0 ORDER BY time")
    suspend fun getUnsent(): List<PendingEventEntity>

    @Query("DELETE FROM pending_events WHERE id = :id")
    suspend fun deleteById(id: String)

    // Отмена действия водителем в течение нескольких секунд после нажатия.
    // Условие sent = 0 обязательно: если событие уже ушло и принято 1С,
    // «отменять» локально нечего — там оно уже факт.
    @Query("DELETE FROM pending_events WHERE id = :id AND sent = 0")
    suspend fun deleteIfUnsent(id: String)

    // Не удаляем принятые события: doneTypes/acknowledged/shiftStarted
    // читают эту же таблицу целиком (см. observeForAssignment), чтобы
    // понять, что действие уже случилось. Удаление тут же "забыло" бы
    // прогресс и открыло кнопку заново — повторное нажатие родило бы
    // новый GUID для уже отправленного события.
    @Query("UPDATE pending_events SET sent = 1, lastError = NULL WHERE id = :id")
    suspend fun markSent(id: String)

    // Отбитое 1С событие остаётся в очереди (инвариант 2), но теперь с
    // причиной — её показываем водителю, иначе он видит только счётчик.
    @Query("UPDATE pending_events SET lastError = :error WHERE id = :id")
    suspend fun markRejected(id: String, error: String)

    @Query("SELECT * FROM pending_events WHERE sent = 0 AND lastError IS NOT NULL ORDER BY time DESC LIMIT 1")
    fun observeLastRejected(): Flow<PendingEventEntity?>

    @Query("SELECT COUNT(*) FROM pending_events WHERE sent = 0")
    fun observeUnsentCount(): Flow<Int>

    // Кнопка нажата второй раз (двойной тап, пересоздание экрана) не должна
    // рождать новый GUID для того же реального события — иначе 1С увидит
    // это как два разных события уровня/ездки.
    @Query(
        "SELECT EXISTS(SELECT 1 FROM pending_events " +
            "WHERE assignmentId = :assignmentId AND type = :type AND tripId IS :tripId)"
    )
    suspend fun exists(assignmentId: String, type: String, tripId: String?): Boolean

    @Query("SELECT * FROM pending_events WHERE assignmentId = :assignmentId")
    fun observeForAssignment(assignmentId: String): Flow<List<PendingEventEntity>>
}
