package ru.profstroyservices.armdriver.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// С версии 3 миграции настоящие, а не destructive: снос базы при обновлении
// приложения уносит вместе с ней неотправленную очередь событий, а это факты
// смены, которых больше нигде нет (в 1С они ещё не дошли).
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_events ADD COLUMN lastError TEXT")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_events ADD COLUMN cancelled INTEGER NOT NULL DEFAULT 0")
    }
}

// Отказ загрузки фото (1С временно недоступна и т.п.) раньше проглатывался
// молча — та же дыра, что была у событий до lastError, только для фото.
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_photos ADD COLUMN lastError TEXT")
        // Существующие строки старше самой миграции — 0 сортирует их как
        // самые старые, точная дата тут не важна.
        db.execSQL("ALTER TABLE pending_photos ADD COLUMN enqueuedAt INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_events ADD COLUMN assignmentVersion INTEGER")
    }
}
