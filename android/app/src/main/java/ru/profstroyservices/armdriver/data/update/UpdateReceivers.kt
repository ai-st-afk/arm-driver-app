package ru.profstroyservices.armdriver.data.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import ru.profstroyservices.armdriver.BuildConfig
import ru.profstroyservices.armdriver.MainActivity
import ru.profstroyservices.armdriver.R

const val UPDATE_NOTIFICATION_CHANNEL_ID = "updates"
private const val UPDATED_NOTIFICATION_ID = 2001

// Hilt-инъекция в BroadcastReceiver требует super.onReceive(), а у
// BroadcastReceiver он абстрактный — проще взять синглтон напрямую.
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppUpdaterEntryPoint {
    fun appUpdater(): AppUpdater
}

// Итог установки от PackageInstaller (см. AppUpdater.install).
class UpdateStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EntryPointAccessors.fromApplication(context.applicationContext, AppUpdaterEntryPoint::class.java)
            .appUpdater()
            .onInstallStatus(
                status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE),
                systemMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            )
    }
}

// Новая версия встала — после установки приложение закрыто, сама система
// его не откроет. Уведомление говорит водителю, что произошло, и открывает
// приложение по нажатию.
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, UPDATE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("АРМ водителя обновлён до ${BuildConfig.VERSION_NAME}")
            .setContentText("Нажмите, чтобы открыть")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(UPDATED_NOTIFICATION_ID, notification) }
    }
}
