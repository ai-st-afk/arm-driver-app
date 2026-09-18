package ru.profstroyservices.armdriver.data.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Поля зеркалят backend/gateway/internal/httpapi/models.go — имена JSON-ключей
// менять только синхронно с backend.

@Serializable
data class PersonDto(
    val id: String,
    val name: String? = null,
    @SerialName("staff_id") val staffId: String? = null
)

@Serializable
data class VehicleDto(
    val id: String,
    val name: String? = null,
    val plate: String? = null
)

@Serializable
data class TrailerDto(
    val id: String? = null,
    val plate: String? = null
)

@Serializable
data class PointDto(
    val name: String? = null,
    val address: String? = null
)

@Serializable
data class CargoDto(
    val composition: String? = null,
    val quantity: String? = null,
    val unit: String? = null,
    val weight: String? = null,
    val volume: String? = null
)

@Serializable
data class TripDto(
    val id: String,
    val order: Int,
    val status: String,
    val customer: String? = null,
    val trailer: TrailerDto = TrailerDto(),
    @SerialName("load_point") val loadPoint: PointDto,
    @SerialName("unload_point") val unloadPoint: PointDto,
    @SerialName("plan_load") val planLoad: String? = null,
    @SerialName("plan_unload") val planUnload: String? = null,
    val cargo: CargoDto = CargoDto()
)

@Serializable
data class AssignmentDto(
    val id: String,
    val version: Int,
    val number: String? = null,
    @SerialName("departure_day") val departureDay: String,
    val status: String,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    val driver: PersonDto,
    val vehicle: VehicleDto,
    @SerialName("plan_depart") val planDepart: String? = null,
    @SerialName("plan_return") val planReturn: String? = null,
    val trips: List<TripDto> = emptyList()
)

@Serializable
data class DeviceRequest(
    @SerialName("driver_id") val driverId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("fcm_token") val fcmToken: String
)

@Serializable
data class DeviceResponse(
    val status: String
)

@Serializable
data class EventRequest(
    val id: String,
    val type: String,
    @SerialName("driver_id") val driverId: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("trip_id") val tripId: String? = null,
    val time: String,
    val comment: String = ""
)

@Serializable
data class EventsRequest(
    val events: List<EventRequest>
)

@Serializable
data class EventResultDto(
    val id: String,
    val accepted: Boolean,
    val error: String? = null
)

@Serializable
data class EventsResponse(
    val status: String,
    val events: List<EventResultDto> = emptyList(),
    val error: String? = null
)

@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null
)
