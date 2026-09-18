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
