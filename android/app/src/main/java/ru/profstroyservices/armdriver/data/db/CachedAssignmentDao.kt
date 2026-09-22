package ru.profstroyservices.armdriver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedAssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: CachedAssignmentEntity)

    @Query("SELECT * FROM cached_assignments WHERE driverId = :driverId ORDER BY updatedAt DESC")
    suspend fun getAllForDriver(driverId: String): List<CachedAssignmentEntity>

    @Query("SELECT * FROM cached_assignments WHERE id = :id")
    suspend fun getById(id: String): CachedAssignmentEntity?
}
