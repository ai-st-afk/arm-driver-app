package ru.profstroyservices.armdriver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.network.userMessage
import ru.profstroyservices.armdriver.data.repository.PushRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface BindState {
    data object Idle : BindState
    data object Checking : BindState
    data class Bound(val driverName: String?, val assignments: Int) : BindState
    data class Error(val message: String) : BindState
}

data class ServiceUiState(
    val driverIdInput: String = "",
    val savedDriverId: String? = null,
    val bindState: BindState = BindState.Idle
)

// Служебный экран: привязка телефона к водителю по его GUID из 1С. Водитель
// сюда не ходит — телефон ему выдают уже настроенным. GUID сам по себе
// служит пропуском: не зная его, привязать телефон нельзя.
@HiltViewModel
class ServiceViewModel @Inject constructor(
    private val api: GatewayApi,
    private val settings: DriverSettingsRepository,
    private val pushRepository: PushRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServiceUiState())
    val uiState: StateFlow<ServiceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.getOrCreateDeviceId()
            val saved = settings.driverId.first()
            if (saved != null) {
                _uiState.value = _uiState.value.copy(driverIdInput = saved, savedDriverId = saved)
            }
        }
    }

    fun onDriverIdInputChange(value: String) {
        _uiState.value = _uiState.value.copy(driverIdInput = value)
    }

    // Сразу после сохранения дёргаем gateway: сотрудник, который выдаёт
    // телефон, должен увидеть ФИО привязанного водителя и убедиться, что
    // GUID не набран с опечаткой.
    fun onBind() {
        val driverId = _uiState.value.driverIdInput.trim()
        if (driverId.isEmpty()) return

        _uiState.value = _uiState.value.copy(bindState = BindState.Checking)
        viewModelScope.launch {
            settings.setDriverId(driverId)
            _uiState.value = _uiState.value.copy(savedDriverId = driverId)
            runCatching { api.getAssignments(driverId) }
                .onSuccess { response ->
                    val name = response.assignments.firstOrNull()?.driver?.name
                    _uiState.value = _uiState.value.copy(
                        bindState = BindState.Bound(
                            driverName = name,
                            assignments = response.assignments.size
                        )
                    )
                    // До привязки токен девайса некуда было слать (driver_id
                    // не известен) — теперь известен, шлём не дожидаясь
                    // следующей ротации токена (onNewToken).
                    pushRepository.registerCurrentToken()
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        bindState = BindState.Error(error.userMessage())
                    )
                }
        }
    }
}
