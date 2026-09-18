package ru.profstroyservices.armdriver.data.db

import kotlinx.serialization.json.Json
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.EventRequest

private val json = Json { ignoreUnknownKeys = true }

fun EventRequest.toEntity(): PendingEventEntity = PendingEventEntity(
    id = id,
    type = type,
    driverId = driverId,
    assignmentId = assignmentId,
    tripId = tripId,
    time = time,
    comment = comment
)

fun PendingEventEntity.toEventRequest(): EventRequest = EventRequest(
    id = id,
    type = type,
    driverId = driverId,
    assignmentId = assignmentId,
    tripId = tripId,
    time = time,
    comment = comment
)

fun AssignmentDto.toEntity(driverId: String, updatedAt: Long): CachedAssignmentEntity =
    CachedAssignmentEntity(
        driverId = driverId,
        assignmentJson = json.encodeToString(AssignmentDto.serializer(), this),
        version = version,
        updatedAt = updatedAt
    )

fun CachedAssignmentEntity.toAssignmentDto(): AssignmentDto =
    json.decodeFromString(AssignmentDto.serializer(), assignmentJson)
