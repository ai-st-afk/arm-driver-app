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
}
