package ru.profstroyservices.armdriver.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import javax.inject.Inject

data class HistoryItemUiState(
    val id: String,
    val typeLabel: String,
    val assignmentId: String,
    val tripId: String?,
    val time: String,
    val sent: Boolean,
    val error: String?
)

private val eventTypeLabels = mapOf(
    EventTypes.OZNAKOMLENIE to "Ознакомление",
    EventTypes.NACHALO_SMENY to "Начало смены",
    EventTypes.PRIBYL_NA_POGRUZKU to "Прибыл на погрузку",
    EventTypes.ZAGRUZILSYA_V_PUT to "Загрузился, в пути",
    EventTypes.PRIBYL_NA_RAZGRUZKU to "Прибыл на разгрузку",
    EventTypes.RAZGRUZILSYA to "Разгрузился",
    EventTypes.SRYV to "Ездка сорвана",
    EventTypes.OKONCHANIE_SMENY to "Окончание смены"
)

// Только чтение: полный локальный лог событий, включая уже отправленные
// (sent=1 не удаляется, см. PendingEventDao.markSent) — раньше эти данные
// нигде не показывались, хотя Room их и так хранил.
@HiltViewModel
class HistoryViewModel @Inject constructor(
    eventQueue: EventQueueRepository
) : ViewModel() {

    val items: StateFlow<List<HistoryItemUiState>> = eventQueue.observeAll()
        .map { events ->
            events.sortedByDescending { it.time }.map { event ->
                HistoryItemUiState(
                    id = event.id,
                    typeLabel = eventTypeLabels[event.type] ?: event.type,
                    assignmentId = event.assignmentId,
                    tripId = event.tripId,
                    time = event.time,
                    sent = event.sent,
                    error = event.lastError
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
