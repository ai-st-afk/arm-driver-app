package ru.profstroyservices.armdriver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingPhotoDao {

    @Insert
    suspend fun insert(photo: PendingPhotoEntity)

    @Query("SELECT * FROM pending_photos WHERE uploaded = 0")
    suspend fun getPending(): List<PendingPhotoEntity>

    @Query("SELECT COUNT(*) FROM pending_photos WHERE uploaded = 0")
    fun observePendingCount(): Flow<Int>

    @Query("DELETE FROM pending_photos WHERE id = :id")
    suspend fun deleteById(id: String)
}
