package ru.profstroyservices.armdriver.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class GeoFix(val latitude: Double, val longitude: Double, val accuracy: Int?, val fixTime: String)

// Разовая метка в момент нажатия кнопки — контракт 1С (architecture.md §14,
// п.9, ответ от 2026-10-08): последняя известная координата ОС, событие её
// не ждёт (не запрашиваем свежий фикс — это могло бы занять секунды и
// задержало бы сам факт). Нет разрешения или фикса в принципе — просто
// null: 1С прямо просит не слать 0,0 и не слать пустые элементы вместо
// отсутствующей метки.
//
// Порога «не старше N минут» намеренно нет (был 5 минут, убрали
// 2026-10-13): реальный водитель часто блокирует телефон между «Прибыл на
// разгрузку» и «Разгрузился» на время самой разгрузки — прогрев кэша в это
// время не идёт (приложение в фоне), и 5 минут регулярно не хватало,
// метка молча не уходила. Контракт для этого и даёт `ВремяОпределения`
// ("обязательно при неточной/несвежей метке") — 1С сама предусмотрела
// нечёткую метку с честным временем фиксации, а не только свежую или
// никакую. Отдельного порога не изобретаем.

// На свежем телефоне (или сразу после его обнуления) lastLocation() у
// Google Play Services пуст, пока хоть кто-то в системе не запросил
// координату активно хотя бы раз — проверено на эмуляторе через
// dumpsys location (last location=null у gps/network/fused разом).
// startWarming/stopWarming держат кэш тёплым, пока приложение на экране, —
// это не слежение за маршрутом между событиями (в GeoFix из этих апдейтов
// ничего не попадает сами по себе, читаем всё равно через lastLocation() в
// момент нажатия), а именно поддержание «последней известной ОС» свежей.
private const val WARM_INTERVAL_MILLIS = 3 * 60 * 1000L
private const val TAG = "GeoTagProvider"

@Singleton
class GeoTagProvider @Inject constructor(@ApplicationContext private val context: Context) {
    private val client: FusedLocationProviderClient by lazy {
        LocationServices.getFusedLocationProviderClient(context)
    }

    // Пустое тело — апдейты эту же координату и так кладут в системный кэш
    // GMS, нам отдельно хранить их не нужно: lastKnownFix() ниже читает
    // ровно тот же кэш в момент, когда она реально нужна.
    private val warmupCallback = object : LocationCallback() {}

    fun startWarming() {
        if (!hasPermission()) {
            Log.w(TAG, "Location warmup skipped: permission missing")
            return
        }
        // Экономичный режим не гарантирует получение GPS-фикса. При точном
        // разрешении используем высокую точность, только пока экран открыт.
        val precise = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val priority = if (precise) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY
        val request = LocationRequest.Builder(priority, WARM_INTERVAL_MILLIS)
            .setMinUpdateIntervalMillis(WARM_INTERVAL_MILLIS)
            .build()
        @Suppress("MissingPermission") // hasPermission() уже проверен выше
        runCatching {
            client.requestLocationUpdates(request, warmupCallback, Looper.getMainLooper())
                .addOnFailureListener { Log.e(TAG, "Location warmup failed", it) }
        }.onFailure { Log.e(TAG, "Location warmup failed", it) }
    }

    fun stopWarming() {
        runCatching {
            client.removeLocationUpdates(warmupCallback)
                .addOnFailureListener { Log.e(TAG, "Stopping location warmup failed", it) }
        }.onFailure { Log.e(TAG, "Stopping location warmup failed", it) }
    }

    suspend fun lastKnownFix(eventId: String): GeoFix? {
        if (!hasPermission()) {
            Log.w(TAG, "event=$eventId geo=missing reason=permission_missing")
            return null
        }
        val location = try {
            currentLastLocation()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "event=$eventId geo=missing reason=location_error", e)
            return null
        }
        if (location == null) {
            Log.w(TAG, "event=$eventId geo=missing reason=empty_location_cache")
            return null
        }
        if (location.latitude == 0.0 && location.longitude == 0.0) {
            Log.w(TAG, "event=$eventId geo=missing reason=zero_coordinates")
            return null
        }
        Log.i(TAG, "event=$eventId geo=present fixTime=${location.time}")
        return GeoFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = if (location.hasAccuracy()) location.accuracy.toInt() else null,
            fixTime = OffsetDateTime.ofInstant(Instant.ofEpochMilli(location.time), ZoneId.systemDefault())
                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    // getLastLocation() — кэш системы, обычно отвечает мгновенно, в отличие
    // от запроса нового фикса (requestLocationUpdates), поэтому не держит
    // отправку события.
    private suspend fun currentLastLocation(): Location? = suspendCancellableCoroutine { cont ->
        @Suppress("MissingPermission") // hasPermission() уже проверен выше
        client.lastLocation
            .addOnSuccessListener { location -> cont.resumeWith(Result.success(location)) }
            .addOnFailureListener { cont.resumeWith(Result.failure(it)) }
            .addOnCanceledListener {
                cont.resumeWith(Result.failure(IllegalStateException("Location task cancelled")))
            }
    }
}
