package ru.profstroyservices.armdriver.ui.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import ru.profstroyservices.armdriver.data.update.AppUpdater
import ru.profstroyservices.armdriver.data.update.UpdateState
import javax.inject.Inject

@HiltViewModel
class UpdateViewModel @Inject constructor(private val updater: AppUpdater) : ViewModel() {
    val state: StateFlow<UpdateState> = updater.state
    val installAllowed: StateFlow<Boolean> = updater.installAllowed

    fun checkNow() = updater.check(force = true)
    fun installNow() = updater.installNow()
    fun confirm(intent: Intent) = updater.confirmInstall(intent)
}

// Экран системных настроек, где для нашего приложения включается
// «Установка неизвестных приложений». Без этого тихое обновление невозможно.
fun installPermissionIntent(packageName: String) =
    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))

// Баннер только про то, где нужен водитель (или тот, кто выдаёт телефон):
// разрешить установку, подтвердить её или узнать, что она не удалась.
// Скачивание и «уже последняя версия» — в настройках, не на каждом экране.
@Composable
fun UpdateBanner(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    val installAllowed by viewModel.installAllowed.collectAsState()
    val context = LocalContext.current
    var permissionDismissed by rememberSaveable { mutableStateOf(false) }

    when (val current = state) {
        is UpdateState.NeedsConfirmation -> BannerRow(
            title = "Обновление ${current.versionName} ждёт подтверждения",
            text = "Нажмите «Обновить» в окне установки.",
            action = "Открыть",
            onAction = { viewModel.confirm(current.intent) }
        )

        is UpdateState.InstallFailed -> BannerRow(
            title = "Обновление ${current.versionName} не установилось",
            text = current.message,
            action = "Повторить",
            onAction = viewModel::installNow
        )

        is UpdateState.Ready -> if (installAllowed) {
            BannerRow(
                title = "Готова версия ${current.versionName}",
                text = "Установится сама, когда свернёте приложение. «Установить сейчас» закроет его на несколько секунд.",
                action = "Установить сейчас",
                onAction = viewModel::installNow
            )
        } else {
            PermissionBanner(context, onDismiss = null)
        }

        else -> if (!installAllowed && !permissionDismissed) {
            PermissionBanner(context, onDismiss = { permissionDismissed = true })
        }
    }
}

@Composable
private fun PermissionBanner(context: android.content.Context, onDismiss: (() -> Unit)?) {
    BannerRow(
        title = "Разрешите автообновление",
        text = "Один раз включите «Разрешить установку» — дальше новые версии будут ставиться сами.",
        action = "Разрешить",
        onAction = { context.startActivity(installPermissionIntent(context.packageName)) },
        onDismiss = onDismiss
    )
}

// Текст во всю ширину, кнопка под ним: рядом с кнопкой текст сжимался в
// узкую колонку, и баннер занимал треть экрана.
@Composable
private fun BannerRow(
    title: String,
    text: String,
    action: String,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            if (onDismiss != null) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Скрыть",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(end = 12.dp)
        )
        OutlinedButton(
            onClick = onAction,
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 12.dp)
        ) {
            Text(action)
        }
    }
}
