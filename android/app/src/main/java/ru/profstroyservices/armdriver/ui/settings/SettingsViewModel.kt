package ru.profstroyservices.armdriver.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

data class SettingsUiState(
    val driverName: String? = null,
    val vehicle: String? = null,
    val configured: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        load()
    }

    // Показываем ФИО и машину из последней разнарядки — чтобы водитель видел,
    // что телефон привязан именно к нему, не заглядывая в идентификаторы.
    fun load() {
        viewModelScope.launch {
            val driverId = settings.driverId.first()
            if (driverId == null) {
                _uiState.value = SettingsUiState(configured = false)
                return@launch
            }
            val latest = assignmentRepository.getCachedList(driverId).firstOrNull()
            _uiState.value = SettingsUiState(
                driverName = latest?.driver?.name,
                vehicle = latest?.vehicle?.let { vehicle ->
                    listOfNotNull(
                        vehicle.name?.takeIf { it.isNotBlank() },
                        vehicle.plate?.takeIf { it.isNotBlank() }
                    ).joinToString(" ").takeIf { it.isNotBlank() }
                },
                configured = true
            )
        }
    }
}
