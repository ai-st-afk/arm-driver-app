package ru.profstroyservices.armdriver.data.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import retrofit2.HttpException
import ru.profstroyservices.armdriver.BuildConfig
import ru.profstroyservices.armdriver.data.network.AppReleaseDto
import ru.profstroyservices.armdriver.data.network.GatewayApi
import ru.profstroyservices.armdriver.data.network.userMessage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Downloading(val versionName: String, val percent: Int) : UpdateState
    data class Ready(val versionName: String) : UpdateState
    data class Installing(val versionName: String) : UpdateState
    // Система всё-таки спросила водителя (первая установка до разрешения,
    // оболочка производителя) — экран подтверждения открывается кнопкой.
    data class NeedsConfirmation(val versionName: String, val intent: Intent) : UpdateState
    // Проверка или скачивание не удались — видно только в настройках:
    // без сети это нормальное состояние, баннер на каждом экране — шум.
    data class CheckFailed(val message: String) : UpdateState
    data class InstallFailed(val versionName: String, val message: String) : UpdateState
}

private class UpdateException(message: String) : IOException(message)

// Самообновление: телефоны водителей сами забирают новую версию со шлюза,
// не нужно собирать их для ручной установки. Скачиваем сразу, как только
// шлюз сообщил о версии новее, — APK ~15 МБ, ждать Wi-Fi, которого у
// водителя может не быть неделями, дороже. Ставим тихо, когда водитель
// свернул приложение: установка закрывает его процесс, и посреди работы
// это выглядело бы как вылет. Тихая установка без вопросов — право
// приложения обновлять само себя (Android 12+), но только после того, как
// ему один раз разрешили «Установку неизвестных приложений».
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: GatewayApi,
    authInterceptor: Interceptor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "updates")

    // Отдельный клиент: у общего в отладочной сборке логирование тела, а
    // оно читает ответ целиком в память — 15 МБ APK в лог.
    private val downloadClient = OkHttpClient.Builder().addInterceptor(authInterceptor).build()
    private val downloadUrl = BuildConfig.GATEWAY_BASE_URL.trimEnd('/') + "/api/mobile/app/download"

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _installAllowed = MutableStateFlow(canInstall())
    val installAllowed: StateFlow<Boolean> = _installAllowed.asStateFlow()

    @Volatile private var ready: AppReleaseDto? = null
    @Volatile private var foreground = false
    @Volatile private var lastCheckAt = 0L
    private var checkJob: Job? = null
    private val installing = AtomicBoolean(false)
    private val holds = AtomicInteger(0)
    private val staleSessionsCleared = AtomicBoolean(false)

    fun onAppForeground() {
        foreground = true
        _installAllowed.value = canInstall()
        check(force = false)
    }

    fun onAppBackground() {
        foreground = false
        installInBackground()
    }

    // Пока идёт съёмка этапа, установку откладываем: она закрыла бы камеру
    // вместе с недоснятыми фото, и этап пришлось бы снимать заново.
    fun holdInstall(): () -> Unit {
        holds.incrementAndGet()
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) holds.decrementAndGet() }
    }

    @Synchronized
    fun check(force: Boolean) {
        if (checkJob?.isActive == true || installing.get()) return
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheckAt != 0L && now - lastCheckAt < CHECK_INTERVAL_MS) return
        lastCheckAt = now
        checkJob = scope.launch { runCheck() }
    }

    fun installNow() {
        scope.launch { install() }
    }

    private suspend fun runCheck() {
        if (staleSessionsCleared.compareAndSet(false, true)) abandonStaleSessions()
        if (ready == null) _state.value = UpdateState.Checking
        val release = try {
            api.latestApp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is HttpException && e.code() == 404) {
                _state.value = UpdateState.UpToDate
            } else if (ready == null) {
                Log.w(TAG, "update check failed", e)
                _state.value = UpdateState.CheckFailed("Не удалось проверить обновления. ${e.userMessage()}")
            }
            return
        }
        if (release.versionCode <= BuildConfig.VERSION_CODE) {
            ready = null
            dir.listFiles()?.forEach { it.delete() }
            _state.value = UpdateState.UpToDate
            return
        }
        val apk = apkFile(release)
        if (!apk.exists()) {
            ready = null
            try {
                download(release, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "update download failed", e)
                _state.value = UpdateState.CheckFailed(e.downloadMessage(release.versionName))
                return
            }
        }
        ready = release
        _state.value = UpdateState.Ready(release.versionName)
        if (!foreground) installInBackground()
    }

    private fun download(release: AppReleaseDto, target: File) {
        dir.mkdirs()
        dir.listFiles()?.filter { !it.name.startsWith(target.name) }?.forEach { it.delete() }
        val part = File(dir, target.name + ".part")
        var offset = part.length()
        if (offset < release.size) {
            _state.value = UpdateState.Downloading(release.versionName, percent(offset, release.size))
            val request = Request.Builder().url(downloadUrl).apply {
                // If-Range: если на сервере уже другой файл, он отдаст его
                // целиком, а не хвост чужого файла к нашему началу.
                if (offset > 0) header("Range", "bytes=$offset-").header("If-Range", "\"${release.sha256}\"")
            }.build()
            downloadClient.newCall(request).execute().use { response ->
                val append = when (response.code) {
                    206 -> true
                    200 -> false
                    else -> throw UpdateException("Сервер не отдал файл обновления. (http_${response.code})")
                }
                if (!append) offset = 0
                val body = response.body ?: throw UpdateException("Сервер не отдал файл обновления. (update_empty)")
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var shownPercent = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            offset += read
                            val percent = percent(offset, release.size)
                            if (percent != shownPercent) {
                                shownPercent = percent
                                _state.value = UpdateState.Downloading(release.versionName, percent)
                            }
                        }
                    }
                }
            }
        }
        if (!sha256(part).equals(release.sha256, ignoreCase = true)) {
            part.delete()
            throw UpdateException("Файл обновления пришёл повреждённым, скачаем заново. (update_checksum)")
        }
        val info = context.packageManager.getPackageArchiveInfo(part.path, PackageManager.PackageInfoFlags.of(0))
        if (info?.packageName != context.packageName || info.longVersionCode != release.versionCode.toLong()) {
            part.delete()
            throw UpdateException("На сервере неверный файл обновления. (update_mismatch)")
        }
        if (!part.renameTo(target)) throw UpdateException("Не удалось сохранить обновление. (update_storage)")
    }

    private fun installInBackground() {
        if (ready == null || holds.get() > 0 || !canInstall()) return
        if (_state.value !is UpdateState.Ready) return
        scope.launch { install() }
    }

    private fun install() {
        val release = ready ?: return
        val apk = apkFile(release)
        if (!apk.exists() || !installing.compareAndSet(false, true)) return
        _state.value = UpdateState.Installing(release.versionName)
        try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
                setSize(apk.length())
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Успех сюда обычно не доходит: установка закрывает процесс,
                // новая версия сама уберёт файл при первой проверке.
                session.commit(statusReceiver(sessionId).intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "update install failed to start", e)
            installing.set(false)
            _state.value = UpdateState.InstallFailed(
                release.versionName,
                "Обновление не установилось. (install_session)"
            )
        }
    }

    internal fun onInstallStatus(status: Int, systemMessage: String?, confirm: Intent?) {
        val release = ready ?: return
        installing.set(false)
        Log.i(TAG, "install status $status: $systemMessage")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                installing.set(true)
                if (confirm == null) return
                _state.value = UpdateState.NeedsConfirmation(release.versionName, confirm)
                if (foreground) confirmInstall(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> _state.value = UpdateState.UpToDate
            // Водитель нажал «Отмена» в системном окне — не ошибка, обновление
            // остаётся готовым и предложится снова.
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = UpdateState.Ready(release.versionName)
            else -> _state.value = UpdateState.InstallFailed(release.versionName, installFailureMessage(status))
        }
    }

    fun confirmInstall(intent: Intent) {
        runCatching { context.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Log.w(TAG, "cannot open install confirmation", it) }
    }

    private fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    // Сессии прошлого процесса (закоммиченные, но так и не подтверждённые)
    // иначе висят в системе до её собственной уборки через несколько дней.
    private fun abandonStaleSessions() {
        val installer = context.packageManager.packageInstaller
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
    }

    private fun statusReceiver(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, UpdateStatusReceiver::class.java),
        // Изменяемый: система дописывает в него статус установки.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    private fun apkFile(release: AppReleaseDto) = File(dir, "arm-driver-${release.versionCode}.apk")

    private companion object {
        const val TAG = "AppUpdater"
        const val CHECK_INTERVAL_MS = 30 * 60 * 1000L
    }
}

private fun percent(done: Long, total: Long): Int =
    if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun Throwable.downloadMessage(versionName: String): String = when (this) {
    is UpdateException -> message.orEmpty()
    is UnknownHostException, is ConnectException, is SocketTimeoutException ->
        "Версия $versionName не скачалась. ${userMessage()}"
    is IOException -> "Скачивание версии $versionName прервалось — докачаем при следующей проверке. (update_io)"
    else -> "Версия $versionName не скачалась. ${userMessage()}"
}

internal fun installFailureMessage(status: Int): String = when (status) {
    PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает памяти телефона для обновления. (install_storage)"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Телефон запретил установку обновления. (install_blocked)"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Обновление не подходит к установленному приложению. (install_conflict)"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Обновление не подходит к этому телефону. (install_incompatible)"
    PackageInstaller.STATUS_FAILURE_INVALID -> "Файл обновления повреждён. (install_invalid)"
    else -> "Обновление не установилось. (install_failure_$status)"
}
