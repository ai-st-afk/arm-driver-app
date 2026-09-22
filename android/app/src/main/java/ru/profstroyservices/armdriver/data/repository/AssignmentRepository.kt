package ru.profstroyservices.armdriver.data.repository

import ru.profstroyservices.armdriver.data.db.CachedAssignmentDao
import ru.profstroyservices.armdriver.data.db.toAssignmentDto
import ru.profstroyservices.armdriver.data.db.toEntity
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.GatewayApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AssignmentRepository @Inject constructor(
    private val api: GatewayApi,
    private val cache: CachedAssignmentDao
) {
    // Инвариант из AGENTS.md: разнарядка приходит целиком с версией, версия
    // ниже сохранённой — устаревшее. Здесь просто перезаписываем кэшем
    // последнего успешного ответа gateway; сравнение версий уже делает
    // gateway при приёме от 1С, тут не дублируем эту логику. За день у
    // водителя может быть несколько разнарядок (Stage 8) — gateway отдаёт
    // список, кэшируем все.
    suspend fun refreshList(driverId: String): Result<List<AssignmentDto>> = runCatching {
        val assignments = api.getAssignments(driverId).assignments
        val updatedAt = System.currentTimeMillis()
        assignments.forEach { cache.upsert(it.toEntity(driverId, updatedAt)) }
        assignments
    }

    suspend fun getCachedList(driverId: String): List<AssignmentDto> =
        cache.getAllForDriver(driverId).map { it.toAssignmentDto() }

    suspend fun getCachedById(assignmentId: String): AssignmentDto? =
        cache.getById(assignmentId)?.toAssignmentDto()

    // Когда данные последний раз реально приходили с gateway — показываем
    // водителю «обновлено в HH:MM», чтобы он видел, свежая ли разнарядка.
    suspend fun getCachedUpdatedAt(assignmentId: String): Long? =
        cache.getById(assignmentId)?.updatedAt

    // Точечный рефреш одной уже известной разнарядки (открыта из кэша/списка,
    // нужны свежие данные) — без похода за списком целиком.
    suspend fun getById(assignmentId: String): AssignmentDto {
        val assignment = api.getAssignment(assignmentId)
        cache.upsert(assignment.toEntity(assignment.driver.id, updatedAt = System.currentTimeMillis()))
        return assignment
    }
}
