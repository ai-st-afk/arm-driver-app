package ru.profstroyservices.armdriver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedAssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: CachedAssignmentEntity)

    @Query("SELECT * FROM cached_assignments WHERE driverId = :driverId ORDER BY updatedAt DESC")
    suspend fun getAllForDriver(driverId: String): List<CachedAssignmentEntity>

    @Query("SELECT * FROM cached_assignments WHERE id = :id")
    suspend fun getById(id: String): CachedAssignmentEntity?

    @Query("SELECT * FROM cached_assignments WHERE driverId = :driverId")
    fun observeAllForDriver(driverId: String): Flow<List<CachedAssignmentEntity>>

    // Разнарядки, которых шлюз больше не отдаёт водителю, иначе висели бы в
    // кэше вечно как рабочие.
    @Query("DELETE FROM cached_assignments WHERE driverId = :driverId AND id NOT IN (:keepIds)")
    suspend fun deleteForDriverExcept(driverId: String, keepIds: List<String>)
}
