package ru.profstroyservices.armdriver.ui.shift

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.repository.QueueRepository
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject

sealed interface ShiftUiState {
    data object Hidden : ShiftUiState
    data class Active(val assignmentId: String, val assignmentNumber: String?) : ShiftUiState
}

// «Закончить смену» жила в каждой разнарядке отдельно — при нескольких
// разнарядках за день выглядело так, будто у каждой своя смена. Смена одна,
// поэтому кнопка теперь общая для всего приложения (см. MainScaffold), а
// не привязана к тому, какую разнарядку водитель сейчас смотрит. «Начать
// смену» осталась в самой разнарядке — там это осмысленный выбор, с какой
// разнарядки начинается работа.
@HiltViewModel
class ShiftViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val queue: QueueRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<ShiftUiState>(ShiftUiState.Hidden)
    val uiState: StateFlow<ShiftUiState> = _uiState.asStateFlow()

    private var driverId: String? = null
    private var watchJob: Job? = null

    init {
        refresh()
    }

    // Список разнарядок редко меняется в рамках сессии, но при холодном
    // старте кэш ещё может быть пуст (его наполняет своим сетевым запросом
    // экран «Разнарядка», а не этот ViewModel) — поэтому тут тоже свой
    // запрос к сети, как и на остальных экранах, а не только чтение кэша.
    // Watch пересобирается по новому набору id при каждом вызове, в том
    // числе при возврате в приложение (см. MainScaffold).
    fun refresh() {
        viewModelScope.launch {
            val id = driverId ?: settings.driverId.first() ?: return@launch
            driverId = id
            val assignments = assignmentRepository.refreshList(id).getOrNull()
                ?: assignmentRepository.getCachedList(id)

            watchJob?.cancel()
            if (assignments.isEmpty()) {
                _uiState.value = ShiftUiState.Hidden
                return@launch
            }

            val perAssignment = assignments.map { assignment ->
                eventQueue.observeForAssignment(assignment.id).map { events ->
                    val started = events.any { it.type == EventTypes.NACHALO_SMENY && !it.cancelled }
                    val ended = events.any { it.type == EventTypes.OKONCHANIE_SMENY && !it.cancelled }
                    if (started && !ended) assignment else null
                }
            }
            watchJob = viewModelScope.launch {
                combine(perAssignment) { results -> results.firstOrNull { it != null } }
                    .collect { active ->
                        _uiState.value = active?.let { ShiftUiState.Active(it.id, it.number) } ?: ShiftUiState.Hidden
                    }
            }
        }
    }

    fun onEndShift() {
        val state = _uiState.value as? ShiftUiState.Active ?: return
        val id = driverId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.OKONCHANIE_SMENY,
                driverId = id,
                assignmentId = state.assignmentId
            )
            queue.flush()
        }
    }
}
