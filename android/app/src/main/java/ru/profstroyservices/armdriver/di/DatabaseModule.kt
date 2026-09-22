package ru.profstroyservices.armdriver.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.profstroyservices.armdriver.data.db.AppDatabase
import ru.profstroyservices.armdriver.data.db.CachedAssignmentDao
import ru.profstroyservices.armdriver.data.db.MIGRATION_3_4
import ru.profstroyservices.armdriver.data.db.PendingEventDao
import ru.profstroyservices.armdriver.data.db.PendingPhotoDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "arm-driver.db")
            .addMigrations(MIGRATION_3_4)
            // Схемы 1 и 2 существовали только на машинах разработки — их
            // пересоздаём. Начиная с 3 база переживает обновление приложения,
            // иначе вместе с ней уедет неотправленная очередь событий.
            .fallbackToDestructiveMigrationFrom(1, 2)
            .build()

    @Provides
    fun providePendingEventDao(db: AppDatabase): PendingEventDao = db.pendingEventDao()

    @Provides
    fun provideCachedAssignmentDao(db: AppDatabase): CachedAssignmentDao = db.cachedAssignmentDao()

    @Provides
    fun providePendingPhotoDao(db: AppDatabase): PendingPhotoDao = db.pendingPhotoDao()
}
