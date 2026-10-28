package ru.profstroyservices.armdriver.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import ru.profstroyservices.armdriver.ui.camera.ScanDriverQr
import ru.profstroyservices.armdriver.ui.camera.rememberCameraAccess

private val ButtonHeight = 64.dp

@Composable
fun ServiceScreen(
    onClose: () -> Unit,
    viewModel: ServiceViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val currentOnClose by rememberUpdatedState(onClose)
    val scanLauncher = rememberLauncherForActivityResult(ScanDriverQr()) { raw ->
        raw?.let(viewModel::onQrScanned)
    }
    val requestCamera = rememberCameraAccess(onDenied = viewModel::onCameraDenied)

    LaunchedEffect(viewModel) { viewModel.bound.collect { currentOnClose() } }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(text = "Служебный вход", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Привязка телефона к водителю. Идентификатор водителя " +
                    "берётся из 1С, водителю его вводить не нужно.",
                style = MaterialTheme.typography.bodySmall
            )

            // GUID уже привязанного телефона не показываем в поле — он и
            // есть пропуск, показывать его в открытом виде тому, кто просто
            // зашёл в «Настройки», нельзя.
            if (uiState.savedDriverId != null) {
                Text(
                    text = "Телефон уже привязан. Чтобы привязать к другому водителю, отсканируйте его QR-код или введите GUID.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Button(
                onClick = { requestCamera { scanLauncher.launch(Unit) } },
                modifier = Modifier.fillMaxWidth().height(ButtonHeight)
            ) {
                Text("Сканировать QR-код", style = MaterialTheme.typography.labelLarge)
            }

            Text(text = "Или введите вручную:", style = MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value = uiState.driverIdInput,
                onValueChange = viewModel::onDriverIdInputChange,
                label = { Text("Идентификатор водителя из 1С") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedButton(
                onClick = viewModel::onBind,
                enabled = uiState.driverIdInput.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(ButtonHeight)
            ) {
                Text("Привязать по введённому", style = MaterialTheme.typography.labelLarge)
            }

            uiState.error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }

            Text(
                "Очистка убирает загруженные разнарядки только с этого телефона. " +
                    "События, фото и привязка водителя сохраняются. Если разнарядки есть на сервере, они загрузятся снова.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(
                onClick = viewModel::clearAssignments,
                enabled = !uiState.clearingAssignments,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (uiState.clearingAssignments) "Очистка…" else "Очистить разнарядки на телефоне")
            }
            uiState.clearMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                Text("Закрыть")
            }
        }
    }
}
