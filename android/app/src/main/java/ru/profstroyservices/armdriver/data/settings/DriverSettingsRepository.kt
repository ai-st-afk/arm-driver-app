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
private val DRIVER_NAME_KEY = stringPreferencesKey("driver_name")
private val DEVICE_ID_KEY = stringPreferencesKey("device_id")

@Singleton
class DriverSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    val driverId: Flow<String?> = dataStore.data.map { it[DRIVER_ID_KEY] }

    // ФИО привязанного водителя — чтобы видеть его в настройках сразу после
    // привязки, а не после первой разнарядки.
    val driverName: Flow<String?> = dataStore.data.map { it[DRIVER_NAME_KEY] }

    // GUID и ФИО меняются вместе: ФИО прежнего водителя не должно остаться
    // рядом с новым GUID. Тот же водитель без нового ФИО — прежнее остаётся.
    suspend fun bind(driverId: String, name: String?) {
        dataStore.edit { prefs ->
            val sameDriver = prefs[DRIVER_ID_KEY] == driverId
            prefs[DRIVER_ID_KEY] = driverId
            when {
                !name.isNullOrBlank() -> prefs[DRIVER_NAME_KEY] = name
                !sameDriver -> prefs.remove(DRIVER_NAME_KEY)
            }
        }
    }

    // Только если телефон всё ещё привязан к этому водителю: ответ сервера
    // мог прийти уже после перепривязки к другому.
    suspend fun setDriverName(driverId: String, name: String) {
        dataStore.edit { prefs ->
            if (prefs[DRIVER_ID_KEY] == driverId) prefs[DRIVER_NAME_KEY] = name
        }
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
