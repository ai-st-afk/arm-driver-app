package ru.profstroyservices.armdriver.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

// Обновление приложения поверх 0.7.x: очередь фото из базы версии 8 должна
// пережить миграцию — неотправленные снимки не теряются, приложение не падает.
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "migration-test.db"

    @After
    fun tearDown() {
        context.deleteDatabase(name)
    }

    // Схема версии 8 — как её создавал Room (AppDatabase_Impl до 0.8.0).
    private fun createVersion8() {
        context.deleteDatabase(name)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(8) {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_events` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `driverId` TEXT NOT NULL, `assignmentId` TEXT NOT NULL, `tripId` TEXT, `time` TEXT NOT NULL, `comment` TEXT NOT NULL, `sent` INTEGER NOT NULL, `lastError` TEXT, `cancelled` INTEGER NOT NULL, `assignmentVersion` INTEGER, `latitude` REAL, `longitude` REAL, `locationAccuracy` INTEGER, `locationFixTime` TEXT, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `cached_assignments` (`id` TEXT NOT NULL, `driverId` TEXT NOT NULL, `assignmentJson` TEXT NOT NULL, `version` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_cached_assignments_driverId` ON `cached_assignments` (`driverId`)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `pending_photos` (`id` TEXT NOT NULL, `tripId` TEXT NOT NULL, `driverId` TEXT NOT NULL, `assignmentId` TEXT NOT NULL, `filePath` TEXT NOT NULL, `uploaded` INTEGER NOT NULL, `lastError` TEXT, `enqueuedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))")
                        db.execSQL("INSERT INTO pending_photos (id, tripId, driverId, assignmentId, filePath, uploaded, lastError, enqueuedAt) VALUES ('p1', 't1', 'd1', 'a1', '/data/p1.jpg', 0, NULL, 1)")
                    }

                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    @Test
    fun pendingPhotoSurvivesUpgradeTo9() = runBlocking {
        createVersion8()

        // Room сверяет схему после миграции с сущностями — ошибка в миграции
        // здесь упадёт так же, как упала бы у водителя при обновлении.
        val db = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(MIGRATION_8_9)
            .build()
        val photos = db.pendingPhotoDao().getPending()
        db.close()

        assertEquals(listOf("p1"), photos.map { it.id })
        assertNull(photos.single().eventId)
        assertNull(photos.single().kind)
    }
}
