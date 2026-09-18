package ru.profstroyservices.armdriver.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface AssignmentLoadState {
    data object Idle : AssignmentLoadState
    data object Loading : AssignmentLoadState
    data class Success(val raw: String) : AssignmentLoadState
    data class Error(val message: String) : AssignmentLoadState
}

data class SetupUiState(
    val driverIdInput: String = "",
    val savedDriverId: String? = null,
    val loadState: AssignmentLoadState = AssignmentLoadState.Idle
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val api: GatewayApi,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val prettyJson = Json { prettyPrint = true }

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

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

    fun onSaveAndCheck() {
        val driverId = _uiState.value.driverIdInput.trim()
        if (driverId.isEmpty()) return

        _uiState.value = _uiState.value.copy(loadState = AssignmentLoadState.Loading)
        viewModelScope.launch {
            settings.setDriverId(driverId)
            _uiState.value = _uiState.value.copy(savedDriverId = driverId)
            runCatching { api.getCurrentAssignment(driverId) }
                .onSuccess { assignment: AssignmentDto ->
                    val raw = prettyJson.encodeToString(AssignmentDto.serializer(), assignment)
                    _uiState.value = _uiState.value.copy(loadState = AssignmentLoadState.Success(raw))
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        loadState = AssignmentLoadState.Error(error.message ?: error.toString())
                    )
                }
        }
    }
}
