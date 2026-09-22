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
