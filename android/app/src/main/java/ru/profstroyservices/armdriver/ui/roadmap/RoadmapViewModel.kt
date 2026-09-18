package ru.profstroyservices.armdriver.ui.roadmap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.TripDto
import ru.profstroyservices.armdriver.data.repository.AssignmentRepository
import ru.profstroyservices.armdriver.data.repository.DocumentRepository
import ru.profstroyservices.armdriver.data.repository.EventQueueRepository
import ru.profstroyservices.armdriver.data.repository.EventTypes
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import java.io.File
import javax.inject.Inject

private const val STATUS_CANCELLED = "Отменена"

data class TripUiState(
    val trip: TripDto,
    val doneTypes: Set<String>
) {
    val isCancelled: Boolean get() = trip.status == STATUS_CANCELLED
    val isResolved: Boolean get() = isCancelled || doneTypes.contains(EventTypes.RAZGRUZILSYA) || doneTypes.contains(EventTypes.SRYV)
    val nextAction: String? get() = EventTypes.TRIP_CYCLE.firstOrNull { it !in doneTypes }
}

sealed interface RoadmapUiState {
    data object Loading : RoadmapUiState
    data class Error(val message: String) : RoadmapUiState
    data class Content(
        val assignmentId: String,
        val trips: List<TripUiState>,
        val shiftEnded: Boolean
    ) : RoadmapUiState
}

@HiltViewModel
class RoadmapViewModel @Inject constructor(
    private val assignmentRepository: AssignmentRepository,
    private val eventQueue: EventQueueRepository,
    private val documents: DocumentRepository,
    private val settings: DriverSettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RoadmapUiState>(RoadmapUiState.Loading)
    val uiState: StateFlow<RoadmapUiState> = _uiState.asStateFlow()

    // События + фото вместе — водителю не важно, что именно "не отправлено",
    // важно, что очередь не пуста и надо когда-нибудь нажать «Повторить».
    val unsentCount: StateFlow<Int> = combine(
        eventQueue.observeUnsentCount(),
        documents.observePendingCount()
    ) { events, photos -> events + photos }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private var driverId: String? = null

    init {
        viewModelScope.launch {
            val id = settings.driverId.first()
            if (id == null) {
                _uiState.value = RoadmapUiState.Error("GUID водителя не задан")
                return@launch
            }
            driverId = id

            val assignment = assignmentRepository.getCached(id)
            if (assignment == null) {
                _uiState.value = RoadmapUiState.Error("Разнарядка не найдена в кэше")
                return@launch
            }
            refresh(assignment)
            retryQueue()
        }
    }

    private suspend fun refresh(assignment: AssignmentDto) {
        val events = eventQueue.observeForAssignment(assignment.id).first()
        val trips = assignment.trips
            .sortedBy { it.order }
            .map { trip ->
                val doneTypes = events.filter { it.tripId == trip.id }.map { it.type }.toSet()
                TripUiState(trip, doneTypes)
            }
        _uiState.value = RoadmapUiState.Content(
            assignmentId = assignment.id,
            trips = trips,
            shiftEnded = events.any { it.type == EventTypes.OKONCHANIE_SMENY }
        )
    }

    fun onTripAction(trip: TripDto, type: String) {
        val id = driverId ?: return
        val assignmentId = (_uiState.value as? RoadmapUiState.Content)?.assignmentId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(type = type, driverId = id, assignmentId = assignmentId, tripId = trip.id)
            reloadFromCache()
            retryQueue()
        }
    }

    fun onSryv(trip: TripDto, comment: String) {
        val id = driverId ?: return
        val assignmentId = (_uiState.value as? RoadmapUiState.Content)?.assignmentId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(
                type = EventTypes.SRYV,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id,
                comment = comment
            )
            reloadFromCache()
            retryQueue()
        }
    }

    fun onEndShift() {
        val id = driverId ?: return
        val assignmentId = (_uiState.value as? RoadmapUiState.Content)?.assignmentId ?: return
        viewModelScope.launch {
            eventQueue.enqueue(type = EventTypes.OKONCHANIE_SMENY, driverId = id, assignmentId = assignmentId)
            reloadFromCache()
            retryQueue()
        }
    }

    // Файл создаётся синхронно (просто File.createNewFile через File(...)),
    // сеть тут не участвует — можно дёргать прямо из Compose перед запуском
    // камеры.
    fun prepareCapture() = documents.createCaptureTarget()

    // Разгрузился фиксируется вместе с фото: сначала фото ставится в свою
    // очередь на загрузку (см. DocumentRepository — не блокирует событие,
    // если сети нет прямо сейчас), потом обычное событие Разгрузился.
    fun onPhotoCaptured(trip: TripDto, file: File) {
        val id = driverId ?: return
        val assignmentId = (_uiState.value as? RoadmapUiState.Content)?.assignmentId ?: return
        viewModelScope.launch {
            documents.enqueue(file = file, driverId = id, assignmentId = assignmentId, tripId = trip.id)
            eventQueue.enqueue(
                type = EventTypes.RAZGRUZILSYA,
                driverId = id,
                assignmentId = assignmentId,
                tripId = trip.id
            )
            reloadFromCache()
            retryQueue()
        }
    }

    fun onRetry() {
        viewModelScope.launch { retryQueue() }
    }

    private suspend fun retryQueue() {
        runCatching { eventQueue.sendPending() }
        runCatching { documents.uploadPending() }
        reloadFromCache()
    }

    private suspend fun reloadFromCache() {
        val id = driverId ?: return
        val assignment = assignmentRepository.getCached(id) ?: return
        refresh(assignment)
    }
}
