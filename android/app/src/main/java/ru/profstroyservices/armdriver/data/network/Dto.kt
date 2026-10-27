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
    val volume: String? = null,
    val positions: CargoPositionsDto? = null
)

@Serializable
data class CargoPositionsDto(val items: List<CargoPositionDto> = emptyList())

@Serializable
data class CargoPositionDto(
    @SerialName("product_id") val productId: String,
    val name: String,
    val unit: String,
    val quantity: String,
    val weight: String,
    val volume: String
)

@Serializable
data class CargoTotalDto(
    val positions: CargoPositionsDto,
    val weight: String,
    val volume: String
)

@Serializable
data class TripContactDto(
    val purpose: String,
    val role: String,
    val name: String,
    val phones: List<ContactPhoneDto> = emptyList()
)

@Serializable
data class ContactPhoneDto(val display: String, val number: String)

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
    val cargo: CargoDto = CargoDto(),
    val contacts: List<TripContactDto> = emptyList()
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
    // Механик выдал ключи своим АРМ, отдельно от диспетчера — пока false,
    // "Начать смену" недоступна (см. AssignmentState.canStartShift).
    // Старый кэш/разнарядки без этого контракта — default false, не true.
    @SerialName("keys_issued") val keysIssued: Boolean = false,
    @SerialName("keys_issued_at") val keysIssuedAt: String? = null,
    val trips: List<TripDto> = emptyList(),
    @SerialName("shift_total") val shiftTotal: CargoTotalDto? = null
)

@Serializable
data class AssignmentsListResponse(
    val assignments: List<AssignmentDto> = emptyList()
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
    val comment: String = "",
    val geo: GeoTagRequest? = null
)

// Разовая метка в момент нажатия кнопки — контракт 1С (architecture.md §14,
// п.9, 2026-10-08), не трекинг маршрута между событиями.
@Serializable
data class GeoTagRequest(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Int? = null,
    @SerialName("fix_time") val fixTime: String? = null
)

@Serializable
data class EventsRequest(
    val events: List<EventRequest>
)

@Serializable
data class EventResultDto(
    val id: String,
    val accepted: Boolean,
    val error: String? = null,
    // 1С сама код не присылает (только текст ошибки) — это код шлюза,
    // см. eventSendResultJSON на backend. null у старых записей/если
    // шлюз ещё не задеплоен с этим полем.
    val code: String? = null
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

@Serializable
data class DocumentUploadResponse(
    val status: String,
    val id: String? = null
)
