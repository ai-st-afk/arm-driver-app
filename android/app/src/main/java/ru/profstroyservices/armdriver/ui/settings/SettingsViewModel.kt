package ru.profstroyservices.armdriver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import retrofit2.HttpException
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.network.userMessage
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

data class SettingsUiState(
    val driverName: String? = null,
    // Почему ФИО нет: сервер не знает водителя (опечатка в GUID или ещё не
    // было разнарядок) или нет связи.
    val driverNameNote: String? = null,
    val vehicle: String? = null,
    val configured: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val settings: DriverSettingsRepository,
    private val api: GatewayApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val retry = MutableStateFlow(0)

    // ФИО — из привязки (QR или ответ шлюза), запасной вариант — последняя
    // разнарядка. Машина — только из разнарядки.
    init {
        viewModelScope.launch {
            combine(settings.driverId, settings.driverName, retry) { id, name, _ -> id to name }
                .collectLatest { (driverId, storedName) ->
                    if (driverId == null) {
                        _uiState.value = SettingsUiState(configured = false)
                        return@collectLatest
                    }
                    val latest = assignmentRepository.getCachedList(driverId).firstOrNull()
                    val name = storedName ?: latest?.driver?.name
                    _uiState.value = SettingsUiState(
                        driverName = name,
                        driverNameNote = if (name == null) "Загружаем ФИО…" else null,
                        vehicle = latest?.vehicle?.let { vehicle ->
                            listOfNotNull(
                                vehicle.name?.takeIf { it.isNotBlank() },
                                vehicle.plate?.takeIf { it.isNotBlank() }
                            ).joinToString(" ").takeIf { it.isNotBlank() }
                        },
                        configured = true
                    )
                    if (storedName == null) lookUpName(driverId, shownName = name)
                }
        }
    }

    // При возврате на экран: ФИО могло не загрузиться без связи.
    fun retryIfMissing() {
        if (_uiState.value.configured && _uiState.value.driverName == null) retry.value += 1
    }

    private suspend fun lookUpName(driverId: String, shownName: String?) {
        val note = try {
            // Сохранение снова запустит сбор выше — уже с ФИО.
            settings.setDriverName(driverId, api.getDriver(driverId).name)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() == 404) {
                "Сервер ещё не получал разнарядок этого водителя — ФИО появится с первой. " +
                    "Если разнарядки уже были, проверьте, что отсканирован QR нужного водителя."
            } else {
                "ФИО не загрузилось. ${e.userMessage()}"
            }
        } catch (e: Exception) {
            "ФИО не загрузилось. ${e.userMessage()}"
        }
        if (note != null && shownName == null) _uiState.value = _uiState.value.copy(driverNameNote = note)
    }
}
