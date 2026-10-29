package ru.profstroyservices.armdriver

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import ru.profstroyservices.armdriver.data.update.AppUpdater
import ru.profstroyservices.armdriver.data.update.UPDATE_NOTIFICATION_CHANNEL_ID
import ru.profstroyservices.armdriver.push.ASSIGNMENT_NOTIFICATION_CHANNEL_ID
import javax.inject.Inject

@HiltAndroidApp
class ArmDriverApp : Application() {

    @Inject lateinit var appUpdater: AppUpdater

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Всё приложение на экране / всё свёрнуто — а не отдельное окно:
        // камера поверх главного экрана не считается «свернули».
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = appUpdater.onAppForeground()
            override fun onStop(owner: LifecycleOwner) = appUpdater.onAppBackground()
        })
    }

    // Канал нужен до первого уведомления — иначе система тихо отбрасывает
    // пуш вместо показа. Высокий приоритет и звук: разнарядка на 17:00 и
    // отмена рейса не то, что можно пропустить в тишине. Обновление —
    // наоборот, тихо: это просто сообщение, что приложение уже новое.
    private fun createNotificationChannels() {
        val assignments = NotificationChannel(
            ASSIGNMENT_NOTIFICATION_CHANNEL_ID,
            "Разнарядки",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Новая разнарядка, напоминания о приёме, отмена или передача другому водителю"
        }
        val updates = NotificationChannel(
            UPDATE_NOTIFICATION_CHANNEL_ID,
            "Обновления приложения",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Приложение обновилось до новой версии"
        }
        getSystemService(Context.NOTIFICATION_SERVICE).let { it as NotificationManager }
            .createNotificationChannels(listOf(assignments, updates))
    }
}
