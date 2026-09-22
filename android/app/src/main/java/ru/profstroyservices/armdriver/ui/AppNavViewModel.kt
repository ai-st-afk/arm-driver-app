package ru.profstroyservices.armdriver.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface AppNavUiState {
    data object Loading : AppNavUiState
    data class Ready(val hasDriverId: Boolean) : AppNavUiState
}

// Решает только один вопрос — показывать первый запуск (onboarding) или
// сразу таб-бар (main). Дальше в это состояние никто не возвращается:
// GUID меняется через таб «Настройки», не через повторный onboarding.
@HiltViewModel
class AppNavViewModel @Inject constructor(
    settings: DriverSettingsRepository
) : ViewModel() {
    val state: StateFlow<AppNavUiState> = settings.driverId
        .map<String?, AppNavUiState> { AppNavUiState.Ready(hasDriverId = it != null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppNavUiState.Loading)
}
