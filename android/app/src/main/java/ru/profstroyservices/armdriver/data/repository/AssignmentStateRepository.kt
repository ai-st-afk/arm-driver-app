package ru.profstroyservices.armdriver.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import ru.profstroyservices.armdriver.data.db.CachedAssignmentDao
import ru.profstroyservices.armdriver.data.db.PendingEventDao
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import java.util.concurrent.ConcurrentHashMap
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
    private val settings: DriverSettingsRepository,
    private val eventQueue: EventQueueRepository,
    private val queue: QueueRepository
) {
    private val listRefreshes = ConcurrentHashMap<String, RefreshGate>()
    private val detailRefreshes = ConcurrentHashMap<String, RefreshGate>()

    fun observeStates(): Flow<List<AssignmentState>> =
        settings.driverId.flatMapLatest { driverId ->
            if (driverId == null) {
                flowOf(emptyList())
            } else {
                val decoded = cache.observeAllForDriver(driverId)
                    .distinctUntilChanged()
                    .map { cached -> cached.map { it.toAssignmentDto() to it.updatedAt } }
                combine(decoded, events.observeAll().distinctUntilChanged()) { cached, allEvents ->
                    val byAssignment = allEvents.groupBy { it.assignmentId }
                    cached.map { (assignment, updatedAt) ->
                        assignmentState(assignment, byAssignment[assignment.id].orEmpty())
                            .copy(updatedAt = updatedAt)
                    }
                }
            }
        }.distinctUntilChanged().flowOn(Dispatchers.Default)

    fun observeState(assignmentId: String): Flow<AssignmentState?> =
        observeStates().map { states -> states.firstOrNull { it.id == assignmentId } }

    fun observeCurrent(): Flow<AssignmentState?> = observeStates().map(::currentAssignment)

    // Свежий список с шлюза. Разнарядки, которых шлюз больше не отдаёт
    // (вышли из окна выдачи), из кэша убираем — кроме той, где смена ещё
    // открыта: её водитель должен закрыть сам.
    suspend fun refresh(force: Boolean = false): Result<Unit> {
        val driverId = settings.driverId.first() ?: return Result.success(Unit)
        return listRefreshes.getOrPut(driverId) { RefreshGate() }.run(force) {
            withContext(Dispatchers.Default) {
                val before = observeStates().first()
                assignments.refreshList(driverId).map { fresh ->
                    reacknowledgeTrivialVersionBumps(before, fresh, driverId)
                    val openShift = observeStates().first()
                        .filter { it.phase == AssignmentPhase.IN_SHIFT }
                        .map { it.id }
                    cache.deleteForDriverExcept(driverId, fresh.map { it.id } + openShift)
                }
            }
        }
    }

    suspend fun refreshOne(assignmentId: String): Result<Unit> {
        val driverId = settings.driverId.first() ?: return Result.success(Unit)
        return detailRefreshes.getOrPut("$driverId/$assignmentId") { RefreshGate() }.run {
            withContext(Dispatchers.Default) {
                val before = observeStates().first().firstOrNull { it.id == assignmentId }
                runCatching { assignments.getById(assignmentId, driverId) }
                    .onFailure { if (it is CancellationException) throw it }
                    .map { fresh ->
                        if (before != null) reacknowledgeTrivialVersionBumps(listOf(before), listOf(fresh), driverId)
                    }
            }
        }
    }

    // Выдача ключей механиком приходит той же разнарядкой с увеличенной
    // версией (нововведение 2026-10-13) — а 1С отбивает НачалоСмены, если
    // Ознакомление записано не под текущей версией (DEVLOG, "1С 2.4").
    // Раньше это заставляло водителя заново жать «Ознакомился и принял»
    // из-за одного лишь флага ключей, хотя маршрут вообще не менялся —
    // кнопка должна была просто разблокироваться. Если единственная
    // разница между версиями — ключи/время их выдачи, подтверждаем новую
    // версию автоматически, без участия водителя. Если поменялось что-то
    // ещё (рейсы, машина, план) — это настоящее изменение, подтверждать
    // должен сам водитель, как и раньше.
    private suspend fun reacknowledgeTrivialVersionBumps(
        before: List<AssignmentState>,
        fresh: List<AssignmentDto>,
        driverId: String
    ) {
        val freshById = fresh.associateBy { it.id }
        var reacknowledged = false
        for (old in before) {
            if (old.phase != AssignmentPhase.ACCEPTED) continue
            val new = freshById[old.id] ?: continue
            if (!isTrivialKeysVersionBump(old.assignment, new)) continue
            eventQueue.enqueue(
                type = EventTypes.OZNAKOMLENIE,
                driverId = driverId,
                assignmentId = new.id,
                assignmentVersion = new.version
            )
            reacknowledged = true
        }
        if (reacknowledged) queue.flush()
    }
}

// Вынесено как чистая функция — тестируется без Room/DI, по образцу
// currentAssignment(). Версия выросла, а всё остальное (рейсы, машина,
// план, статус) структурно совпало — значит разница только в
// КлючиВыданы/ВремяВыдачиКлючей, настоящего пересмотра разнарядки не было.
fun isTrivialKeysVersionBump(old: AssignmentDto, new: AssignmentDto): Boolean {
    if (new.version <= old.version) return false
    return old.copy(version = new.version, keysIssued = new.keysIssued, keysIssuedAt = new.keysIssuedAt) == new
}
