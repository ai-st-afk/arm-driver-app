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
    // gateway при приёме от 1С, тут не дублируем эту логику.
    suspend fun refresh(driverId: String): Result<AssignmentDto> = runCatching {
        val assignment = api.getCurrentAssignment(driverId)
        cache.upsert(assignment.toEntity(driverId, updatedAt = System.currentTimeMillis()))
        assignment
    }

    suspend fun getCached(driverId: String): AssignmentDto? =
        cache.getForDriver(driverId)?.toAssignmentDto()
}
