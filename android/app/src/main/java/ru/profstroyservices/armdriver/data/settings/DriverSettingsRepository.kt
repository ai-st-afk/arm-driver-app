package ru.profstroyservices.armdriver.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val DRIVER_ID_KEY = stringPreferencesKey("driver_id")
private val DEVICE_ID_KEY = stringPreferencesKey("device_id")

@Singleton
class DriverSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val driverId: Flow<String?> = dataStore.data.map { it[DRIVER_ID_KEY] }

    suspend fun setDriverId(driverId: String) {
        dataStore.edit { it[DRIVER_ID_KEY] = driverId }
    }

    // device_id генерируется один раз на устройство и живёт до переустановки
    // приложения — используется как технический идентификатор в /api/mobile/devices,
    // не путать с driver_id (GUID водителя из 1С).
    suspend fun getOrCreateDeviceId(): String {
        var result = ""
        dataStore.edit { prefs ->
            result = prefs[DEVICE_ID_KEY] ?: UUID.randomUUID().toString().also { prefs[DEVICE_ID_KEY] = it }
        }
        return result
    }
}
