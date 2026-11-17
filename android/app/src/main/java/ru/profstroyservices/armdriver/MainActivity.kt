package ru.profstroyservices.armdriver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import ru.profstroyservices.armdriver.data.location.GeoTagProvider
import ru.profstroyservices.armdriver.ui.AppNavHost
import ru.profstroyservices.armdriver.ui.theme.ArmDriverTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var geoTagProvider: GeoTagProvider

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArmDriverTheme {
                RequestStartupPermissions(onLocationGranted = geoTagProvider::startWarming)
                Column(Modifier.fillMaxSize()) {
                    if (BuildConfig.IS_TEST_BUILD) {
                        Text(
                            text = "ТЕСТОВАЯ БАЗА · test3",
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.errorContainer)
                                .statusBarsPadding()
                                .padding(8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    Box(Modifier.weight(1f)) { AppNavHost() }
                }
            }
        }
    }

    // Пока приложение на экране — держим кэш последней координаты тёплым
    // (см. GeoTagProvider.startWarming про lastLocation() пустой на свежем
    // телефоне), свёрнули — сразу гасим: это не фоновое слежение.
    override fun onStart() {
        super.onStart()
        geoTagProvider.startWarming()
    }

    override fun onStop() {
        super.onStop()
        geoTagProvider.stopWarming()
    }
}

// Все разрешения запрашиваются один раз при первом запуске, одним системным
// диалогом за другим — не по месту использования (не при первом нажатии
// кнопки шага), чтобы не удивлять водителя посреди рабочего действия.
// Уведомления: на Android 13+ разрешение обязательно — без него пуш о
// разнарядке на завтра (приходит вечером, приложение обычно закрыто) молча
// не покажется. Геолокация: foreground-only, под разовую метку к событию
// (GeoTagProvider, architecture.md §14 п.9) — отказ не блокирует работу,
// просто событие уйдёт без неё. Камера: своя камера приложения для фото на
// этапах и QR при привязке; если её отозвали — спросим ещё раз по месту
// (rememberCameraAccess).
@Composable
private fun RequestStartupPermissions(onLocationGranted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        // onStart() уже мог отработать до того, как водитель ответил на
        // диалог, — тогда startWarming() внутри него молча не сработал
        // (разрешения ещё не было). Второй шанс сразу после выдачи.
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true || result[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {
            onLocationGranted()
        }
    }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) &&
                !context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            if (!context.hasPermission(Manifest.permission.CAMERA)) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (missing.isNotEmpty()) {
            launcher.launch(missing.toTypedArray())
        } else {
            onLocationGranted()
        }
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
