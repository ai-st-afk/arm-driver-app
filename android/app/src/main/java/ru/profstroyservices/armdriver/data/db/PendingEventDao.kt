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

    // cancelled = 0: отменённый шаг не факт, отправлять в 1С нечего.
    @Query("SELECT * FROM pending_events WHERE sent = 0 AND cancelled = 0 ORDER BY time")
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

    @Query("SELECT * FROM pending_events WHERE sent = 0 AND cancelled = 0 AND lastError IS NOT NULL ORDER BY time DESC LIMIT 1")
    fun observeLastRejected(): Flow<PendingEventEntity?>

    @Query("SELECT COUNT(*) FROM pending_events WHERE sent = 0 AND cancelled = 0")
    fun observeUnsentCount(): Flow<Int>

    // Кнопка «Отмена»: водитель сам отменяет ещё не отправленный шаг
    // (случайный повторный тап и т.п.). Не удаляем строку — она остаётся в
    // «Истории» с пометкой «отменено», а не пропадает бесследно. sent = 0
    // в условии обязателен: если событие уже ушло и принято 1С, отменять
    // локально уже нечего, там это факт.
    // Возвращает число изменённых строк — 0 означает «уже отправлено,
    // отменить нельзя».
    @Query("UPDATE pending_events SET cancelled = 1 WHERE id = :id AND sent = 0")
    suspend fun markCancelled(id: String): Int

    // Кнопка нажата второй раз (двойной тап, пересоздание экрана) не должна
    // рождать новый GUID для того же реального события — иначе 1С увидит
    // это как два разных события уровня/рейса. cancelled = 0: если прошлая
    // попытка этого шага отменена, шаг можно завести заново.
    @Query(
        "SELECT EXISTS(SELECT 1 FROM pending_events " +
            "WHERE assignmentId = :assignmentId AND type = :type AND tripId IS :tripId AND cancelled = 0)"
    )
    suspend fun exists(assignmentId: String, type: String, tripId: String?): Boolean

    // Ознакомление дедуплицируется по версии разнарядки: новая версия
    // требует нового Ознакомления (1С сбрасывает прежнее), старое не мешает.
    @Query(
        "SELECT EXISTS(SELECT 1 FROM pending_events " +
            "WHERE assignmentId = :assignmentId AND type = :type " +
            "AND assignmentVersion = :assignmentVersion AND cancelled = 0)"
    )
    suspend fun existsForVersion(assignmentId: String, type: String, assignmentVersion: Int): Boolean

    // Для кнопки «Отмена» по этапу рейса: находит ещё не отправленную и ещё
    // не отменённую запись конкретного шага. Если её нет — шаг уже отправлен
    // в 1С, и отменить нечего (инвариант 2).
    @Query(
        "SELECT * FROM pending_events " +
            "WHERE assignmentId = :assignmentId AND tripId = :tripId AND type = :type " +
            "AND sent = 0 AND cancelled = 0 LIMIT 1"
    )
    suspend fun findUnsent(assignmentId: String, tripId: String, type: String): PendingEventEntity?

    @Query("SELECT * FROM pending_events WHERE assignmentId = :assignmentId")
    fun observeForAssignment(assignmentId: String): Flow<List<PendingEventEntity>>
}
