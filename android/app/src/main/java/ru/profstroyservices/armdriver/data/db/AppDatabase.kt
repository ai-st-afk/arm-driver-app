package ru.profstroyservices.armdriver.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PendingEventEntity::class, CachedAssignmentEntity::class, PendingPhotoEntity::class],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingEventDao(): PendingEventDao
    abstract fun cachedAssignmentDao(): CachedAssignmentDao
    abstract fun pendingPhotoDao(): PendingPhotoDao
}
