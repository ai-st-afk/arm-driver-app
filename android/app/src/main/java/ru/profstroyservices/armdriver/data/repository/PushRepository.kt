package ru.profstroyservices.armdriver.data.repository

import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import ru.profstroyservices.armdriver.data.network.DeviceRequest
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.settings.DriverSettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

// POST /api/mobile/devices существовал с самого начала (Stage 4), но до
// FCM-интеграции на Android его некому было вызывать. driver_id может быть
// ещё не привязан (первый запуск, до «Служебного входа») — тогда токен
// просто не регистрируется, следующий вызов (после НовыйТокен или после
// успешной привязки) отправит его.
@Singleton
class PushRepository @Inject constructor(
    private val api: GatewayApi,
    private val settings: DriverSettingsRepository
) {
    suspend fun registerToken(token: String): Result<Unit> = runCatching {
        val driverId = settings.driverId.first() ?: return@runCatching
        val deviceId = settings.getOrCreateDeviceId()
        api.registerDevice(DeviceRequest(driverId = driverId, deviceId = deviceId, fcmToken = token))
        Unit
    }

    // onNewToken в сервисе срабатывает редко (новая установка, ротация
    // токена Google Play services) — обычно уже до того, как водитель
    // привязан. После «Служебного входа» токен для этого driver_id иначе
    // не уйдёт до следующей ротации, поэтому дёргаем явно (ServiceViewModel).
    suspend fun registerCurrentToken(): Result<Unit> = runCatching {
        val token = currentToken() ?: return@runCatching
        registerToken(token).getOrThrow()
    }

    // Без kotlinx-coroutines-play-services ради одного вызова — Task
    // оборачивается вручную.
    // kotlin.coroutines.resume (не kotlinx.coroutines) — простое resumeWith,
    // без обязательного onCancellation у CancellableContinuation напрямую.
    private suspend fun currentToken(): String? = suspendCancellableCoroutine { cont ->
        runCatching {
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token -> cont.resumeWith(Result.success(token)) }
                .addOnFailureListener { cont.resumeWith(Result.success(null)) }
        }.onFailure { cont.resumeWith(Result.success(null)) }
    }
}
