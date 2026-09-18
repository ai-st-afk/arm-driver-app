package ru.profstroyservices.armdriver.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CachedAssignmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(assignment: CachedAssignmentEntity)

    @Query("SELECT * FROM cached_assignments WHERE driverId = :driverId")
    suspend fun getForDriver(driverId: String): CachedAssignmentEntity?
}
