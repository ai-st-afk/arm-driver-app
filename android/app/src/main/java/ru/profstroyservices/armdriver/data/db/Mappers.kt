package ru.profstroyservices.armdriver.data.db

import kotlinx.serialization.json.Json
import ru.profstroyservices.armdriver.data.network.AssignmentDto
import ru.profstroyservices.armdriver.data.network.EventRequest
import ru.profstroyservices.armdriver.data.network.GeoTagRequest

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
    comment = comment,
    geo = toGeoTagRequest()
)

// Либо обе координаты есть, либо геометки не было вообще — так её и
// записывал GeoTagProvider при enqueue (см. EventQueueRepository).
private fun PendingEventEntity.toGeoTagRequest(): GeoTagRequest? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    return GeoTagRequest(latitude = lat, longitude = lon, accuracy = locationAccuracy, fixTime = locationFixTime)
}

fun AssignmentDto.toEntity(driverId: String, updatedAt: Long): CachedAssignmentEntity =
    CachedAssignmentEntity(
        id = id,
        driverId = driverId,
        assignmentJson = json.encodeToString(AssignmentDto.serializer(), this),
        version = version,
        updatedAt = updatedAt
    )

fun CachedAssignmentEntity.toAssignmentDto(): AssignmentDto =
    json.decodeFromString(AssignmentDto.serializer(), assignmentJson)
