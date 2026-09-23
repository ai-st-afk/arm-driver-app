package ru.profstroyservices.armdriver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dagger.hilt.android.HiltAndroidApp
import ru.profstroyservices.armdriver.push.ASSIGNMENT_NOTIFICATION_CHANNEL_ID

@HiltAndroidApp
class ArmDriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    // Канал нужен до первого уведомления — иначе система тихо отбрасывает
    // пуш вместо показа. Высокий приоритет и звук: разнарядка на 17:00 и
    // отмена рейса не то, что можно пропустить в тишине.
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            ASSIGNMENT_NOTIFICATION_CHANNEL_ID,
            "Разнарядки",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Новая разнарядка, напоминания о приёме, отмена или передача другому водителю"
        }
        getSystemService(Context.NOTIFICATION_SERVICE).let { it as NotificationManager }
            .createNotificationChannel(channel)
    }
}
