package ru.profstroyservices.armdriver.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.profstroyservices.armdriver.MainActivity
import ru.profstroyservices.armdriver.R
import ru.profstroyservices.armdriver.data.repository.AssignmentStateRepository
import ru.profstroyservices.armdriver.data.repository.PushRepository
import javax.inject.Inject

// Совпадает с каналом в манифесте/шлюзе (internal/push/fcm.go) — заведён в
// ArmDriverApp.onCreate.
const val ASSIGNMENT_NOTIFICATION_CHANNEL_ID = "assignments"
private const val NOTIFICATION_ID = 1001

// Hilt поддерживает @AndroidEntryPoint и на Service, не только на
// Activity/Fragment — тот же DI-граф, что у остального приложения.
@AndroidEntryPoint
class ArmDriverFirebaseMessagingService : FirebaseMessagingService() {

    @Inject lateinit var pushRepository: PushRepository
    @Inject lateinit var states: AssignmentStateRepository

    // FirebaseMessagingService — не ViewModel, своего scope нет. SupervisorJob:
    // одно упавшее обновление не должно отменить остальные вызовы сервиса.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Новый токен — не только при первом запуске, а при любой переустановке,
    // очистке данных Google Play services и т.п. Дошлём его при первой же
    // возможности, не дожидаясь следующего онлайна вручную.
    override fun onNewToken(token: String) {
        scope.launch { pushRepository.registerToken(token) }
    }

    // FCM с одновременно notification+data не вызывает этот метод, пока
    // приложение свёрнуто/убито — тогда система сама рисует уведомление по
    // notification-блоку (см. default_notification_* в манифесте). Этот путь
    // срабатывает только когда приложение открыто — тогда системного показа
    // не будет вообще, если не показать самим.
    override fun onMessageReceived(message: RemoteMessage) {
        // Список кэширован локально — свежие данные подтянутся сами по
        // приходу в приложение (ON_RESUME), пуш только сигнал, что что-то
        // изменилось. Синхронный сетевой поход отсюда не нужен и рискованно
        // держать вне UI-цикла жизни экрана.
        scope.launch { states.refresh() }

        val title = message.notification?.title ?: return
        val body = message.notification?.body ?: ""
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, ASSIGNMENT_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification) }
    }
}
