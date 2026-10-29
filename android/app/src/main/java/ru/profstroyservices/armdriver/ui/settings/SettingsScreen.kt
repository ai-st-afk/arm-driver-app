package ru.profstroyservices.armdriver.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ru.profstroyservices.armdriver.BuildConfig
import ru.profstroyservices.armdriver.data.update.UpdateState
import ru.profstroyservices.armdriver.ui.components.LabeledField
import ru.profstroyservices.armdriver.ui.update.UpdateViewModel
import ru.profstroyservices.armdriver.ui.update.installPermissionIntent

private val ButtonHeight = 56.dp

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val updateState by updateViewModel.state.collectAsState()
    val installAllowed by updateViewModel.installAllowed.collectAsState()
    val context = LocalContext.current
    var showService by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.retryIfMissing() }

    if (showService) {
        ServiceScreen(onClose = { showService = false })
        return
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Настройки", style = MaterialTheme.typography.headlineSmall)

            if (uiState.configured) {
                LabeledField(label = "Водитель", value = uiState.driverName ?: "—")
                uiState.driverNameNote?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
            }
            uiState.vehicle?.let { LabeledField(label = "Машина", value = it) }

            if (BuildConfig.DISPATCHER_PHONE.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${BuildConfig.DISPATCHER_PHONE}"))
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(ButtonHeight)
                ) {
                    Text("Позвонить диспетчеру", style = MaterialTheme.typography.labelLarge)
                }
            }

            LabeledField(label = "Версия приложения", value = BuildConfig.VERSION_NAME)
            Text(text = updateStatusText(updateState, installAllowed), style = MaterialTheme.typography.bodySmall)
            if (!installAllowed) {
                OutlinedButton(
                    onClick = { context.startActivity(installPermissionIntent(context.packageName)) },
                    modifier = Modifier.fillMaxWidth().height(ButtonHeight)
                ) {
                    Text("Разрешить автообновление", style = MaterialTheme.typography.labelLarge)
                }
            }
            OutlinedButton(
                onClick = updateViewModel::checkNow,
                enabled = updateState !is UpdateState.Checking && updateState !is UpdateState.Downloading &&
                    updateState !is UpdateState.Installing,
                modifier = Modifier.fillMaxWidth().height(ButtonHeight)
            ) {
                Text("Проверить обновления", style = MaterialTheme.typography.labelLarge)
            }

            TextButton(onClick = { showService = true }) {
                Text("Служебный вход")
            }
        }
    }
}

private fun updateStatusText(state: UpdateState, installAllowed: Boolean): String = when (state) {
    UpdateState.Idle, UpdateState.Checking -> "Проверяем обновления…"
    UpdateState.UpToDate -> "Установлена последняя версия."
    is UpdateState.Downloading -> "Скачивается версия ${state.versionName}: ${state.percent}%"
    is UpdateState.Ready -> if (installAllowed) {
        "Версия ${state.versionName} скачана и установится, когда свернёте приложение."
    } else {
        "Версия ${state.versionName} скачана. Чтобы она установилась, разрешите автообновление."
    }
    is UpdateState.Installing -> "Устанавливается версия ${state.versionName}…"
    is UpdateState.NeedsConfirmation -> "Версия ${state.versionName} ждёт подтверждения установки."
    is UpdateState.CheckFailed -> state.message
    is UpdateState.InstallFailed -> state.message
}
