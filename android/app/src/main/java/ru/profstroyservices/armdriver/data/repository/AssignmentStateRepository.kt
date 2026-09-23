package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ru.profstroyservices.armdriver.data.db.CachedAssignmentDao
import ru.profstroyservices.armdriver.data.db.PendingEventDao
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

// Единственное место, где считается состояние разнарядок. Раньше каждый
// экран считал его сам и по-своему — отсюда расхождения между плашкой,
// вкладкой рейсов и экраном разнарядки. Реактивно от Room: любое событие
// или новая версия разнарядки сразу видны на всех экранах.
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AssignmentStateRepository @Inject constructor(
    private val assignments: AssignmentRepository,
    private val cache: CachedAssignmentDao,
    private val events: PendingEventDao,
    private val settings: DriverSettingsRepository
) {
    fun observeStates(): Flow<List<AssignmentState>> =
        settings.driverId.flatMapLatest { driverId ->
            if (driverId == null) {
                flowOf(emptyList())
            } else {
                combine(cache.observeAllForDriver(driverId), events.observeAll()) { cached, allEvents ->
                    val byAssignment = allEvents.groupBy { it.assignmentId }
                    cached.map { entity ->
                        val assignment = entity.toAssignmentDto()
                        assignmentState(assignment, byAssignment[assignment.id].orEmpty())
                            .copy(updatedAt = entity.updatedAt)
                    }
                }
            }
        }

    fun observeState(assignmentId: String): Flow<AssignmentState?> =
        observeStates().map { states -> states.firstOrNull { it.id == assignmentId } }

    fun observeCurrent(): Flow<AssignmentState?> = observeStates().map(::currentAssignment)

    // Свежий список с шлюза. Разнарядки, которых шлюз больше не отдаёт
    // (вышли из окна выдачи), из кэша убираем — кроме той, где смена ещё
    // открыта: её водитель должен закрыть сам.
    suspend fun refresh(): Result<Unit> {
        val driverId = settings.driverId.first() ?: return Result.success(Unit)
        return assignments.refreshList(driverId).map { fresh ->
            val openShift = observeStates().first()
                .filter { it.phase == AssignmentPhase.IN_SHIFT }
                .map { it.id }
            cache.deleteForDriverExcept(driverId, fresh.map { it.id } + openShift)
        }
    }

    suspend fun refreshOne(assignmentId: String): Result<Unit> {
        val driverId = settings.driverId.first() ?: return Result.success(Unit)
        return runCatching { assignments.getById(assignmentId, driverId) }.map { }
    }
}
