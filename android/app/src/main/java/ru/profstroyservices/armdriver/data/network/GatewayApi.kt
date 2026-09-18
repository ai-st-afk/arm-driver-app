package ru.profstroyservices.armdriver.data.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

interface GatewayApi {

    @GET("api/mobile/assignments/current")
    suspend fun getCurrentAssignment(@Query("driver_id") driverId: String): AssignmentDto

    @GET("api/mobile/assignments/{id}")
    suspend fun getAssignment(@Path("id") id: String): AssignmentDto

    @POST("api/mobile/devices")
    suspend fun registerDevice(@Body request: DeviceRequest): DeviceResponse

    @POST("api/mobile/events")
    suspend fun sendEvents(@Body request: EventsRequest): EventsResponse

    @Multipart
    @POST("api/mobile/documents")
    suspend fun uploadDocument(
        @Part("driver_id") driverId: RequestBody,
        @Part("assignment_id") assignmentId: RequestBody,
        @Part("trip_id") tripId: RequestBody,
        @Part photo: MultipartBody.Part
    ): DocumentUploadResponse
}
