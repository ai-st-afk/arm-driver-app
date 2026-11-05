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
    val old = previous?.toAssignmentDto()
    val cancelledNow = assignment.trips.any { trip ->
        trip.status == STATUS_CANCELLED && old?.trips?.firstOrNull { it.id == trip.id }?.status != STATUS_CANCELLED
    }
    val routeChanged = old != null && old.copy(
        version = assignment.version,
        keysIssued = assignment.keysIssued,
        keysIssuedAt = assignment.keysIssuedAt,
        keysAccepted = assignment.keysAccepted,
        keysAcceptedAt = assignment.keysAcceptedAt,
        shiftClosed = assignment.shiftClosed,
        shiftClosedAt = assignment.shiftClosedAt,
        // Подтверждать ознакомление нужно при изменении маршрута, а не при
        // промежуточном статусе Сорвана и причине, пришедшей из 1С.
        trips = old.trips.map { prior ->
            val incoming = assignment.trips.firstOrNull { it.id == prior.id }
            if (incoming?.status == STATUS_FAILED) prior.copy(status = incoming.status, cancelReason = incoming.cancelReason)
            else prior
        }
    ) != assignment
    val ackRequired = when {
        old == null -> assignment.version
        cancelledNow || routeChanged -> assignment.version
        else -> previous.ackRequiredVersion.takeIf { it > 0 } ?: previous.version
    }
    val changedUnload = assignment.trips.filter { trip ->
        old?.trips?.firstOrNull { it.id == trip.id }?.unloadPoint?.let { it != trip.unloadPoint } == true
    }.map { it.id }
    val visibleUnloadChanges = (changedUnload + previous?.changedUnloadTripIds.orEmpty().split(','))
        .filter { id -> id.isNotBlank() && assignment.trips.any { it.id == id } }
        .distinct()
    return assignment.copy(trips = assignment.trips + vanished).toEntity(driverId, updatedAt).copy(
        ackRequiredVersion = ackRequired,
        changedUnloadTripIds = visibleUnloadChanges.joinToString(",")
    )
}
