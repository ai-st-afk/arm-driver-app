package ru.profstroyservices.armdriver.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import ru.profstroyservices.armdriver.data.repository.PushRepository
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

data class ServiceUiState(
    val driverIdInput: String = "",
    val savedDriverId: String? = null,
    val error: String? = null,
    val clearingAssignments: Boolean = false,
    val clearMessage: String? = null
)

private val GuidPattern =
    Regex("(?<![0-9a-fA-F])[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}(?![0-9a-fA-F])")

data class DriverQr(val driverId: String, val name: String?)

// Формат QR согласован с 1С 2026-10-28 (architecture.md §14 п.10): строка 1 —
// GUID водителя, строка 2 — ФИО, разделитель LF, UTF-8. Разбор терпимее
// формата («;», CRLF, любой порядок строк) — на случай QR, напечатанных
// не из 1С. GUID — только если
// он в QR один: из двух (водитель и разнарядка) угадывать нельзя. Регистр
// GUID — нижний, так его пишет 1С и так он лежит на шлюзе. ФИО — если
// кроме GUID ровно одна строка с буквами; QR из одного GUID тоже подходит,
// ФИО тогда подтянется со шлюза.
fun parseDriverQr(raw: String): DriverQr? {
    val guid = GuidPattern.findAll(raw).map { it.value.lowercase() }.distinct().singleOrNull() ?: return null
    val others = raw.split('\n', '\r', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() && !GuidPattern.containsMatchIn(it) }
    val name = others.singleOrNull()?.takeIf { part -> part.any { it.isLetter() } }
    return DriverQr(guid, name)
}

// Служебный экран: привязка телефона к водителю по его GUID из 1С. Водитель
// сюда не ходит — телефон ему выдают уже настроенным. GUID сам по себе
// служит пропуском: не зная его, привязать телефон нельзя.
@HiltViewModel
class ServiceViewModel @Inject constructor(
    private val settings: DriverSettingsRepository,
    private val pushRepository: PushRepository,
    private val assignments: AssignmentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServiceUiState())
    val uiState: StateFlow<ServiceUiState> = _uiState.asStateFlow()

    // Привязка сохранена — экран закрывается, открываются настройки с ФИО.
    private val _bound = Channel<Unit>(Channel.BUFFERED)
    val bound: Flow<Unit> = _bound.receiveAsFlow()

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

    fun onBind() {
        val driverId = _uiState.value.driverIdInput.trim()
        if (driverId.isNotEmpty()) bind(driverId, name = null)
    }

    // GUID из QR в поле ввода не кладём — его не нужно видеть на экране,
    // как и уже привязанный (см. init).
    fun onQrScanned(raw: String) {
        val qr = parseDriverQr(raw)
        if (qr == null) {
            _uiState.value = _uiState.value.copy(
                error = "В этом QR-коде нет идентификатора водителя. Нужен QR из карточки водителя в 1С."
            )
            return
        }
        bind(qr.driverId, qr.name)
    }

    fun onCameraDenied() {
        _uiState.value = _uiState.value.copy(
            error = "Нет доступа к камере. Разрешите её в настройках телефона или введите идентификатор вручную."
        )
    }

    // Привязка не ждёт сервера: сразу в настройки, ФИО там — из QR или
    // подтянется со шлюза (SettingsViewModel). Проверять, что GUID без
    // опечатки, теперь видно там же: сервер не знает водителя — это
    // написано рядом с ФИО.
    private fun bind(driverId: String, name: String?) {
        viewModelScope.launch {
            settings.bind(driverId, name)
            _uiState.value = _uiState.value.copy(savedDriverId = driverId, driverIdInput = "", error = null)
            _bound.send(Unit)
            // До привязки токен девайса некуда было слать (driver_id не
            // известен) — шлём сразу, не дожидаясь ротации токена. ViewModel
            // живёт дольше закрытого экрана (её владелец — вкладка/Activity).
            pushRepository.registerCurrentToken()
        }
    }
}
