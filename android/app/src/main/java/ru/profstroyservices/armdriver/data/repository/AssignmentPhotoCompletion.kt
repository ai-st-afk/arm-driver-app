package ru.profstroyservices.armdriver.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val completedPhotosKey = stringSetPreferencesKey("completed_assignment_photos")

// Техническая отметка: четыре снимка сохранены в локальную очередь.
// Факт их приёма 1С по-прежнему виден через общую очередь отправки.
@Singleton
class AssignmentPhotoCompletion @Inject constructor(private val dataStore: DataStore<Preferences>) {
    fun observe(assignmentId: String): Flow<Set<PhotoKind>> = dataStore.data.map { prefs ->
        prefs[completedPhotosKey].orEmpty().mapNotNull { entry ->
            val prefix = "$assignmentId:"
            if (entry.startsWith(prefix)) PhotoKind.fromCode(entry.removePrefix(prefix)) else null
        }.toSet()
    }

    suspend fun isDone(assignmentId: String, kind: PhotoKind): Boolean = kind in observe(assignmentId).first()

    suspend fun markDone(assignmentId: String, kind: PhotoKind) {
        dataStore.edit { prefs ->
            prefs[completedPhotosKey] = prefs[completedPhotosKey].orEmpty() + "$assignmentId:${kind.code}"
        }
    }
}
