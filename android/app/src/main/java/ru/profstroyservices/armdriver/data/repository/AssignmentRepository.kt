package ru.profstroyservices.armdriver.data.repository

import ru.profstroyservices.armdriver.data.db.CachedAssignmentDao
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.db.toEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.GatewayApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.profstroyservices.armdriver.data.db.CachedAssignmentEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssignmentRepository @Inject constructor(
    private val api: GatewayApi,
    private val cache: CachedAssignmentDao
) {
    private val mutationMutex = Mutex()

    // Пакет пишется одной транзакцией Room: экраны не пересчитываются
    // после каждой отдельной разнарядки. JSON обрабатывается вне UI-потока.
    suspend fun refreshList(driverId: String): Result<List<AssignmentDto>> = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            runCatching {
                val assignments = api.getAssignments(driverId).assignments
                val previous = cache.getAllForDriver(driverId).associateBy { it.id }
                val updatedAt = System.currentTimeMillis()
                val updates = assignments.mapNotNull {
                    updatedAssignment(it, previous[it.id], driverId, updatedAt)
                }
                if (updates.isNotEmpty()) cache.upsertAll(updates)
                assignments
            }.onFailure { if (it is CancellationException) throw it }
        }
    }

    suspend fun clearCache() = mutationMutex.withLock { cache.clearAll() }

    suspend fun getCachedList(driverId: String): List<AssignmentDto> = withContext(Dispatchers.Default) {
        cache.getAllForDriver(driverId).map { it.toAssignmentDto() }
    }

    suspend fun getCachedById(assignmentId: String): AssignmentDto? =
        cache.getById(assignmentId)?.toAssignmentDto()

    // Время последней применённой версии; повтор той же версии его не меняет.
    suspend fun getCachedUpdatedAt(assignmentId: String): Long? =
        cache.getById(assignmentId)?.updatedAt

    // Точечный рефреш одной уже известной разнарядки (открыта из кэша/списка,
    // нужны свежие данные) — без похода за списком целиком. Кэшируем под
    // водителем этого телефона, а не под <Водитель> из документа: если
    // разнарядку передали другому, шлюз отдаст её этому водителю как
    // отменённую, и она должна остаться у него видна, а не уехать в кэше
    // к чужому водителю.
    suspend fun getById(assignmentId: String, driverId: String): AssignmentDto = withContext(Dispatchers.Default) {
        mutationMutex.withLock {
            val assignment = api.getAssignment(assignmentId, driverId)
            updatedAssignment(assignment, cache.getById(assignmentId), driverId, System.currentTimeMillis())
                ?.let { cache.upsert(it) }
            assignment
        }
    }
}

internal fun updatedAssignment(
    assignment: AssignmentDto,
    previous: CachedAssignmentEntity?,
    driverId: String,
    updatedAt: Long
): CachedAssignmentEntity? {
    if (previous != null && previous.driverId == driverId && assignment.version <= previous.version) return null
    val freshIds = assignment.trips.map { it.id }.toSet()
    val vanished = previous?.toAssignmentDto()?.trips.orEmpty()
        .filter { it.id !in freshIds }
        .map { it.copy(status = STATUS_CANCELLED) }
    return assignment.copy(trips = assignment.trips + vanished).toEntity(driverId, updatedAt)
}
