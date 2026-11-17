package ru.profstroyservices.armdriver.ui.update

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.DialogProperties
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
    val mandatoryReady: StateFlow<Boolean> = updater.mandatoryReady
    val installationHeld: StateFlow<Boolean> = updater.installationHeld

    fun checkNow() = updater.check(force = true)
    fun installNow() = updater.installNow()
    fun confirm(intent: Intent) = updater.confirmInstall(intent)
    fun holdInstall(): () -> Unit = updater.holdInstall()
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
    val mandatory by viewModel.mandatoryReady.collectAsState()
    val context = LocalContext.current
    var permissionDismissed by rememberSaveable { mutableStateOf(false) }

    when (val current = state) {
        is UpdateState.NeedsConfirmation -> BannerRow(
            title = if (mandatory) "Обязательное обновление ${current.versionName}" else "Обновление ${current.versionName} ждёт подтверждения",
            text = "Нажмите «Установить» в системном окне.",
            action = "Подтвердить установку",
            onAction = { viewModel.confirm(current.intent) }
        )

        is UpdateState.AwaitingSystemInstall -> BannerRow(
            title = "Подтвердите обновление ${current.versionName}",
            text = "Дождитесь системного окна и нажмите в нём «Установить». Повторно запускать установку не нужно.",
            action = null,
            onAction = {}
        )

        is UpdateState.InstallFailed -> BannerRow(
            title = "Обновление ${current.versionName} не установилось",
            text = current.message,
            action = "Повторить",
            onAction = viewModel::installNow
        )

        is UpdateState.Ready -> if (installAllowed) {
            BannerRow(
                title = if (mandatory) "Обязательное обновление ${current.versionName}" else "Готова версия ${current.versionName}",
                text = if (mandatory) {
                    "Закончите текущий рейс и установите обновление перед следующим."
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    "Установится сама, когда свернёте приложение. «Установить сейчас» закроет его на несколько секунд."
                } else {
                    "Нажмите «Установить» здесь, затем подтвердите обновление в системном окне Android."
                },
                action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Установить сейчас" else "Установить",
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
fun MandatoryUpdateDialog(
    pendingCount: Int,
    onRetryQueue: () -> Unit,
    viewModel: UpdateViewModel
) {
    val state by viewModel.state.collectAsState()
    val installAllowed by viewModel.installAllowed.collectAsState()
    val context = LocalContext.current
    val version = when (val current = state) {
        is UpdateState.Ready -> current.versionName
        is UpdateState.NeedsConfirmation -> current.versionName
        is UpdateState.Installing -> current.versionName
        is UpdateState.AwaitingSystemInstall -> current.versionName
        else -> return
    }
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Нужно обновить приложение") },
        text = {
            Text(
                "Версия $version уже скачана и проверена. Установите её перед следующим рейсом." +
                    if (pendingCount > 0) " Не отправлено: $pendingCount. Можно повторить отправку до установки; очередь сохранится и после обновления." else ""
            )
        },
        confirmButton = {
            when (val current = state) {
                is UpdateState.Ready -> Button(onClick = if (installAllowed) viewModel::installNow else {
                    { context.startActivity(installPermissionIntent(context.packageName)) }
                }) {
                    Text(if (installAllowed) "Установить" else "Разрешить установку")
                }
                is UpdateState.NeedsConfirmation -> Button(onClick = { viewModel.confirm(current.intent) }) {
                    Text("Подтвердить установку")
                }
                is UpdateState.Installing -> Button(onClick = {}, enabled = false) { Text("Устанавливается…") }
                is UpdateState.AwaitingSystemInstall -> Button(onClick = {}, enabled = false) { Text("Ждём подтверждения Android…") }
                else -> Unit
            }
        },
        dismissButton = {
            if (pendingCount > 0) TextButton(onClick = onRetryQueue) { Text("Повторить отправку") }
        }
    )
}

@Composable
private fun PermissionBanner(context: android.content.Context, onDismiss: (() -> Unit)?) {
    BannerRow(
        title = "Разрешите установку обновлений",
        text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Один раз включите «Разрешить установку» — дальше новые версии будут ставиться сами."
        } else {
            "Включите «Разрешить установку». На Android 10 и 11 каждое обновление нужно подтверждать в системном окне."
        },
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
    action: String?,
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
        if (action != null) OutlinedButton(
            onClick = onAction,
            modifier = Modifier.align(Alignment.End).padding(top = 4.dp, end = 12.dp)
        ) {
            Text(action)
        }
    }
}
