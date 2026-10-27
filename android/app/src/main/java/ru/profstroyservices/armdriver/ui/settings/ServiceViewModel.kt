package ru.profstroyservices.armdriver.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.network.userMessage
import ru.profstroyservices.armdriver.data.repository.PushRepository
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
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
    val bindState: BindState = BindState.Idle,
    val clearingAssignments: Boolean = false,
    val clearMessage: String? = null
)

private val GuidPattern =
    Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])")

// Формат QR с 1С ещё не согласован, поэтому GUID берём, даже если вокруг
// него есть текст, — но только если он там один: из двух GUID (например,
// водитель и разнарядка) угадывать, какой из них водитель, нельзя. Регистр
// приводим к нижнему — так GUID пишет сама 1С и так он лежит на шлюзе.
fun extractDriverGuid(raw: String): String? =
    GuidPattern.findAll(raw).map { it.value.lowercase() }.distinct().singleOrNull()

// Служебный экран: привязка телефона к водителю по его GUID из 1С. Водитель
// сюда не ходит — телефон ему выдают уже настроенным. GUID сам по себе
// служит пропуском: не зная его, привязать телефон нельзя.
@HiltViewModel
class ServiceViewModel @Inject constructor(
    private val api: GatewayApi,
    private val settings: DriverSettingsRepository,
    private val pushRepository: PushRepository,
    private val assignments: AssignmentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServiceUiState())
    val uiState: StateFlow<ServiceUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.getOrCreateDeviceId()
            // GUID — это и есть пропуск (см. комментарий класса), поэтому
            // поле ввода НЕ предзаполняется его значением: экран открыт не
            // только тем, кто выдаёт телефон, а раньше уже привязанный GUID
            // был виден любому, кто зашёл в «Настройки» — эту дыру нашёл
            // автор. savedDriverId только для статуса "уже привязан", сам
            // GUID на экране не показываем.
            _uiState.value = _uiState.value.copy(savedDriverId = settings.driverId.first())
        }
    }

    fun onDriverIdInputChange(value: String) {
        _uiState.value = _uiState.value.copy(driverIdInput = value)
    }

    fun clearAssignments() {
        if (_uiState.value.clearingAssignments) return
        _uiState.value = _uiState.value.copy(clearingAssignments = true, clearMessage = null)
        viewModelScope.launch {
            try {
                assignments.clearCache()
                _uiState.value = _uiState.value.copy(clearMessage = "Разнарядки на телефоне очищены. События и фото сохранены.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ServiceViewModel", "Assignment cache clear failed", e)
                _uiState.value = _uiState.value.copy(clearMessage = "Не удалось очистить разнарядки. Попробуйте ещё раз.")
            } finally {
                _uiState.value = _uiState.value.copy(clearingAssignments = false)
            }
        }
    }

    fun onBind() = bind(_uiState.value.driverIdInput.trim())

    // GUID из QR в поле ввода не кладём — его не нужно видеть на экране,
    // как и уже привязанный (см. init).
    fun onQrScanned(raw: String) {
        val driverId = extractDriverGuid(raw)
        if (driverId == null) {
            _uiState.value = _uiState.value.copy(
                bindState = BindState.Error("В этом QR-коде нет идентификатора водителя. Нужен QR из карточки водителя в 1С.")
            )
            return
        }
        bind(driverId)
    }

    fun onCameraDenied() {
        _uiState.value = _uiState.value.copy(
            bindState = BindState.Error("Нет доступа к камере. Разрешите её в настройках телефона или введите идентификатор вручную.")
        )
    }

    // Сразу после сохранения дёргаем gateway: сотрудник, который выдаёт
    // телефон, должен увидеть ФИО привязанного водителя и убедиться, что
    // GUID не набран с опечаткой.
    private fun bind(driverId: String) {
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
